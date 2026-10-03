package uz.horecaos.platform.integration.marketplace;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Winds {@link MarketplaceAvailabilityReconciler#tick()} to a clock instead of to luck (ADR
 * 0141): a frequent, log-and-continue pass. The tick is cheap when nothing differs — a handful
 * of indexed reads per binding — so the interval is short, and the expensive full recompute is
 * decided per binding by the reconciler's own {@code next_sweep_at}, not by this timer.
 *
 * <p>One tick at a time per process; across replicas the reconciler's advisory lock and row
 * leases are what keep two ticks from sending the same row twice. The switch exists for the
 * reason {@code InventoryReservationSweeper}'s does: a one-shot process — a Flyway container, a
 * rehearsal against a restored copy — must not tell a live aggregator anything as a side effect
 * of starting up.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.marketplace.availability.reconciler.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MarketplaceAvailabilityScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceAvailabilityScheduler.class);

    private final MarketplaceAvailabilityReconciler reconciler;
    private final AtomicBoolean running = new AtomicBoolean();

    public MarketplaceAvailabilityScheduler(MarketplaceAvailabilityReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(
            initialDelayString = "${horecaos.marketplace.availability.reconciler.initial-delay:PT20S}",
            fixedDelayString = "${horecaos.marketplace.availability.reconciler.interval:PT5S}")
    public void tick() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            reconciler.tick();
        } catch (RuntimeException failure) {
            // Logged and swallowed: the next tick retries, and a dead scheduler would also stop
            // every other module's timer sharing this pool (SchedulingConfiguration).
            log.error("Marketplace availability reconcile tick failed", failure);
        } finally {
            running.set(false);
        }
    }
}
