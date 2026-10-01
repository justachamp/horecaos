package uz.horecaos.platform.inventory.infrastructure.realtime;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.horecaos.platform.inventory.api.InventoryStopChanged;
import uz.horecaos.platform.inventory.api.ItemAvailabilityChanged;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;

/**
 * The {@code STOP_LIST} realtime channel's first producer (ADR 0045, ADR 0141): a stop made,
 * lifted or expired, and a position toggled, tells every open stop list at the affected
 * branches to re-read.
 *
 * <p>Published after commit, like {@code OrderRealtimeSignalTrigger}: a signal is an
 * invitation to re-read, and re-reading a change that then rolled back would show a state the
 * database never held. A signal carries an identifier and a version, never the stop.
 *
 * <p>The channel is subscribable at {@code LOCATION} only, so a stop that is not tied to one
 * branch ({@code BRAND}, {@code MENU}, a channel everywhere) signals each branch that lists
 * the dish as stock — the branches where it changes what is sold.
 */
@Component
public class InventoryStopRealtimeSignalTrigger {

    private final RealtimeSignalPublisher realtime;
    private final JdbcAvailabilityStopStore stops;

    public InventoryStopRealtimeSignalTrigger(RealtimeSignalPublisher realtime, JdbcAvailabilityStopStore stops) {
        this.realtime = realtime;
        this.stops = stops;
    }

    /**
     * Looks the branches up while the transaction is still open and tenant-bound --
     * {@code inventory.stock_items} enforces row-level security (ADR 0056), and a read after
     * commit runs on a connection nobody bound -- and publishes only once it has committed.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onStopChanged(InventoryStopChanged event) {
        List<UUID> locations = event.locationId() != null
                ? List.of(event.locationId())
                : stops.locationsStocking(event.tenantId(), event.brandId(), event.variantId());
        afterCommit(() -> {
            for (UUID locationId : locations) {
                signal(event.tenantId(), locationId, event.variantId(), event.occurredAt());
            }
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAvailabilityChanged(ItemAvailabilityChanged event) {
        signal(event.tenantId(), event.locationId(), event.variantId(), event.occurredAt());
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void signal(UUID tenantId, UUID locationId, UUID variantId, java.time.Instant at) {
        realtime.publish(RealtimeSignal.of(
                tenantId, StreamChannel.STOP_LIST, ScopeKey.location(locationId), "Variant", variantId, null, at));
    }
}
