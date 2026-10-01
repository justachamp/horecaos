package uz.horecaos.platform.dinein.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import uz.horecaos.platform.customers.api.CustomerBlacklistPort;
import uz.horecaos.platform.dinein.application.QrEntryService.GuestContext;
import uz.horecaos.platform.dinein.domain.BearerToken;
import uz.horecaos.platform.dinein.domain.QrMode;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SettingsRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.TableRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * A guest seats themselves at a free table (ADR 0143).
 *
 * <p>The guest holds two credentials and this service takes both: the table's guest
 * token (the proof of <em>where</em>, resolved to a row and never taken from the
 * request) and the signed-in customer's account (the proof of <em>who</em>, resolved
 * by the controller from the ordinary customer session). Neither a table, a location
 * nor a tenant is ever an input.
 *
 * <h2>The eligibility transaction</h2>
 *
 * Everything below runs in one transaction, in this order, and the order is the
 * design:
 *
 * <ol>
 *   <li>lock the branch's settings row {@code FOR NO KEY UPDATE} and read it;
 *   <li>lock the table's row {@code FOR UPDATE};
 *   <li>look for a live session at the table;
 *   <li>check the table and the party, the holds, the account, and only then the
 *       caps;
 *   <li>open the claim.
 * </ol>
 *
 * <p><b>Why the settings row.</b> The branch cap and the daily cap are aggregates
 * over rows that do not exist yet, and a lock on the one table being claimed says
 * nothing about a claim on <em>another</em> table: a person holding photographs of
 * several codes fires claims at different tables, each transaction counting zero
 * peers under READ COMMITTED, so a check-then-insert under the table lock alone would
 * let every one of them through. The settings row is the one row every claim at a
 * branch has in common, so locking it makes the branch's claims take turns: each waits
 * for the previous transaction to commit or roll back and then counts, on a fresh
 * snapshot, everything it left. The lock order is fixed -- settings row, then table
 * row -- and nothing else takes both in the other order (the reservation confirm and
 * amend take table rows only, the staff settings write takes the settings row only),
 * so there is no cycle.
 *
 * <p>The per-account cap does not rest on that lock alone: {@code
 * ux_claim_account_branch} refuses a second live unconfirmed claim by one account
 * whatever any transaction counted, and a violation of it answers exactly as a
 * reached cap does.
 *
 * <p>Transactions are run through {@link TransactionOperations} rather than a
 * {@code @Transactional} annotation so the lock is held in a code path wired by hand
 * as well as in the application -- a test that exercised an unlocked copy of this
 * would prove nothing about the one that ships.
 */
@Service
public class WalkInSeatingService {

    /**
     * Per guest token, not per source. Five opens a minute is far above what a person
     * with a phone does and well below what a script does; the coarse per-address limit
     * that catches volumetric abuse belongs to the edge (ADR 0033's division of labour).
     */
    private static final RateLimiter.Policy OPEN_LIMIT = RateLimiter.Policy.strictPerMinute(5);

    private static final String OPEN_OPERATION = "dinein.walkin.open";

    static final Duration DAILY_WINDOW = Duration.ofHours(24);

    /** The internal class of a refusal, for the counters. Never in a response. */
    public enum RefusalClass {
        DISABLED,
        TABLE_STATE,
        HELD,
        BLACKLISTED,
        ACCOUNT_CAP,
        DAILY_CAP,
        BRANCH_CAP,
        RATE_LIMITED
    }

    /**
     * The one refusal a guest can be given for a reason that is about the room or
     * about another guest. The class is for the counters and is not carried in the
     * response.
     */
    static final class NotAvailable extends ApiException {

        private final RefusalClass refusalClass;

        NotAvailable(RefusalClass refusalClass) {
            super(
                    ErrorCode.RESOURCE_CONFLICT,
                    TableSessionService.NOT_AVAILABLE_MESSAGE,
                    TableSessionService.NOT_AVAILABLE);
            this.refusalClass = refusalClass;
        }

        RefusalClass refusalClass() {
            return refusalClass;
        }
    }

    /** What the route returns: the session the guest is now at, and whether this call opened it. */
    public record Seating(SessionRow session, boolean created) {}

    private final QrEntryService qr;
    private final JdbcDineInStore store;
    private final TableSessionService sessions;
    private final CustomerBlacklistPort blacklist;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final ClaimMetrics metrics;

