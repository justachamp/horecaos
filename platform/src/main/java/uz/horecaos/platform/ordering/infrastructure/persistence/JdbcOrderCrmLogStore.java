package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Wave 9 w4-reports-distance-crm (7.2a): the CRM half of the console order
 * log («Заказы») — customer, operator and courier — read straight off
 * {@code ordering.orders} and its own module's tables, never {@code
 * reporting.fact_order}.
 *
 * <p>Row 7.2a's own gap: the reporting module carries no PERSONAL field at
 * all (ADR 0029) and {@code SubjectPseudonym} exists specifically to stop a
 * reporting fact ever being re-linked to a customer, so a screen that wants
 * a name and a phone beside the money figures cannot get them from {@code
 * GET .../reporting/orders} by design. This store answers the other half:
 * the console joins the two reads by {@code orderId} in the browser, never
 * in SQL, and never inside the reporting module.
 *
 * <p>Every join here stays inside {@code ordering} and {@code fulfillment} —
 * the same two schemas {@code OperationsOrderController} and {@code
 * ShipmentCancellationPort} already read from this module today. Nothing
 * here names {@code reporting.*}.
 *
 * <p>The courier column is {@code display_reference} ("K-014"), never the
 * courier's own decrypted name — {@code fulfillment.couriers}' own migration
 * comment names that column as exactly what "a dispatch board, an event
 * payload and a log line" should carry (ADR 0029). A shipment is resolved
 * with a scalar subquery, ordered by {@code assigned_at} descending, rather
 * than a {@code LEFT JOIN}: an order can carry more than one {@code
 * fulfillment.shipments} row over its life (a courier cancels, a second is
 * dispatched) and nothing at the database enforces at most one per order, so
 * a join would fan the row out and double-count the order underneath it —
 * the same risk {@code JdbcReportingStore}'s own {@code
 * delivery_distance_meters} subquery was written to avoid.
 */
@Repository
public class JdbcOrderCrmLogStore {

    private final JdbcClient jdbc;

    public JdbcOrderCrmLogStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A page of the log, newest first.
     *
     * @param locationIds  narrows to these branches; empty means every branch
     *                     the caller's own tenant-wide grant already covers
     * @param cursor       the previous page's last row, or null to start
     * @param limit        the page size, already clamped by the caller
     */
    public List<CrmLogRow> list(
            UUID tenantId, Instant from, Instant to, List<UUID> locationIds, int limit, @Nullable LogCursor cursor) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("from", utc(from));
        params.put("to", utc(to));
        params.put("limit", limit);

        StringBuilder filter = new StringBuilder();
        if (!locationIds.isEmpty()) {
            filter.append(" AND o.location_id IN (:locations)");
            params.put("locations", locationIds);
        }
        if (cursor != null) {
            filter.append(" AND (o.created_at, o.id) < (:afterOccurredAt, :afterOrderId)");
            params.put("afterOccurredAt", utc(cursor.occurredAt()));
            params.put("afterOrderId", cursor.orderId());
        }

