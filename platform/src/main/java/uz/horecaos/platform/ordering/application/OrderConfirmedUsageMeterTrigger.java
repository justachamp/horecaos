package uz.horecaos.platform.ordering.application;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.UsageMeter;
import uz.horecaos.platform.commercial.api.UsageMovement;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.ordering.api.OrderingEvent;

/**
 * The caller {@link UsageMeter} was missing for {@code orders.monthly_included}
 * (ADR 0021): "the canonical overage line", per that key's own doc comment, and
 * until this class nothing fed it.
 *
 * <p>Metered on {@link OrderConfirmed}, not on checkout and not on {@code
 * OrderCompleted}. A cart or an awaiting-approval order may never become a real
 * commitment, so counting one before confirmation would meter something that
 * might not happen; waiting for completion would instead miss an order a
 * customer paid for and the kitchen fired, but that was later cancelled or
 * rejected downstream of confirmation — {@code OrderConfirmed} is, in the same
 * event's own words, "the commercial commitment", which is exactly what a
 * plan's monthly order allowance is sold against.
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}, the same phase {@code
 * OrderCompletionAccrualTrigger} (loyalty) and {@code PaymentCaptureConfirmationTrigger}
 * (this module) already use for a fact {@code OrderStateService} publishes: the
 * order and the usage fact that it was confirmed commit together, so a crash
 * between them cannot leave a billable order the ledger never heard about.
 *
 * <p>Idempotent the same way every {@link UsageMeter} caller is: the order's own
 * id is the source event id, and {@code OrderStateService}'s own conditional
 * UPDATE means {@code OrderConfirmed} is published once per order, but even a
 * redelivered or duplicated publish collapses to the same row rather than a
 * second count.
 */
@Component
public class OrderConfirmedUsageMeterTrigger {

    private final UsageMeter usage;

    public OrderConfirmedUsageMeterTrigger(UsageMeter usage) {
        this.usage = usage;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderingEvent(OrderingEvent event) {
        if (event instanceof OrderConfirmed confirmed) {
            usage.record(new UsageMovement(
                    confirmed.tenantId().value(),
                    EntitlementKeys.ORDERS_MONTHLY_INCLUDED,
                    1,
                    "ordering.OrderConfirmed",
                    confirmed.orderId().toString(),
                    confirmed.confirmedAt(),
                    Map.of("location_id", confirmed.locationId().toString())));
        }
    }
}
