package uz.qoida.platform.loyalty.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the three things time does to a balance (ADR 0046).
 *
 * <p>All three are late-binding by nature, and none of them has a request to
 * hang off. A lot matures because a day passed; a lot expires because six months
 * passed; a hold dies because a checkout was abandoned and nobody came back to
 * say so. A platform without this loop has points that never become spendable, a
 * liability that never decays, and balances silently held by carts nobody
 * finished.
 *
 * <p>Every run is caught and logged rather than allowed to escape. A sweep that
 * dies on one bad row and takes the scheduler with it stops the other two, and
 * the symptom a month later is a liability report nobody can explain.
 *
 * <p>The switch exists so a one-shot process — a Flyway container, a rehearsal, a
 * console run against a restored copy — does not expire a real customer's points
 * as a side effect of being started.
 */
@Component
@ConditionalOnProperty(name = "qoida.loyalty.sweeper.enabled", havingValue = "true",
        matchIfMissing = true)
public class LoyaltySweeper {

    private static final Logger log = LoggerFactory.getLogger(LoyaltySweeper.class);

    private final LoyaltyMaintenanceService maintenance;

    public LoyaltySweeper(LoyaltyMaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    /**
     * Frequent, because the thing it delays is a customer being able to spend
     * what they can already see. The earn delay is measured in hours and this is
     * measured in minutes, so the lag it adds is invisible beside it.
     */
    @Scheduled(
            initialDelayString = "${qoida.loyalty.sweeper.initial-delay:PT1M}",
            fixedDelayString = "${qoida.loyalty.sweeper.maturity-interval:PT5M}")
    public void matureLots() {
        run("maturity", maintenance::matureLots);
    }

    /**
     * Hourly. Expiry is a date rather than an instant, so expiring a lot within
     * the hour is the same customer experience as expiring it on the second, and
     * an hourly pass keeps the batch small.
     */
    @Scheduled(
            initialDelayString = "${qoida.loyalty.sweeper.initial-delay:PT1M}",
            fixedDelayString = "${qoida.loyalty.sweeper.expiry-interval:PT1H}")
    public void expireLots() {
        run("expiry", maintenance::expireLots);
    }

    /**
     * Frequent, because a stale hold is a customer's own points locked away from
     * their own next attempt, and the second attempt usually happens within
     * minutes of the first one failing.
     */
    @Scheduled(
            initialDelayString = "${qoida.loyalty.sweeper.initial-delay:PT1M}",
            fixedDelayString = "${qoida.loyalty.sweeper.hold-interval:PT2M}")
    public void releaseStaleHolds() {
        run("stale holds", maintenance::releaseStaleHolds);
    }

    /**
     * Daily, and the only pass here that writes nothing.
     *
     * <p>The interval is a cost decision rather than an urgency one. The query
     * behind it aggregates every entry in the estate, which is the price of an
     * invariant stated over the whole ledger instead of over one account a
     * request happened to touch; hourly would buy nothing, because a drift does
     * not heal and the repair is a person's judgement either way.
     *
     * <p>Not routed through {@link #run} because that one reports rows touched,
     * and this pass touches none: it logs an error per drifting account and this
     * is the summary. A zero is the platform's daily statement that every points
     * balance is exactly the movements behind it.
     */
    @Scheduled(
            initialDelayString = "${qoida.loyalty.sweeper.initial-delay:PT1M}",
            fixedDelayString = "${qoida.loyalty.sweeper.reconciliation-interval:PT24H}")
    public void reconcileLedger() {
        try {
            int drifting = maintenance.reconcileLedger();
            if (drifting > 0) {
                log.error("Loyalty ledger reconciliation found {} points accounts whose balance is "
                        + "not the sum of their own movements", drifting);
            }
        } catch (RuntimeException failure) {
            log.error("Loyalty ledger reconciliation failed", failure);
        }
    }

    private void run(String pass, java.util.function.IntSupplier sweep) {
        try {
            int touched = sweep.getAsInt();
            if (touched > 0) {
                log.info("Loyalty {} sweep touched {} rows", pass, touched);
            }
        } catch (RuntimeException failure) {
            // Logged and swallowed. The next tick retries, and the alternative is
            // a dead scheduler that also stops the other two passes.
            log.error("Loyalty {} sweep failed", pass, failure);
        }
    }
}