        return jdbc.sql("""
                SELECT o.id, o.created_at, o.location_id, o.customer_account_id,
                       o.channel_code_snapshot,
                       o.created_by_actor_type, o.created_by_actor_id,
                       o.accepted_by_actor_type, o.accepted_by_actor_id,
                       cs.display_name_encrypted, cs.contact_encrypted, cs.anonymized_at,
                       -- See this class's own doc for why a subquery and not a join.
                       (SELECT c.display_reference
                          FROM fulfillment.shipments s
                          JOIN fulfillment.couriers c
                            ON c.tenant_id = s.tenant_id AND c.id = s.courier_id
                         WHERE s.tenant_id = o.tenant_id AND s.order_id = o.id
                         ORDER BY s.assigned_at DESC NULLS LAST, s.created_at DESC
                         LIMIT 1) AS courier_display_reference
                  FROM ordering.orders o
                  LEFT JOIN ordering.order_customer_snapshots cs
                    ON cs.tenant_id = o.tenant_id AND cs.order_id = o.id
                 WHERE o.tenant_id = :tenantId
                   AND o.created_at >= :from AND o.created_at < :to
                """ + filter + " ORDER BY o.created_at DESC, o.id DESC LIMIT :limit")
                .params(params)
                .query(JdbcOrderCrmLogStore::crmLogRow)
                .list();
    }

    /**
     * Gap map row 1.1: the customer half of the board's own Клиент column,
     * batched by {@code orderId} for exactly one already-fetched page —
     * never a date range, never a reporting fact, the same restraint {@link
     * #list} already keeps. Order rows outside {@code orderIds} or this
     * tenant are simply absent from the result, the same "caller only gets
     * what it asked for and is scoped to" contract {@link
     * uz.horecaos.platform.fulfillment.api.ActiveCourierAssignmentsPort}
     * already keeps for the Курьер column added alongside this one.
     *
     * @param orderIds bounded by the caller ({@code OrderCrmLogController}) —
     *                 this store trusts the caller already capped it to one
     *                 board page's worth
     */
    public List<CustomerLabelRow> customerLabels(UUID tenantId, Set<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT o.id, o.customer_account_id, cs.display_name_encrypted, cs.contact_encrypted,
                       cs.anonymized_at
                  FROM ordering.orders o
                  LEFT JOIN ordering.order_customer_snapshots cs
                    ON cs.tenant_id = o.tenant_id AND cs.order_id = o.id
                 WHERE o.tenant_id = :tenantId
                   AND o.id IN (:orderIds)
                """)
                .param("tenantId", tenantId)
                .param("orderIds", orderIds)
                .query(JdbcOrderCrmLogStore::customerLabelRow)
                .list();
    }

    private static CustomerLabelRow customerLabelRow(ResultSet row, int number) throws SQLException {
        return new CustomerLabelRow(
                row.getObject("id", UUID.class),
                row.getObject("customer_account_id", UUID.class) != null ? "ACCOUNT" : "GUEST",
                row.getString("display_name_encrypted"),
                row.getString("contact_encrypted"),
                row.getObject("anonymized_at", OffsetDateTime.class) != null);
    }

    private static CrmLogRow crmLogRow(ResultSet row, int number) throws SQLException {
        String createdByActorType = row.getString("created_by_actor_type");
        String createdByActorId = row.getString("created_by_actor_id");
        String acceptedByActorType = row.getString("accepted_by_actor_type");
        String acceptedByActorId = row.getString("accepted_by_actor_id");
        String channelCode = row.getString("channel_code_snapshot");

        return new CrmLogRow(
                row.getObject("id", UUID.class),
                requireInstant(row, "created_at"),
                row.getObject("location_id", UUID.class),
                row.getObject("customer_account_id", UUID.class) != null ? "ACCOUNT" : "GUEST",
                row.getString("display_name_encrypted"),
                row.getString("contact_encrypted"),
                row.getObject("anonymized_at", OffsetDateTime.class) != null,
                operatorPrincipalId(
                        createdByActorType, createdByActorId, acceptedByActorType, acceptedByActorId, channelCode),
                row.getString("courier_display_reference"));
    }

    /**
     * The console's own operator column, on the same precedence {@code
     * OperatorAttribution} uses in the reporting module: whoever approved the
     * order outranks whoever created it, and an order with no human actor at
     * either end is credited to the channel it came in on. Duplicated here
     * rather than imported — {@code OperatorAttribution} is package-private to
     * {@code reporting.application}, which this module may not reach into,
     * and the CRM log must not depend on the reporting module regardless (this
     * class's own doc).
     */
    private static String operatorPrincipalId(
            @Nullable String createdByActorType,
            @Nullable String createdByActorId,
            @Nullable String acceptedByActorType,
            @Nullable String acceptedByActorId,
            String channelCode) {
        if ("USER".equals(acceptedByActorType) && acceptedByActorId != null && !acceptedByActorId.isBlank()) {
            return acceptedByActorId;
        }
        if ("USER".equals(createdByActorType) && createdByActorId != null && !createdByActorId.isBlank()) {
            return createdByActorId;
        }
        return "channel:" + channelCode;
    }

    private static Instant requireInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new IllegalStateException(column + " is NOT NULL but was null");
        }
        return value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * One log row, exactly as decrypted as an ordinary console read may go
     * (orders.md §1.5/§3.7): the name in full, the phone decrypted for the
     * caller to mask, never the address. The customer type is derived from
     * {@code customer_account_id} being set, so it is never a third "unknown"
     * state even for an order that predates the snapshot row.
     *
     * @param displayNameEncrypted null when the order carries no customer snapshot at all
     * @param contactEncrypted     null when the snapshot carries no phone, or the order is a guest order
     */
    public record CrmLogRow(
            UUID orderId,
            Instant occurredAt,
            UUID locationId,
            String customerType,
            @Nullable String displayNameEncrypted,
            @Nullable String contactEncrypted,
            boolean anonymized,
            String operatorPrincipalId,
            @Nullable String courierDisplayReference) {}

    /** A keyset cursor: the previous page's last row. */
    public record LogCursor(Instant occurredAt, UUID orderId) {}

    /**
     * One order's customer half, gap map row 1.1 — {@link #customerLabels}'s
     * own row, the same shape {@link CrmLogRow} carries minus the fields the
     * board's Клиент column has no use for (occurred/location/operator/courier).
     *
     * @param displayNameEncrypted null when the order carries no customer snapshot at all
     * @param contactEncrypted     null when the snapshot carries no phone, or the order is a guest order
     */
    public record CustomerLabelRow(
            UUID orderId,
            String customerType,
            @Nullable String displayNameEncrypted,
            @Nullable String contactEncrypted,
            boolean anonymized) {}
}
