package uz.horecaos.platform.fulfillment.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.audit.api.ActorRef;

/**
 * How an order cancellation reaches its delivery plan (ADR 0014, gap map row
 * 1.2g).
 *
 * <p>Called rather than listened for, the same direction {@link
 * DeliveryPlanner} already takes and for the same architectural reason its own
 * doc gives: fulfilment cannot listen for an ordering event without importing
 * ordering, and pricing already depends on fulfilment for ADR 0037's delivery
 * fee while ordering depends on pricing — a fulfilment listener on {@code
 * OrderCancelled} would close {@code fulfillment -> ordering -> pricing ->
 * fulfillment} into a cycle {@code ModularArchitectureTests} refuses.
 *
 * <p><b>Call this only after the order's own cancellation has committed</b>,
 * never from inside that transaction. A {@code PARTNER} shipment's cancel is a
 * network call to a courier partner, and the production implementation makes
 * that call with no pooled database connection held across it — see {@code
 * fulfillment.application.ShipmentCancellationService}'s own doc.
 */
public interface ShipmentCancellationPort {

    /**
     * Cascades an already-cancelled order onto its open delivery plan, if it
     * has one — never throws for an order with nothing to cancel, which is the
     * ordinary case for a pickup or dine-in order and for one whose delivery
     * never got as far as a courier.
     */
    Outcome cancelForOrder(
            UUID tenantId, UUID brandId, UUID locationId, UUID orderId, String reasonCode, ActorRef actor);

    /** What {@link #cancelForOrder} actually did. */
    enum Result {

        /** No open delivery plan existed for this order. */
        NOTHING_TO_CANCEL,

        /** A plan with no shipment yet was moved straight to {@code CANCELLED}. */
        PLAN_CANCELLED,

        /** An in-house courier's shipment was cancelled locally. Notifying the courier is ADR 0042's own concern. */
        INTERNAL_CANCELLED,

        /** The partner confirmed a free cancellation. */
        PROVIDER_CANCELLED,

        /** The partner confirmed the cancellation, but it is chargeable. */
        PROVIDER_CANCELLED_CHARGEABLE,

        /** The partner's answer could not be confirmed. A human must reconcile before anything else touches this plan. */
        PROVIDER_UNCERTAIN,

        /** The partner refused, or could not be reached at all. A human owns this shipment now. */
        PROVIDER_FAILED
    }

    /** @param providerType present only when {@code result} came from asking an actual partner */
    record Outcome(Result result, @Nullable String providerType) {}
}
