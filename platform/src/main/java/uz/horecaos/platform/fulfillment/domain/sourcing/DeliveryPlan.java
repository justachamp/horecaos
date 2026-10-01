package uz.horecaos.platform.fulfillment.domain.sourcing;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The durable sourcing effort for one order (ADR 0014).
 *
 * <p>Separate from the shipment, which ADR 0014 refuses to merge with it by name:
 * planning, quoting and sourcing all happen before anything physical exists, and
 * one plan may produce several assignment attempts.
 *
 * @param deliveryFeeResolutionId the ADR 0037 evidence row the fee was priced
 *                              against, or null for an order whose snapshot
 *                              predates one — see {@code DeliveryOrderPort.DeliveryOrder}
 * @param promisedDeliveryStart null until a promise is made. Nullable in pairs —
 *                              a promise with one end is a window nobody can be
 *                              held to, and {@code ck_plan_promise_pair} refuses it
 * @param distanceMeters        branch to door. Snapshotted with its source so a
 *                              decision made on a road distance is not later
 *                              re-read as one made on a radius
 * @param destinationLabel      row 3.1: ordering's non-PII projection of the
 *                              destination — district/zone and street, never a
 *                              house number, a flat, or a phone — snapshotted
 *                              here at plan creation (V0410) so the dispatch
 *                              board's card never decrypts anything on its
 *                              10-second poll. Null when {@code
 *                              DeliveryDestination#maskedLabel} had nothing to
 *                              show
 * @param dispatchPolicyId      ADR 0142: the pinned {@code fulfillment.dispatch_rules}
 *                              document the plan was evaluated under, a pair with
 *                              {@code dispatchPolicyVersion}; null when none was published
 *                              and the built-in default applied. {@code policyId} stays the
 *                              identity of the {@code fulfillment.sourcing} timing document
 * @param dispatch              what the rules decided when the plan was created -- the rule,
 *                              the mode, the partner set, the start -- applied by every tick
 *                              and never recomputed, so an edit cannot reroute an order in flight
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
        @Nullable UUID deliveryFeeResolutionId,
        PickupPlan pickup,
        @Nullable Instant promisedDeliveryStart,
        @Nullable Instant promisedDeliveryEnd,
        Integer distanceMeters,
        String distanceSource,
        UUID policyId,
        Integer policyVersion,
        int version,
        @Nullable String destinationLabel,
        @Nullable UUID dispatchPolicyId,
        @Nullable Integer dispatchPolicyVersion,
        DispatchDecision dispatch) {

    public static final String STANDARD = "STANDARD";

    /**
     * A plan created before dispatch rules existed, or by a caller that knows nothing about them:
     * sourced under the built-in default decision, with no dispatch document pinned.
     */
    public DeliveryPlan(
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
            @Nullable UUID deliveryFeeResolutionId,
            PickupPlan pickup,
            @Nullable Instant promisedDeliveryStart,
            @Nullable Instant promisedDeliveryEnd,
            Integer distanceMeters,
            String distanceSource,
            UUID policyId,
            Integer policyVersion,
            int version,
            @Nullable String destinationLabel) {
        this(
                id,
                tenantId,
                brandId,
                locationId,
                orderId,
                status,
                mode,
                serviceLevel,
                customerDeliveryFeeMinor,
                currency,
                deliveryFeeResolutionId,
                pickup,
                promisedDeliveryStart,
                promisedDeliveryEnd,
                distanceMeters,
                distanceSource,
                policyId,
                policyVersion,
                version,
                destinationLabel,
                null,
                null,
                DispatchDecision.builtInDefault());
    }

    public DeliveryPlan {
        Objects.requireNonNull(id, "A plan id is required");
        Objects.requireNonNull(tenantId, "A tenant is required");
        Objects.requireNonNull(orderId, "An order is required");
        Objects.requireNonNull(pickup, "A pickup plan is required");
        Objects.requireNonNull(status, "A status is required");
        Objects.requireNonNull(mode, "A sourcing mode is required");
        Objects.requireNonNull(dispatch, "A dispatch decision is required");
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
