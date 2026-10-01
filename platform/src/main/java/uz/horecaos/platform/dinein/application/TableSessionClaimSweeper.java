package uz.horecaos.platform.dinein.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wires {@link ClaimLifecycleService#sweepOnce()} to a clock (ADR 0143).
 *
 * <p>The thing that makes a guest's claim a <em>claim</em> rather than a table
 * somebody can hold forever by tapping and leaving: without this, the unconfirmed
 * claim an abandoned phone leaves behind occupies its table until a person notices.
 * Same genre as {@code InventoryReservationSweeper}: frequent, best-effort,
 * log-and-continue, because what a missed tick delays is a table going back to the
 * room by seconds, and a wedged sweep must not take the outbox relay or any other
 * module's timer down with it ({@code SchedulingConfiguration}'s error handler).
 *
 * <p>Carries no tenant loop: one conditional read selects every tenant's due claims
 * and each is decided in its own transaction by {@link ClaimLifecycleService}.
 *
 * <p>The switch exists for the reason {@code InventoryReservationSweeper}'s does: a
 * one-shot process -- a Flyway container, a rehearsal, a console run against a restored
 * copy -- must not lapse a real tenant's claims as a side effect of starting up.
 */
@Component
@ConditionalOnProperty(name = "horecaos.dinein.claim-sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class TableSessionClaimSweeper {

    private static final Logger log = LoggerFactory.getLogger(TableSessionClaimSweeper.class);

    private final ClaimLifecycleService claims;

    public TableSessionClaimSweeper(ClaimLifecycleService claims) {
        this.claims = claims;
    }

    /**
     * Every thirty seconds: the shortest claim window is two minutes, so a claim
     * overstays by at most a quarter of its own life.
     */
    @Scheduled(
            initialDelayString = "${horecaos.dinein.claim-sweeper.initial-delay:PT30S}",
            fixedDelayString = "${horecaos.dinein.claim-sweeper.interval:PT30S}")
    public void sweepOnce() {
        try {
            int decided = claims.sweepOnce();
            if (decided > 0) {
                log.info("Claim sweep settled {} walk-in claims", decided);
            }
        } catch (RuntimeException failure) {
            // Logged and swallowed, not rethrown: the next tick retries, and the
            // alternative is a dead scheduler that also stops every other module's
            // timer sharing this pool (SchedulingConfiguration).
            log.error("Claim sweep failed", failure);
        }
    }
}
