package uz.horecaos.platform.fulfillment.infrastructure.sourcing;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.fulfillment.api.DeliveryCompletionPort;

/**
 * {@code fulfillment}'s implementation of its own {@link
 * DeliveryCompletionPort} (ADR 0125): closes an internal shipment out to
 * {@code DELIVERED} and hands {@code courier} — the only module that calls
 * this, via {@code courier.application.DeliveryAccrualOrderCompletionTrigger}
 * — what it needs to price the delivery, without {@code courier} ever
 * reading {@code fulfillment}'s own tables (the rule ADR 0042's package doc
 * states as central).
 *
 * <p>Written as one compare-and-set, the way every other write in this
 * package is (see {@code JdbcAssignmentStore}'s own doc on why): the {@code
 * UPDATE}'s {@code WHERE} decides, nothing here counts first.
 *
 * <p>{@code findLiveShipment}'s query also joins {@code ordering.orders} for
 * {@code payment_status_projection} — a plain SQL join, not a Java import of
 * an {@code ordering} type, so it creates no dependency edge Spring
 * Modulith's module graph can see. See {@link DeliveryCompletionPort}'s own
 * doc for why the interface lives in {@code fulfillment.api} rather than
 * {@code courier.api} despite {@code courier} being the caller.
 */
@Repository
public class JdbcDeliveryCompletionAdapter implements DeliveryCompletionPort {

    private final JdbcClient jdbc;

    public JdbcDeliveryCompletionAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Optional<InternalDelivery> closeInternalShipment(UUID tenantId, UUID orderId, Instant deliveredAt) {
        Optional<ShipmentRow> found = findLiveShipment(tenantId, orderId);
        if (found.isEmpty() || !"INTERNAL".equals(found.get().sourceType())) {
            // No shipment (pickup/dine-in), a partner-sourced one (ADR 0042
            // never prices those), or nothing live at all. One empty answer for
            // all three -- a caller has nothing different to do for any of
            // them, the same non-distinguishing answer DeliveryOrderPort
            // already gives for its own four empty cases.
            return Optional.empty();
        }

        ShipmentRow row = found.get();
        if (!"DELIVERED".equals(row.status())) {
            if (!markDelivered(tenantId, row.id(), deliveredAt)) {
                // Lost a race with another closer of the same shipment -- most
                // likely a replayed OrderCompleted, an ordinary at-least-once
                // event. Re-read rather than trust the value raced against.
                found = findLiveShipment(tenantId, orderId);
                if (found.isEmpty() || !"DELIVERED".equals(found.get().status())) {
                    // Cancelled out from under us between the two reads.
                    return Optional.empty();
                }
                row = found.get();
            }
        }

        ShipmentRow closed = row;
        return findAcceptedAttempt(tenantId, closed.id())
                .map(attempt -> new InternalDelivery(
                        closed.brandId(),
                        closed.locationId(),
                        Objects.requireNonNull(closed.courierId(), "An INTERNAL shipment always names its courier"),
                        closed.id(),
                        attempt.id(),
                        attempt.acceptedAt(),
                        closed.distanceMeters() == null ? 0 : closed.distanceMeters(),
                        closed.distanceSource(),
                        closed.promisedDeliveryEnd(),
                        closed.pickupWindowEnd(),
                        closed.prepaid()));
    }

    /** The order's one live (non-cancelled) shipment, if any — {@code ux_shipment_one_active_per_plan} guarantees at most one. */
    private Optional<ShipmentRow> findLiveShipment(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT s.id, s.status, s.source_type, s.courier_id, s.brand_id, s.location_id,
                       p.distance_meters, p.distance_source, p.promised_delivery_end, p.pickup_window_end,
                       o.payment_status_projection
                  FROM fulfillment.shipments s
                  JOIN fulfillment.delivery_plans p
                    ON p.id = s.delivery_plan_id AND p.tenant_id = s.tenant_id
                  JOIN ordering.orders o
                    ON o.id = s.order_id AND o.tenant_id = s.tenant_id
                 WHERE s.tenant_id = :tenantId AND s.order_id = :orderId AND s.status <> 'CANCELLED'
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((ResultSet row, int number) -> new ShipmentRow(
                        Objects.requireNonNull(row.getObject("id", UUID.class)),
                        Objects.requireNonNull(row.getString("status")),
                        Objects.requireNonNull(row.getString("source_type")),
                        row.getObject("courier_id", UUID.class),
                        Objects.requireNonNull(row.getObject("brand_id", UUID.class)),
                        Objects.requireNonNull(row.getObject("location_id", UUID.class)),
                        (Integer) row.getObject("distance_meters"),
                        row.getString("distance_source"),
                        instant(row, "promised_delivery_end"),
                        instant(row, "pickup_window_end"),
                        isPrepaid(row.getString("payment_status_projection"))))
                .optional();
    }

    private static boolean isPrepaid(@Nullable String paymentStatusProjection) {
        return "AUTHORIZED".equals(paymentStatusProjection) || "CAPTURED".equals(paymentStatusProjection);
    }

    private boolean markDelivered(UUID tenantId, UUID shipmentId, Instant deliveredAt) {
        return jdbc.sql("""
                UPDATE fulfillment.shipments
                   SET status = 'DELIVERED', delivered_at = :deliveredAt, version = version + 1
                 WHERE tenant_id = :tenantId AND id = :shipmentId
                   AND status <> 'DELIVERED' AND status <> 'CANCELLED'
                """)
                        .param("tenantId", tenantId)
                        .param("shipmentId", shipmentId)
                        .param("deliveredAt", utc(deliveredAt))
                        .update()
                == 1;
    }

    /** The attempt that won this shipment — {@code ux_attempt_one_accepted} guarantees at most one. */
    private Optional<AttemptRow> findAcceptedAttempt(UUID tenantId, UUID shipmentId) {
        return jdbc.sql("""
                SELECT id, accepted_at
                  FROM fulfillment.assignment_attempts
                 WHERE tenant_id = :tenantId AND shipment_id = :shipmentId AND status = 'ACCEPTED'
                """)
                .param("tenantId", tenantId)
                .param("shipmentId", shipmentId)
                .query((ResultSet row, int number) -> new AttemptRow(
                        Objects.requireNonNull(row.getObject("id", UUID.class)),
                        Objects.requireNonNull(instant(row, "accepted_at"))))
                .optional();
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws java.sql.SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record ShipmentRow(
            UUID id,
            String status,
            String sourceType,
            @Nullable UUID courierId,
            UUID brandId,
            UUID locationId,
            @Nullable Integer distanceMeters,
            @Nullable String distanceSource,
            @Nullable Instant promisedDeliveryEnd,
            @Nullable Instant pickupWindowEnd,
            boolean prepaid) {}

    private record AttemptRow(UUID id, Instant acceptedAt) {}
}
