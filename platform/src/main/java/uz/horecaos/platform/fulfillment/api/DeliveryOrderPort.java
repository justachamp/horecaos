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
     * One order's delivery-relevant facts, decrypted once for dispatch.
     *
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
     * @param destinationLabel row 3.1: a non-PII projection of the same
     *                         destination — district/zone and street, never a
     *                         house number, a flat, or a phone — computed by
     *                         ordering (the module that owns {@code
     *                         DeliveryDestination}) so the dispatch board can
     *                         show roughly where an order is going without
     *                         decrypting anything on its 10-second poll. Null
     *                         when the destination carries neither a zone nor
     *                         a street to show
     * @param dispatchFacts    ADR 0142: what the order's channel and delivery zone are,
     *                         the two facts a dispatch rule asks that the fulfilment module
     *                         cannot read for itself. Null when the adapter cannot say, which
     *                         a rule naming a source, channel or zone then does not match
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
            Waypoint dropoff,
            @Nullable String destinationLabel,
            @Nullable DispatchOrderFacts dispatchFacts) {

        /** An order whose channel and zone are unknown: the shape every caller built before dispatch rules. */
        public DeliveryOrder(
                UUID orderId,
                String orderReference,
                Duration preparation,
                long deliveryFeeMinor,
                @Nullable UUID deliveryFeeResolutionId,
                String currency,
                boolean prepaid,
                long itemValueMinor,
                Waypoint dropoff,
                @Nullable String destinationLabel) {
            this(
                    orderId,
                    orderReference,
                    preparation,
                    deliveryFeeMinor,
                    deliveryFeeResolutionId,
                    currency,
                    prepaid,
                    itemValueMinor,
                    dropoff,
                    destinationLabel,
                    null);
        }

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

    /**
     * The two order facts a dispatch rule asks that fulfilment cannot read for itself, without
     * decrypting anything (ADR 0142).
     *
     * <p>Beside {@link #deliveryOrder} rather than inside it for the simulator's sake: re-reading
     * "which rule would this recent plan's order match" must not reveal a customer's address, and
     * every fact here is in clear on the order and on the fee-resolution evidence. Defaulted to
     * empty so a test double that predates dispatch rules need grow no implementation.
     *
     * @return empty when the order is not this tenant's or the adapter cannot say
     */
    default Optional<DispatchOrderFacts> dispatchFacts(UUID tenantId, UUID orderId) {
        return Optional.empty();
    }

    /**
     * An order's sales channel and delivery zone.
     *
     * @param channelId         the order's sales channel
     * @param channelSystemType {@code tenant.sales_channels.system_type}, ADR 0036's closed set
     * @param zoneId            the delivery zone the order was priced in, from its fee-resolution
     *                          evidence; null for an order with none (a pickup, an externally
     *                          priced aggregator order, a snapshot that predates the evidence)
     */
    record DispatchOrderFacts(
            UUID channelId,
            String channelSystemType,
            @Nullable UUID zoneId,
            boolean prepaid) {

        public DispatchOrderFacts {
            Objects.requireNonNull(channelId, "A channel is required");
            Objects.requireNonNull(channelSystemType, "A channel system type is required");
        }
    }

    /**
     * Where the customer is, and nothing else about them, decrypted for one stated purpose
     * (ADR 0029, courier policy {@code revealCustomerLocationTiming}).
     *
     * <p>Narrower than {@link #deliveryOrder} on purpose. That read assembles a name, a
     * telephone number and a comment for a partner's booking; a courier's own screen needs the
     * door and the way in, and a courier policy that decides <em>when</em> a courier may see
     * where the customer lives must not hand them the customer's name and number in the same
     * breath. The caller decides whether this read is allowed, and records the audit fact that
     * names who asked and for which order; this answers the decrypt, and states the purpose in the
     * reveal call so the reason travels with the read.
     *
     * <p>Not filtered on the order's status: an order that has since been cancelled still has a
     * door, and refusing the read here would make a courier already standing at it unable to
     * find out where. Whether they may still ask is the caller's rule, taken from the shipment
     * they hold.
     *
     * @param purpose why the address is being read, stated in the reveal call
     * @return empty when the order is not this tenant's, is not a delivery, or holds no address
     */
    default Optional<CustomerLocation> customerLocation(UUID tenantId, UUID orderId, String purpose) {
        return Optional.empty();
    }

    /**
     * The door a courier is sent to. Personal data throughout, so it prints as nothing.
     *
     * @param instructions the customer's own words about how to reach them ("ring twice",
     *                     "the gate code is on the intercom"), or null when they left none
     */
    record CustomerLocation(
            double latitude,
            double longitude,
            String addressLine,
            @Nullable String entrance,
            @Nullable String floor,
            @Nullable String apartment,
            @Nullable String instructions) {

        @Override
        public String toString() {
            return "CustomerLocation[REDACTED]";
        }
    }

    /** Whether a real implementation is present. */
    default boolean isWired() {
        return true;
    }

    /** The reason no plan exists for a delivery order while this port is unwired. */
    String NOT_WIRED_REASON = "DELIVERY_ORDER_NOT_WIRED";
}
