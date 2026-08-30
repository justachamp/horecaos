package uz.qoida.platform.fulfillment.domain.sourcing;

import java.util.Objects;
import java.util.UUID;

import uz.qoida.platform.fulfillment.api.ShipmentBookingPort.Waypoint;

/**
 * One order that needs a courier (ADR 0014).
 *
 * <p>Everything sourcing needs and nothing it does not, in the same spirit as
 * {@code DeliveryFeeQuery}: no basket, no customer, no order state machine. What
 * a courier decision turns on is where the food is, where it is going, when it
 * will be ready, and whether the money has already been taken.
 *
 * @param planId          identifies this sourcing effort. Durably it is
 *                        {@code fulfillment.delivery_plans.id}; until that table
 *                        exists the caller supplies a stable id of its own,
 *                        because it is what every partner idempotency key for
 *                        this order is derived from
 * @param distanceMeters  branch to destination. ADR 0042's dispatch gate uses it
 *                        to exclude a courier whose vehicle class puts this
 *                        order outside their band, so a courier on foot is not
 *                        offered an eleven-kilometre drop
 * @param prepaid         whether Qoida already took payment. False instructs a
 *                        partner to collect from the recipient, so a wrong value
 *                        here charges the customer twice — asserted in
 *                        {@code NoorDeliveryAdapterTests} rather than trusted
 * @param itemValueMinor  integer minor units, whole som for UZS. The goods value
 *                        being carried, never the delivery fee
 */
public record SourcingRequest(
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID orderId,
        UUID planId,
        String orderReference,
        PickupPlan plan,
        SourcingMode mode,
        int distanceMeters,
        Waypoint pickup,
        Waypoint dropoff,
        boolean prepaid,
        long itemValueMinor,
        String currency,
        String correlationId) {

    public SourcingRequest {
        Objects.requireNonNull(tenantId, "A tenant is required");
        Objects.requireNonNull(brandId, "A brand is required");
        Objects.requireNonNull(locationId, "A location is required");
        Objects.requireNonNull(planId, "A plan id is required");
        Objects.requireNonNull(orderReference, "An order reference is required");
        Objects.requireNonNull(plan, "A pickup plan is required");
        Objects.requireNonNull(mode, "A sourcing mode is required");
        Objects.requireNonNull(pickup, "A pickup waypoint is required");
        Objects.requireNonNull(dropoff, "A dropoff waypoint is required");
        Objects.requireNonNull(currency, "A currency is required");
        if (distanceMeters < 0) {
            throw new IllegalArgumentException(
                    "A delivery distance cannot be negative, was " + distanceMeters);
        }
    }

    /** Names the order and nothing about the people at either end of it. */
    @Override
    public String toString() {
        return "SourcingRequest[plan=%s, order=%s, mode=%s, distanceMeters=%d]"
                .formatted(planId, orderReference, mode, distanceMeters);
    }
}
