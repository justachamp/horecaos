package uz.horecaos.platform.fulfillment.domain.sourcing;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The durable sourcing effort for one order (ADR 0014).
 *
 * <p>Separate from the shipment, which ADR 0014 refuses to merge with it by name:
 * planning, quoting and sourcing all happen before anything physical exists, and
 * one plan may produce several assignment attempts.
 *
 * @param promisedDeliveryStart null until a promise is made. Nullable in pairs —
 *                              a promise with one end is a window nobody can be
 *                              held to, and {@code ck_plan_promise_pair} refuses it
 * @param distanceMeters        branch to door. Snapshotted with its source so a
 *                              decision made on a road distance is not later
 *                              re-read as one made on a radius
 */
public record DeliveryPlan(
        UUID id,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID orderId,
        PlanStatus status,
        SourcingMode mode,
        String serviceLevel,
        long customerDeliveryFeeMinor,
        String currency,
        UUID deliveryFeeResolutionId,
        PickupPlan pickup,
        Instant promisedDeliveryStart,
        Instant promisedDeliveryEnd,
        Integer distanceMeters,
        String distanceSource,
        UUID policyId,
        Integer policyVersion,
        int version) {

    public static final String STANDARD = "STANDARD";

    public DeliveryPlan {
        Objects.requireNonNull(id, "A plan id is required");
        Objects.requireNonNull(tenantId, "A tenant is required");
        Objects.requireNonNull(orderId, "An order is required");
        Objects.requireNonNull(pickup, "A pickup plan is required");
        Objects.requireNonNull(status, "A status is required");
        Objects.requireNonNull(mode, "A sourcing mode is required");
        if (customerDeliveryFeeMinor < 0) {
            throw new IllegalArgumentException("A delivery fee cannot be negative");
        }
    }

    /** Names the order and nothing about the people at either end of it. */
    @Override
    public String toString() {
        return "DeliveryPlan[id=%s, order=%s, status=%s, mode=%s, sourceAt=%s]"
                .formatted(id, orderId, status, mode, pickup.sourceAt());
    }
}
