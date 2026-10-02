package uz.horecaos.platform.dinein.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.dinein.application.port.SessionOrderSource;
import uz.horecaos.platform.dinein.application.port.SessionOrderSource.RoundStatus;
import uz.horecaos.platform.dinein.domain.RoundStatuses;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SettingsRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.WalkInPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * What becomes of a guest's claim once its window has passed (ADR 0143, Decision 4
 * and The sweeper).
 *
 * <p>A claim becomes an ordinary session when a round the restaurant has accepted
 * is on it, or it lapses -- <em>in whatever status it has been moved to</em>. For
 * every live unconfirmed claim past {@code claim_expires_at} the sweep reads the
 * statuses of the rounds attached and does exactly one of three things:
 *
 * <ul>
 *   <li><b>Confirm</b> when a round is accepted ({@code confirmed_by =
 *       'round:<orderId>'}).
 *   <li><b>Defer</b> when a round is still in flight, but only until {@code
 *       claim_expires_at + walk_in_payment_defer_minutes}. That bound is anchored on
 *       the claim's own expiry, is not renewable, and is deliberately not the payment
 *       worker's stale-after: that is a deploy property which flags a stuck order for
 *       a person and never ends its payment, so it bounds nothing per claim.
 *   <li><b>Lapse</b> otherwise: no rounds, only failed ones, or an in-flight round past
 *       the bound. The table goes back to the room.
 * </ul>
 *
 * <p>The sweep selects on {@code closed_at IS NULL}, not on {@code status = 'OPEN'}:
 * a guest can move a session to {@code BILL_REQUESTED} with the table's token alone,
 * and the state machine has no {@code BILL_REQUESTED -> CLOSED} edge, so a sweeper
 * indexed on {@code OPEN} would never see, and could not close, a claim one tap had
 * moved out of it. {@link TableSessionService#lapseClaim} owns the way out.
 *
 * <p>Each claim is decided in its own transaction. A claim that loses a race (a
 * round attached, a host took charge, the guest asked for the bill) leaves its own
 * failure to the next sweep and does not hold up the rest.
 */
@Service
public class ClaimLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ClaimLifecycleService.class);

    /** One sweep's ceiling. A backlog drains over successive sweeps rather than in one long read. */
    static final int BATCH = 200;

    /** The actor of every automatic decision: a job, never mistaken for a person in an audit trail. */
    static final ActorRef SWEEPER = ActorRef.systemJob("dinein.claim-sweeper");

    /** What happened to one due claim. */
    public enum Outcome {
        CONFIRMED,
        DEFERRED,
        LAPSED,
        /** Somebody else decided first, or the row is no longer a due claim. */
        UNCHANGED
    }

    private final JdbcDineInStore store;
    private final SessionOrderSource orders;
    private final TableSessionService sessions;
    private final Clock clock;
    private final TransactionOperations transactions;

    public ClaimLifecycleService(
            JdbcDineInStore store,
            SessionOrderSource orders,
            TableSessionService sessions,
            Clock clock,
            TransactionOperations transactions) {
        this.store = store;
        this.orders = orders;
        this.sessions = sessions;
        this.clock = clock;
        this.transactions = transactions;
    }

    /**
     * One pass over every due claim, across every tenant.
     *
     * @return how many claims were confirmed or lapsed (a deferral decides nothing)
     */
    public int sweepOnce() {
        List<SessionRow> due = store.dueClaims(clock.instant(), BATCH);
        int decided = 0;
        for (SessionRow claim : due) {
            try {
                Outcome outcome = transactions.execute(status -> resolve(claim.tenantId(), claim.id()));
                if (outcome == Outcome.CONFIRMED || outcome == Outcome.LAPSED) {
                    decided++;
                }
            } catch (ApiException lostTheRace) {
                // A stale version means somebody moved the session between our read and
                // our write. That is not a failure: the next sweep reads what they left.
                if (lostTheRace.errorCode() != ErrorCode.STALE_VERSION) {
                    log.warn("Claim sweep could not settle a claim: {}", lostTheRace.errorCode());
                }
            } catch (RuntimeException failure) {
                // Log-and-continue, like every sweeper on the shared scheduler pool. The
                // message carries no tenant, table or account (ADR 0029).
                log.error("Claim sweep failed on one claim", failure);
            }
        }
        return decided;
    }

    /**
     * Decides one claim. The session is read again inside the transaction: the list the
     * sweep started from may be seconds old.
     */
    Outcome resolve(UUID tenantId, UUID sessionId) {
        Instant now = clock.instant();
        // Locked: an attach to this claim takes the same lock, so the rounds read below
        // are the rounds there will be when the decision lands.
        SessionRow claim = store.lockSession(tenantId, sessionId).orElse(null);
        if (claim == null
                || !claim.unconfirmedClaim()
                || !claim.status().live()
                || claim.claimExpiresAt() == null
                || claim.claimExpiresAt().isAfter(now)) {
            return Outcome.UNCHANGED;
        }

        List<RoundStatus> rounds = orders.rounds(tenantId, sessionId);

        RoundStatus accepted = rounds.stream()
                .filter(round -> RoundStatuses.accepted(round.status()))
                .findFirst()
                .orElse(null);
        if (accepted != null) {
            boolean confirmed = sessions.confirmClaimByRound(claim, accepted.orderId());
            return confirmed ? Outcome.CONFIRMED : Outcome.UNCHANGED;
        }

        boolean inFlight = rounds.stream().anyMatch(round -> RoundStatuses.inFlight(round.status()));
        if (inFlight) {
            WalkInPolicy policy = store.findSettings(tenantId, claim.locationId())
                    .map(SettingsRow::walkIn)
                    .orElse(WalkInPolicy.OFF);
            Instant bound = claim.claimExpiresAt().plus(Duration.ofMinutes(policy.paymentDeferMinutes()));
            if (now.isBefore(bound)) {
                return Outcome.DEFERRED;
            }
        }

        sessions.lapseClaim(tenantId, sessionId, SWEEPER);
        return Outcome.LAPSED;
    }
}
