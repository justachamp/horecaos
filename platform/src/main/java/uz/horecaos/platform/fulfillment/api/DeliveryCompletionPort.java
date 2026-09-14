package uz.horecaos.platform.fulfillment.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one question {@code courier} asks {@code fulfillment} when an order
 * finishes (ADR 0125, ADR 0014, ADR 0042).
 *
 * <p>{@code fulfillment} owns the shipment, the delivery plan and the
 * assignment attempt; {@code courier} owns what a delivery is worth. ADR
 * 0042's package doc states the two "must never read each other" — the
 * mechanism that keeps a courier's earnings from ever being derived from the
 * customer's delivery charge.
 *
 * <p><strong>Declared here rather than in {@code courier.api}, unlike the
 * "consumer declares the interface" shape {@link InternalFleetPort}
 * uses.</strong> {@code courier} is the caller (its own {@code
 * DeliveryAccrualOrderCompletionTrigger} invokes {@link
 * #closeInternalShipment}), so the consumer-declares convention would put
 * this in {@code courier.api} with {@code fulfillment} implementing it. But
 * {@code courier} already depends on {@code ordering.api} (for {@code
 * OrderCompleted}, the event that drives this trigger) and {@code ordering}
 * already depends on {@code fulfillment.api} ({@code JdbcDeliveryOrderPort}
 * implements {@code DeliveryOrderPort}) — so a {@code fulfillment ->
 * courier} edge on top of those two would close the triangle {@code
 * courier -> ordering -> fulfillment -> courier}, exactly the cycle {@code
 * ModularArchitectureTests.verifiesModuleBoundaries} refuses to allow.
 * Declaring the interface here instead, with {@code courier} depending on
 * it, reuses the direction {@code courier.infrastructure.dispatch.InternalFleetAdapter}
 * already establishes for {@link InternalFleetPort} — {@code courier}
 * depending on {@code fulfillment.api} is an existing, safe edge with
 * nothing depending back on {@code courier}.
 *
 * <p>{@code closeInternalShipment} both answers the question and closes the
 * loop: it is also where {@code fulfillment.shipments} finally learns a
 * delivery happened, because nothing else does — see the class doc on
 * {@code courier.application.DeliveryAccrualOrderCompletionTrigger} for why
 * order completion is where "delivered" is known today.
 *
 * <p>{@code fulfillment.infrastructure.sourcing.JdbcDeliveryCompletionAdapter}
 * implements this, within {@code fulfillment} itself.
 */
public interface DeliveryCompletionPort {

    /**
     * Closes out the order's live, non-cancelled {@code INTERNAL} shipment —
     * a compare-and-set to {@code DELIVERED} — and returns what
     * {@code CourierAccrualService.recordDelivery} needs to price it.
     *
     * <p>Idempotent: a shipment already {@code DELIVERED} is answered from its
     * stored facts rather than re-closed, so a replayed {@code OrderCompleted}
     * (an at-least-once event, like every other) never re-triggers the write
     * half — {@code recordDelivery} itself would refuse the second accrual in
     * any case (ADR 0042's own unique constraint on the assignment attempt),
     * but the shipment update is a separate statement and needs its own
     * idempotence.
     *
     * <p>Empty, and deliberately not distinguished by reason, for every case
     * that is not "an internal courier is owed for this": no shipment at all
     * (a pickup or dine-in order), a {@code PARTNER}-sourced shipment (ADR
     * 0042 never prices those — {@code delivery_cost_lines} does), or one
     * already {@code CANCELLED}. A caller that got an empty answer has nothing
     * different to do for any of them, the same non-distinguishing answer
     * {@code DeliveryOrderPort} already gives for its own four empty cases.
     *
     * @param deliveredAt the instant the order finished — {@code
     *                     OrderCompleted.completedAt()} in production, so the
     *                     shipment's {@code delivered_at} and the accrual's
     *                     {@code deliveredAt} are the same instant rather than
     *                     two clock reads a few milliseconds apart
     */
    Optional<InternalDelivery> closeInternalShipment(UUID tenantId, UUID orderId, Instant deliveredAt);

    /**
     * What {@code fulfillment} knows about one internal delivery, at the
     * moment it closed.
     *
     * @param acceptedAt      when the courier accepted the offer (ADR 0014) —
     *                        the instant {@code CourierAccrualService}
     *                        resolves the rate card at, snapshotted here
     *                        because resolving at delivery would let a card
     *                        activated mid-trip change what was agreed before
     *                        it
     * @param distanceMeters  the delivery plan's branch-to-destination
     *                        distance
     * @param distanceSourceName the plan's own {@code distance_source} text —
     *                        {@code fulfillment}'s own vocabulary
     *                        ({@code RADIUS}, {@code ROAD},
     *                        {@code RADIUS_FALLBACK}, ADR 0037), not {@code
     *                        courier}'s {@code DistanceSource}; the caller
     *                        maps it rather than this port pretending the two
     *                        modules share one enum
     * @param promisedDeliveryEnd null when the plan recorded no promise
     * @param pickupWindowEnd     when the plan said the order would be ready
     * @param prepaid         whether the order's payment status is {@code
     *                        AUTHORIZED} or {@code CAPTURED} — HorecaOS
     *                        already holds the money, so a courier collects
     *                        nothing at the door. Read from {@code
     *                        ordering.orders.payment_status_projection} — a
     *                        plain non-PII status column, the same one
     *                        {@code JdbcDeliveryOrderPort} already reads for
     *                        its own {@code prepaid} flag — by a plain SQL
     *                        join inside the adapter rather than a second
     *                        port: a port {@code ordering} implemented back
     *                        against {@code courier.api} would make {@code
     *                        courier} and {@code ordering} depend on each
     *                        other, which Spring Modulith refuses as a
     *                        module cycle. Carrying the one boolean here
     *                        keeps the dependency one-directional
     */
    record InternalDelivery(
            UUID brandId,
            UUID locationId,
            UUID courierId,
            UUID shipmentId,
            UUID assignmentAttemptId,
            Instant acceptedAt,
            int distanceMeters,
            @Nullable String distanceSourceName,
            @Nullable Instant promisedDeliveryEnd,
            @Nullable Instant pickupWindowEnd,
            boolean prepaid) {}
}
