package uz.horecaos.platform.notifications.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.inventory.api.ItemAvailabilityChanged;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcInventoryStopDigestStore;

/**
 * Inventory's operations Telegram trigger (ADR 0058, gap map row 2.5c): a
 * dish 86'd, or restored, so the whole staff group knows the stop list
 * changed.
 *
 * <p>Same placement as {@link OrderNotificationTrigger} — {@code inventory}
 * does not depend on {@code notifications}, so a listener here importing
 * {@link ItemAvailabilityChanged} from {@code inventory.api} is a clean
 * one-way edge (contrast {@code payments.notifications.PaymentOperationsAlertTrigger},
 * which a cycle forced out of this package).
 *
 * <p><b>Enqueues rather than fans out directly.</b> Before wave P16 this
 * class called {@code OperationsAlertPort.fanOut} once per event, only on the
 * available -> false edge, which is how a branch stopping fifteen items in
 * one rush got fifteen separate Telegram messages and a return to sale was
 * never announced at all. Every toggle, either direction, is now appended to
 * {@link JdbcInventoryStopDigestStore} instead — still inside this same
 * {@link TransactionPhase#BEFORE_COMMIT} listener, so the queue entry commits
 * with the toggle it describes exactly as the old direct alert did.
 * {@code InventoryStopDigestSweeper} is what actually raises the grouped
 * alert, on its own periodic tick, once per location per window.
 */
@Component
public class InventoryOperationsAlertTrigger {

    /** The semantic template key a tenant authors this alert's wording against. */
    public static final String ITEM_86D = "ITEM_86D";

    static final String SUBJECT_TYPE = "Variant";

    private final JdbcInventoryStopDigestStore digestQueue;

    public InventoryOperationsAlertTrigger(JdbcInventoryStopDigestStore digestQueue) {
        this.digestQueue = digestQueue;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onAvailabilityChanged(ItemAvailabilityChanged event) {
        digestQueue.enqueue(
                event.tenantId(),
                event.brandId(),
                event.locationId(),
                event.variantId(),
                event.available(),
                event.reasonCode(),
                event.eventId(),
                event.occurredAt());
    }
}