    public WalkInSeatingService(
            QrEntryService qr,
            JdbcDineInStore store,
            TableSessionService sessions,
            CustomerBlacklistPort blacklist,
            RateLimiter rateLimiter,
            Clock clock,
            TransactionOperations transactions,
            ClaimMetrics metrics) {
        this.qr = qr;
        this.store = store;
        this.sessions = sessions;
        this.blacklist = blacklist;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    /**
     * Seats a signed-in guest at the table their guest token names.
     *
     * @param guestToken the plaintext {@code X-Dine-In-Token}. Hashed for the rate
     *                   limit before any lookup, so a flood carrying one token costs
     *                   one index probe per request and a hash
     * @param accountId  the caller's customer account for this brand, from the
     *                   ordinary customer session -- never from the request
     * @param partySize  the guest's own word, checked against the table's seats
     */
    public Seating seat(String guestToken, UUID accountId, int partySize) {
        if (guestToken != null && !guestToken.isBlank()) {
            RateLimiter.Decision decision = rateLimiter.check(
                    new RateLimiter.Key(OPEN_OPERATION, null, BearerToken.hash(guestToken)), OPEN_LIMIT);
            if (!decision.allowed()) {
                metrics.refused(RefusalClass.RATE_LIMITED.name());
                throw new ApiException(
                        ErrorCode.RATE_LIMIT_EXCEEDED, "Too many attempts at this table. Try again shortly.");
            }
        }

        GuestContext guest = qr.resolve(guestToken);
        if (guest.mode() != QrMode.ORDER_AND_PAY) {
            // The same 404 every other ordering route gives a VIEW_ONLY code.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This code is not in service. Ask a member of staff.");
        }

        try {
            Seating seating = inTransaction(() -> attempt(guest, accountId, partySize));
            if (seating.created()) {
                metrics.opened();
            }
            return seating;
        } catch (NotAvailable refused) {
            metrics.refused(refused.refusalClass().name());
            throw refused;
        }
    }

    private Seating attempt(GuestContext guest, UUID accountId, int partySize) {
        // 1. The branch's settings row, locked. No row means the capability was never
        //    turned on; there is nothing to lock and nothing to count.
        SettingsRow settings = store.lockSettings(guest.tenantId(), guest.locationId())
                .orElseThrow(() -> new NotAvailable(RefusalClass.DISABLED));
        if (!settings.walkIn().selfSeat() || settings.qrMode() != QrMode.ORDER_AND_PAY) {
            throw new NotAvailable(RefusalClass.DISABLED);
        }

        // 2. The table's row, locked. Resolved from the token, so a guest edits nothing
        //    to reach another table.
        TableRow table = store.lockTables(guest.tenantId(), List.of(guest.tableId())).stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This code is not in service. Ask a member of staff."));

        // 3. Somebody already sits here: hand them the session. Idempotent by
        //    observation, like requestBill; the second guest to scan a table learns
        //    it is taken by being given its bill, not a second session.
        Optional<SessionRow> live = store.findLiveSessionAtTable(guest.tenantId(), table.id());
        if (live.isPresent()) {
            return new Seating(live.get(), false);
        }

        // 4. The table and the party.
        if (!"ACTIVE".equals(table.status())) {
            throw new NotAvailable(RefusalClass.TABLE_STATE);
        }
        if (partySize < 1 || partySize > table.seats()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "This table seats %d. A larger party is a member of staff's to seat.".formatted(table.seats()),
                    Map.of("seats", table.seats()));
        }

        // 5. Reservation holds win at the moment of opening.
        Instant now = clock.instant();
        Instant horizon = now.plus(Duration.ofMinutes(settings.walkIn().horizonMinutes()));
        if (store.tableHeldByConfirmedBooking(guest.tenantId(), table.id(), now, horizon)) {
            throw new NotAvailable(RefusalClass.HELD);
        }

        // 6. The account, then the caps. Counted now that this transaction holds the
        //    settings-row lock, so every earlier claim at the branch is committed and
        //    visible.
        if (blacklist.isCurrentlyBlacklisted(guest.tenantId(), accountId)) {
            throw new NotAvailable(RefusalClass.BLACKLISTED);
        }
        if (store.accountHasLiveUnconfirmedClaim(guest.tenantId(), guest.locationId(), accountId)) {
            throw new NotAvailable(RefusalClass.ACCOUNT_CAP);
        }
        if (store.countClaimsOpenedSince(guest.tenantId(), guest.locationId(), accountId, now.minus(DAILY_WINDOW))
                >= settings.walkIn().dailyClaimsPerAccount()) {
            throw new NotAvailable(RefusalClass.DAILY_CAP);
        }
        if (store.countLiveUnconfirmedClaims(guest.tenantId(), guest.locationId())
                >= settings.walkIn().maxUnconfirmed()) {
            throw new NotAvailable(RefusalClass.BRANCH_CAP);
        }

        // 7. Open the claim: the same row staff open, with the claim columns in the
        //    INSERT. A violation of ux_claim_account_branch answers as a reached cap.
        SessionRow opened;
        try {
            opened = sessions.open(
                    new TableSessionService.OpenSession(
                            guest.tenantId(),
                            guest.brandId(),
                            guest.locationId(),
                            null,
                            List.of(table.id()),
                            partySize,
                            settings.sessionCurrency(),
                            "guest:" + accountId,
                            new TableSessionService.Claim(
                                    accountId,
                                    now.plus(
                                            Duration.ofMinutes(settings.walkIn().claimTtlMinutes())))),
                    "Opened by the guest at the table");
        } catch (ApiException conflict) {
            if ("TABLE_NOT_AVAILABLE".equals(conflict.properties().get("conflict"))) {
                throw new NotAvailable(RefusalClass.ACCOUNT_CAP);
            }
            throw conflict;
        }
        return new Seating(opened, true);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }
}
