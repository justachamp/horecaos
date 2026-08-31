package uz.horecaos.platform.fulfillment.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.Waypoint;

/**
 * The one thing sourcing asks of ADR 0019 before it sends anybody to a door
 * (ADR 0014, ADR 0029).
 *
 * <p>Fulfilment can read the branch end of a journey for itself — a restaurant's
 * address, landmark and phone are published by the merchant and sit in clear on
 * {@code tenant.locations}, exactly as V0023's comment argues they should. The
 * customer end is the opposite: name, phone, address and access notes are inside
 * ADR 0029's envelope encryption, bound to the order row by the AAD, and reaching
 * them is a decrypt with a recorded purpose. Ordering owns that decrypt and this
 * interface is where it is asked for, once, for the purpose of dispatching a
 * courier.
 *
 * <p>Declared here rather than fulfilment reaching into ordering's application
 * layer because ADR 0019's command path is module-internal, and because the
 * direction of the dependency is what keeps the decrypt on ordering's side of the
 * line. It is the mirror of {@link OrderProgressPort}, which is how the kitchen
 * proposes an order transition it may not make itself.
 *
 * <p>Until ordering supplies an implementation, {@code UnwiredDeliveryOrderPort}
 * stands in behind {@code @ConditionalOnMissingBean} and answers empty. That is
 * the correct direction to fail: no plan is created, no courier is sent to an
 * address nobody decrypted, and the warning names the gap.
 */
public interface DeliveryOrderPort {

    /**
     * Everything needed to plan and source one delivery order.
     *
     * @return empty when the order is not this tenant's, is not a delivery, or is
     *         not in a state that should be sourced. All three are the same answer
     *         to a caller — there is nothing to plan — and telling them apart
     *         would leak one tenant's order ids to another
     */
    Optional<DeliveryOrder> deliveryOrder(UUID tenantId, UUID orderId);

    /**
     * @param orderReference   the public order number. The only order identifier a
     *                         partner is ever given, because it is the one already
     *                         shouted across a counter
     * @param preparation      the kitchen's estimate. The whole time model derives
     *                         from it, so a wrong value here is a courier waiting
     *                         unpaid or food under a lamp
     * @param deliveryFeeMinor integer minor units — whole som for UZS — snapshotted
     *                         at checkout. Carried so the plan can answer "what did
     *                         the customer pay for delivery" without re-running ADR
     *                         0037 against today's zones, and never raised because a
     *                         partner cost more
     * @param deliveryFeeResolutionId the ADR 0037 evidence row this fee was priced
     *                         against, or null for an order whose snapshot predates
     *                         one
     * @param prepaid          whether HorecaOS already took the money. False instructs
     *                         a partner to collect from the recipient, so a wrong
     *                         value charges the customer twice
     * @param itemValueMinor   the goods value the courier is carrying, never the
     *                         delivery fee
     * @param dropoff          the decrypted customer end. Personal data throughout,
     *                         which is why {@link Waypoint} prints as nothing
     */
    record DeliveryOrder(
            UUID orderId,
            String orderReference,
            Duration preparation,
            long deliveryFeeMinor,
            @Nullable UUID deliveryFeeResolutionId,
            String currency,
            boolean prepaid,
            long itemValueMinor,
            Waypoint dropoff) {

        public DeliveryOrder {
            Objects.requireNonNull(orderId, "An order id is required");
            Objects.requireNonNull(orderReference, "An order reference is required");
            Objects.requireNonNull(preparation, "A preparation estimate is required");
            Objects.requireNonNull(currency, "A currency is required");
            Objects.requireNonNull(dropoff, "A dropoff waypoint is required");
            if (preparation.isNegative()) {
                throw new IllegalArgumentException("A preparation estimate cannot be negative");
            }
            if (deliveryFeeMinor < 0 || itemValueMinor < 0) {
                throw new IllegalArgumentException("Order money cannot be negative");
            }
        }

        /** Names the order and nothing about the person waiting for it. */
        @Override
        public String toString() {
            return "DeliveryOrder[order=%s, reference=%s, preparation=%s]"
                    .formatted(orderId, orderReference, preparation);
        }
    }

    /** Whether a real implementation is present. */
    default boolean isWired() {
        return true;
    }

    /** The reason no plan exists for a delivery order while this port is unwired. */
    String NOT_WIRED_REASON = "DELIVERY_ORDER_NOT_WIRED";
}
