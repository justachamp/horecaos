package uz.horecaos.platform.ordering.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import uz.horecaos.platform.fulfillment.api.DeliveryPlanner;
import uz.horecaos.platform.ordering.api.OrderConfirmed;

/**
 * Opens the delivery plan a confirmation implies (ADR 0014, ADR 0019).
 *
 * <p>{@link DeliveryPlanner} is called rather than triggered, and its own
 * documentation says why: pricing already depends on fulfilment for ADR 0037's
 * delivery fee and ordering depends on pricing, so a fulfilment listener on an
 * ordering event would close {@code fulfillment -> ordering -> pricing ->
 * fulfillment} into a cycle the architecture tests refuse. The dependency is
 * inverted instead — fulfilment publishes what it can do and the confirmation
 * path calls it — and the confirmation path is here.
 *
 * <p>A listener on ordering's own event rather than a call inside
 * {@link CheckoutService}, because confirmation happens in two places: at
 * checkout when the branch auto-accepts, and in {@link OrderStateService} when an
 * operator, the POS or an approval timeout accepts later. One listener covers
 * both, and neither has to remember to.
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}, for the reason ADR 0041's kitchen
 * opener gives: the plan and the confirmation that caused it commit together, so
 * there is no window in which the platform has committed to delivering an order
 * and nobody will ever be sent for it. Nothing external is called — planning is
 * local rows and a durable job — so this does not put a network hop inside the
 * checkout transaction.
 *
 * <p>The confirmation instant comes off the event and never from a clock here.
 * Every instant in ADR 0014's time model derives from it, so a plan opened by a
 * replay an hour later still describes the promise the customer was given.
 */
@Component
public class DeliveryPlanTrigger {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPlanTrigger.class);

    private final DeliveryPlanner planner;

    public DeliveryPlanTrigger(DeliveryPlanner planner) {
        this.planner = planner;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderConfirmed(OrderConfirmed event) {
        // A collected order, a branch nobody has placed on a map, and an order
        // with no destination all come back empty, and none of them is an error a
        // confirmation should be failed for: refusing here would turn a delivery
        // configuration problem into a checkout outage for the whole branch.
        planner.planFor(event.tenantId().value(), event.brandId(), event.locationId(),
                        event.orderId(), event.confirmedAt())
                .ifPresentOrElse(
                        planId -> log.debug("Opened delivery plan {} for order {}", planId,
                                event.orderId()),
                        () -> log.debug("Order {} has nothing to deliver; no plan was opened",
                                event.orderId()));
    }
}
