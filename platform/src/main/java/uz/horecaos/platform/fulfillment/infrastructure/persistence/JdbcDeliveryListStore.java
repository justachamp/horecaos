package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The deliveries list: one row per delivery plan, with its history (ADR 0164 slice two).
 *
 * <p>The dispatch board's queue lists only the plans that are open; this lists every plan a tenant has, newest
 * first, completed and cancelled included, joined to the order's non-PII facts, the branch and the plan's current
 * shipment. A plan can have had several shipments (an assignment that fell through, a partner that was replaced
 * by a courier); the row shows the one that stands -- the newest that was not cancelled, else the newest.
 *
 * <p>Nothing about the recipient is read: not the name, not the telephone number, not the address. The plan's
 * {@code destination_label} is the non-PII label ordering projected for the dispatch board.
 */
@Repository
public class JdbcDeliveryListStore {

    private final JdbcClient jdbc;

    public JdbcDeliveryListStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param locationIds  empty for every branch the other filters allow
     * @param statuses     plan statuses, empty for all
     * @param after        the keyset position of the last row of the previous page, or null for the first page
     * @param limit        rows wanted; one more is read so the caller can tell whether a page follows
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public List<Row> list(
            UUID tenantId,
            @Nullable UUID brandId,
            List<UUID> locationIds,
            List<String> statuses,
            @Nullable UUID courierId,
            @Nullable Instant from,
            @Nullable Instant to,
            @Nullable Position after,
            int limit) {

        StringBuilder where = new StringBuilder(" WHERE p.tenant_id = :tenantId");
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        if (brandId != null) {
            where.append(" AND p.brand_id = :brandId");
            params.put("brandId", brandId);
        }
        if (!locationIds.isEmpty()) {
            where.append(" AND p.location_id IN (:locationIds)");
            params.put("locationIds", locationIds);
        }
        if (!statuses.isEmpty()) {
            where.append(" AND p.status IN (:statuses)");
            params.put("statuses", statuses);
        }
        if (courierId != null) {
            where.append(" AND s.courier_id = :courierId");
            params.put("courierId", courierId);
        }
        if (from != null) {
            where.append(" AND p.confirmed_at >= :from");
            params.put("from", utc(from));
        }
        if (to != null) {
            where.append(" AND p.confirmed_at < :to");
            params.put("to", utc(to));
        }
        if (after != null) {
            where.append(" AND (p.confirmed_at, p.id) < (:afterAt, :afterId)");
            params.put("afterAt", utc(after.confirmedAt()));
            params.put("afterId", after.planId());
        }
        params.put("limit", limit);

        return jdbc.sql("""
                        SELECT p.id AS plan_id, p.order_id, p.brand_id, p.location_id, p.status AS plan_status,
                               p.destination_label, p.distance_meters, p.currency,
                               p.confirmed_at, p.promised_delivery_start, p.promised_delivery_end,
                               o.public_order_number, o.total_minor, o.payment_status_projection,
                               loc.display_name AS branch_name,
                               s.id AS shipment_id, s.status AS shipment_status, s.source_type,
                               s.courier_id, s.provider_type, s.picked_up_at, s.delivered_at
                          FROM fulfillment.delivery_plans p
                          JOIN ordering.orders o
                            ON o.id = p.order_id AND o.tenant_id = p.tenant_id
                          JOIN tenant.locations loc
                            ON loc.id = p.location_id AND loc.tenant_id = p.tenant_id AND loc.brand_id = p.brand_id
                          LEFT JOIN LATERAL (
                                SELECT sh.* FROM fulfillment.shipments sh
                                 WHERE sh.delivery_plan_id = p.id AND sh.tenant_id = p.tenant_id
                                 ORDER BY (sh.status <> 'CANCELLED') DESC, sh.created_at DESC
                                 LIMIT 1) s ON true
                        """ + where + """
                         ORDER BY p.confirmed_at DESC, p.id DESC
                         LIMIT :limit
                        """)
                .params(params)
                .query((rs, n) -> new Row(
                        rs.getObject("plan_id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getString("public_order_number"),
                        rs.getObject("brand_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getString("branch_name"),
                        rs.getString("plan_status"),
                        rs.getObject("shipment_id", UUID.class),
                        rs.getString("shipment_status"),
                        rs.getString("source_type"),
                        rs.getObject("courier_id", UUID.class),
                        rs.getString("provider_type"),
                        rs.getString("destination_label"),
                        (Integer) rs.getObject("distance_meters"),
                        rs.getObject("confirmed_at", OffsetDateTime.class).toInstant(),
                        instant(rs.getObject("promised_delivery_start", OffsetDateTime.class)),
                        instant(rs.getObject("promised_delivery_end", OffsetDateTime.class)),
                        instant(rs.getObject("picked_up_at", OffsetDateTime.class)),
                        instant(rs.getObject("delivered_at", OffsetDateTime.class)),
                        rs.getLong("total_minor"),
                        rs.getString("currency"),
                        isPrepaid(rs.getString("payment_status_projection"))))
                .list();
    }

    private static boolean isPrepaid(@Nullable String paymentStatus) {
        return "AUTHORIZED".equals(paymentStatus) || "CAPTURED".equals(paymentStatus);
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Where the previous page ended: the keyset of its last row. */
    public record Position(Instant confirmedAt, UUID planId) {}

    /** One delivery, as the list reads it. No recipient field exists to fill. */
    public record Row(
            UUID planId,
            UUID orderId,
            String orderReference,
            UUID brandId,
            UUID locationId,
            String branchName,
            String planStatus,
            @Nullable UUID shipmentId,
            @Nullable String shipmentStatus,
            @Nullable String sourceType,
            @Nullable UUID courierId,
            @Nullable String providerType,
            @Nullable String destinationLabel,
            @Nullable Integer distanceMeters,
            Instant confirmedAt,
            @Nullable Instant promisedDeliveryStart,
            @Nullable Instant promisedDeliveryEnd,
            @Nullable Instant pickedUpAt,
            @Nullable Instant deliveredAt,
            long orderTotalMinor,
            String currency,
            boolean prepaid) {}
}
