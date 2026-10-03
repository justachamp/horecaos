package uz.horecaos.platform.inventory.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Marks stops whose end has passed as {@code EXPIRED} (ADR 0141 Decision 5): writes the
 * audit fact and emits the event so the digest and the marketplace reconciler hear about it
 * sooner.
 *
 * <p>Not on any correctness path, and that is the design. A read evaluates {@code ends_at}
 * itself ({@link AvailabilityResolver}), and the reconciler's resync sweep evaluates it at its
 * own {@code now}, so a sweeper that is down, lagging or switched off delays a notice and
 * never leaves a dish stopped past its end. One cross-tenant pass per tick, through the ADR
 * 0056 exempt role, log-and-continue like {@link InventoryReservationSweeper}; the same
 * switch shape keeps a one-shot process from touching a real tenant's stops on start-up.
 */
@Component
@ConditionalOnProperty(name = "horecaos.inventory.stop-expiry.enabled", havingValue = "true", matchIfMissing = true)
public class InventoryStopExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(InventoryStopExpirySweeper.class);

    private final AvailabilityStopService stops;

    public InventoryStopExpirySweeper(AvailabilityStopService stops) {
        this.stops = stops;
    }

    @Scheduled(
            initialDelayString = "${horecaos.inventory.stop-expiry.initial-delay:PT1M}",
            fixedDelayString = "${horecaos.inventory.stop-expiry.interval:PT1M}")
    public void expireDueStops() {
        try {
            stops.expireDue();
        } catch (RuntimeException failure) {
            // Logged and swallowed: the next tick retries, and a dead scheduler would also
            // stop every other module's timer sharing this pool (SchedulingConfiguration).
            log.error("Availability stop expiry sweep failed", failure);
        }
    }
}
