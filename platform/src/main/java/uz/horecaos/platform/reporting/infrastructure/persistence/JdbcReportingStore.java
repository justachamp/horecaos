package uz.horecaos.platform.reporting.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.horecaos.platform.reporting.application.ReportingFacts.BranchDayAggregate;
import uz.horecaos.platform.reporting.application.ReportingFacts.BranchDayKey;
import uz.horecaos.platform.reporting.application.ReportingFacts.OrderFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.OrderLineFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.RefundFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.SlaBucketAggregate;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.domain.MetricDefinition;

/**
 * Reporting persistence (ADR 0043).
 *
 * <p>Three rules run through every statement here.
 *
 * <p>The tenant predicate is always inside the query, including on the fact
 * tables. A location id or an order id is a UUID that arrives from a client, and
 * a report matching on it alone would serve another tenant's revenue.
 *
 * <p>Every nullable column is read with {@code getObject(name, Integer.class)}
 * rather than {@code getInt}. {@code getInt} answers zero for SQL NULL, and a
 * silent zero in this module is a promise that was never made reading as an order
 * delivered instantly.
 *
 * <p>The source reads — {@link #readSourceOrders}, {@link #readSourceLines},
 * {@link #readSourceRefunds} — are the only statements in the reporting module
 * that touch a module schema, and they are read-only. Everything the read path
 * uses stays inside {@code reporting}, which is what the
 * {@code horecaos_reporting_read} role enforces at the database.
 */
@Repository
public class JdbcReportingStore {

    private final JdbcClient jdbc;

    public JdbcReportingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- boundary

    /**
     * The tenant's business-day boundary, or the platform default derived from
     * the tenant's own timezone.
     *
     * <p>Falling back to the tenant timezone rather than to UTC is the whole
     * point: a UTC default rolls the business day over at 05:00 Tashkent time,
     * mid-evening service, and files a third of the night under tomorrow.
     */
    public Optional<StoredBoundary> findBoundary(UUID tenantId) {
        return jdbc.sql("""
                SELECT p.business_day_start, p.timezone, p.boundary_version,
                       p.recut_completed_through
                  FROM reporting.business_day_policies p
                 WHERE p.tenant_id = :tenantId
                """)
                .param("tenantId", tenantId)
                .query((ResultSet row, int number) -> new StoredBoundary(
                        new BusinessDayBoundary(
                                ZoneId.of(row.getString("timezone")),
                                row.getObject("business_day_start", LocalTime.class),
                                row.getInt("boundary_version")),
                        row.getObject("recut_completed_through", LocalDate.class)))
                .optional();
    }

    /** The tenant's declared timezone, which the default boundary is expressed in. */
    public Optional<String> findTenantTimezone(UUID tenantId) {
        return jdbc.sql("SELECT default_timezone FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .optional();
    }

    public void upsertBoundary(UUID tenantId, BusinessDayBoundary boundary,
            LocalDate effectiveFrom, LocalDate recutCompletedThrough) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("start", boundary.start());
        params.put("timezone", boundary.zone().getId());
        params.put("version", boundary.version());
        params.put("effectiveFrom", effectiveFrom);
        params.put("recutThrough", recutCompletedThrough);

        jdbc.sql("""
                INSERT INTO reporting.business_day_policies (
                    tenant_id, business_day_start, timezone, boundary_version,
                    boundary_effective_from, recut_completed_through, updated_at)
                VALUES (:tenantId, :start, :timezone, :version, :effectiveFrom, :recutThrough, now())
                ON CONFLICT (tenant_id) DO UPDATE
                SET business_day_start = EXCLUDED.business_day_start,
                    timezone = EXCLUDED.timezone,
                    boundary_version = EXCLUDED.boundary_version,
                    boundary_effective_from = EXCLUDED.boundary_effective_from,
                    recut_completed_through = EXCLUDED.recut_completed_through,
                    updated_at = now()
                """)
                .params(params)
                .update();
    }

    /**
     * @param recutCompletedThrough how far a recut after a boundary change has
     *                              got. Null when no change is outstanding
     */
    public record StoredBoundary(BusinessDayBoundary boundary, LocalDate recutCompletedThrough) {
    }

    // ------------------------------------------------------ metric registry

    /**
     * Mirrors one code-owned definition, leaving any signature alone.
     *
     * <p>The insert never updates the definition columns. ADR 0043 says a
     * definition change is a new version, so a row that disagrees with code is a
     * mistake and {@code MetricDefinitionSynchronizer} refuses to start rather
     * than quietly making the database agree with whatever shipped.
     */
    public void insertDefinitionIfAbsent(MetricDefinition definition) {
        Map<String, Object> params = new HashMap<>();
        params.put("metricId", definition.id().name());
        params.put("version", definition.id().version());
        params.put("grain", definition.grain().name());
        params.put("sourceFact", definition.sourceFact());
        params.put("sourceAvailable", definition.sourceAvailable());
        params.put("aggregation", definition.aggregation().name());
        params.put("inclusionRule", definition.inclusionRule());
        params.put("currencyRule", definition.currencyRule().name());
        params.put("roundingRule", definition.roundingRule());
        params.put("unit", definition.unit().name());
        params.put("digest", definition.digest());
        params.put("effectiveFrom", definition.effectiveFrom());

        jdbc.sql("""
                INSERT INTO reporting.metric_definitions (
                    metric_id, version, grain, source_fact, source_available, aggregation,
                    inclusion_rule_code, currency_rule, rounding_rule, unit, definition_digest,
                    effective_from)
                VALUES (
                    :metricId, :version, :grain, :sourceFact, :sourceAvailable, :aggregation,
                    :inclusionRule, :currencyRule, :roundingRule, :unit, :digest, :effectiveFrom)
                ON CONFLICT (metric_id, version) DO NOTHING
                """)
                .params(params)
                .update();
    }

    /** The stored digest and signature for one metric version. */
    public Optional<StoredMetric> findStoredMetric(String name, int version) {
        return jdbc.sql("""
                SELECT definition_digest, signed_by, signed_at
                  FROM reporting.metric_definitions
                 WHERE metric_id = :metricId AND version = :version
                """)
                .param("metricId", name).param("version", version)
                .query((ResultSet row, int number) -> new StoredMetric(
                        row.getString("definition_digest"),
                        row.getString("signed_by"),
                        instantOrNull(row, "signed_at")))
                .optional();
    }

    /**
     * Records the finance signature.
     *
     * <p>Refuses a metric already signed rather than replacing the signature. A
     * second signature over the same words says nothing new, and overwriting the
     * first loses who actually decided.
     */
    public int sign(String name, int version, String signedBy, Instant signedAt) {
        return jdbc.sql("""
                UPDATE reporting.metric_definitions
                   SET signed_by = :signedBy, signed_at = :signedAt
                 WHERE metric_id = :metricId AND version = :version AND signed_by IS NULL
                """)
                .param("metricId", name).param("version", version)
                .param("signedBy", signedBy).param("signedAt", utc(signedAt))
                .update();
    }

    public record StoredMetric(String digest, String signedBy, Instant signedAt) {
    }

    // -------------------------------------------------------- source reads

    /**
     * Every order created inside one business day's instant window.
     *
     * <p>Includes orders that have not reached a terminal status: they are
     * written with the status they currently hold, and the metrics filter on it.
     * Excluding them would leave a day permanently short of the orders that were
     * still open when it closed.
     */
    public List<SourceOrder> readSourceOrders(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT o.id, o.brand_id, o.location_id, o.channel_code_snapshot, o.fulfillment_mode,
                       o.status, o.created_at, o.confirmed_at, o.closed_at, o.customer_account_id,
                       o.subtotal_minor, o.tax_minor, o.discount_minor, o.fee_minor, o.total_minor,
                       o.promised_at, o.promise_travel_minutes, o.version,
                       (SELECT i.legal_entity_id
                          FROM payments.payment_intents i
                         WHERE i.tenant_id = o.tenant_id AND i.order_id = o.id
                           AND i.legal_entity_id IS NOT NULL
                         ORDER BY i.created_at
                         LIMIT 1) AS legal_entity_id,
                       (SELECT min(h.occurred_at)
                          FROM ordering.order_state_history h
                         WHERE h.tenant_id = o.tenant_id AND h.order_id = o.id
                           AND h.to_status = 'READY') AS ready_at,
                       (SELECT h.reason_code
                          FROM ordering.order_state_history h
                         WHERE h.tenant_id = o.tenant_id AND h.order_id = o.id
                           AND h.to_status IN ('CANCELLED', 'REJECTED', 'EXPIRED')
                         ORDER BY h.sequence_number DESC
                         LIMIT 1) AS cancellation_reason_code,
                       (o.customer_account_id IS NOT NULL AND NOT EXISTS (
                            SELECT 1 FROM ordering.orders e
                             WHERE e.tenant_id = o.tenant_id
                               AND e.customer_account_id = o.customer_account_id
                               AND e.status = 'COMPLETED'
                               AND e.created_at < o.created_at)) AS is_first_order
                  FROM ordering.orders o
                 WHERE o.tenant_id = :tenantId
                   AND o.created_at >= :from AND o.created_at < :to
                 ORDER BY o.created_at, o.id
                """)
                .param("tenantId", tenantId).param("from", utc(from)).param("to", utc(to))
                .query(JdbcReportingStore::sourceOrder)
                .list();
    }

    public List<SourceLine> readSourceLines(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT l.id, l.order_id, l.source_variant_id, l.product_name_snapshot,
                       l.quantity, l.base_amount_minor, l.final_amount_minor
                  FROM ordering.order_lines l
                  JOIN ordering.orders o ON o.id = l.order_id AND o.tenant_id = l.tenant_id
                 WHERE l.tenant_id = :tenantId
                   AND o.created_at >= :from AND o.created_at < :to
                 ORDER BY l.order_id, l.line_number
                """)
                .param("tenantId", tenantId).param("from", utc(from)).param("to", utc(to))
                .query((ResultSet row, int number) -> new SourceLine(
                        row.getObject("id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getObject("source_variant_id", UUID.class),
                        row.getString("product_name_snapshot"),
                        row.getInt("quantity"),
                        row.getLong("base_amount_minor"),
                        row.getLong("final_amount_minor")))
                .list();
    }

    /**
     * Refunds settled inside one business day's window, whatever day the orders
     * they reverse belong to.
     *
     * <p>The provider's {@code occurred_at} is used and not HorecaOS's
     * {@code recorded_at}: a callback replayed a day late would otherwise move the
     * refund onto the wrong business date, and a settlement file is matched
     * against the provider's clock.
     */
    public List<SourceRefund> readSourceRefunds(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT t.id, t.amount_minor, t.occurred_at, i.order_id
                  FROM payments.payment_transactions t
                  JOIN payments.payment_intents i
                    ON i.id = t.intent_id AND i.tenant_id = t.tenant_id
                 WHERE t.tenant_id = :tenantId
                   AND t.transaction_type = 'REFUND'
                   AND t.occurred_at >= :from AND t.occurred_at < :to
                 ORDER BY t.occurred_at, t.id
                """)
                .param("tenantId", tenantId).param("from", utc(from)).param("to", utc(to))
                .query((ResultSet row, int number) -> new SourceRefund(
                        row.getObject("id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getLong("amount_minor"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * The orders behind a day's refunds, wherever they sit.
     *
     * <p>Needed because a refund's business date is not the order's, so the close
     * that files the refund cannot have the order in the day it is reading.
     *
     * <p>The whole set in one statement rather than one statement per refund. A
     * busy Saturday's refunds are hundreds of round-trips inside the close
     * transaction, each one a primary-key lookup that the planner would have done
     * as a single index scan had it been asked once. An order id absent from the
     * result is a refund whose order this tenant does not have, which is what the
     * caller already treats as a refund it cannot file.
     */
    public Map<UUID, RefundedOrder> findRefundedOrders(UUID tenantId, Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, RefundedOrder> byOrder = new HashMap<>();
        jdbc.sql("""
                SELECT o.id, o.location_id, o.channel_code_snapshot, o.fulfillment_mode,
                       o.created_at,
                       (SELECT i.legal_entity_id
                          FROM payments.payment_intents i
                         WHERE i.tenant_id = o.tenant_id AND i.order_id = o.id
                           AND i.legal_entity_id IS NOT NULL
                         ORDER BY i.created_at
                         LIMIT 1) AS legal_entity_id
                  FROM ordering.orders o
                 WHERE o.tenant_id = :tenantId AND o.id = ANY(:orderIds)
                """)
                .param("tenantId", tenantId)
                .param("orderIds", orderIds.stream().distinct().toArray(UUID[]::new))
                .query((ResultSet row, int number) -> Map.entry(
                        row.getObject("id", UUID.class),
                        new RefundedOrder(
                                row.getObject("location_id", UUID.class),
                                row.getObject("legal_entity_id", UUID.class),
                                row.getString("channel_code_snapshot"),
                                row.getString("fulfillment_mode"),
                                row.getObject("created_at", OffsetDateTime.class).toInstant())))
                .list()
                .forEach(entry -> byOrder.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(byOrder);
    }

    public record SourceOrder(UUID orderId, UUID brandId, UUID locationId, UUID legalEntityId,
            String channelCode, String fulfilmentMode, String status, Instant createdAt,
            Instant confirmedAt, Instant closedAt, Instant readyAt, UUID customerAccountId,
            boolean firstOrder, long subtotalMinor, long taxMinor, long discountMinor,
            long feeMinor, long totalMinor, Instant promisedAt, Integer promiseTravelMinutes,
            String cancellationReasonCode, int version) {
    }

    public record SourceLine(UUID lineId, UUID orderId, UUID variantId, String productName,
            int quantity, long baseAmountMinor, long finalAmountMinor) {
    }

    public record SourceRefund(UUID refundId, UUID orderId, long amountMinor, Instant occurredAt) {
    }

    public record RefundedOrder(UUID locationId, UUID legalEntityId, String channelCode,
            String fulfilmentMode, Instant createdAt) {
    }

    // ---------------------------------------------------------- fact writes

    /**
     * Clears one tenant's day before it is rebuilt.
     *
     * <p>Delete-then-insert rather than upsert, because the set of orders on a day
     * can shrink: an order whose business date moved must not be left behind on
     * the old day, where it would be counted twice.
     */
    public void clearDay(UUID tenantId, LocalDate businessDate) {
        for (String table : List.of("fact_order_line", "fact_order", "fact_refund",
                "agg_branch_day", "agg_sla_bucket_day")) {
            jdbc.sql("DELETE FROM reporting.%s WHERE tenant_id = :tenantId AND business_date = :day"
                    .formatted(table))
                    .param("tenantId", tenantId).param("day", businessDate)
                    .update();
        }
    }

    /**
     * Removes any fact for these orders filed under another day.
     *
     * <p>The primary key includes the business date, so a boundary change would
     * otherwise leave the same order counted on two dates with nothing to catch
     * it. Runs on {@code ix_fact_order_source}.
     */
    public void clearMisfiledOrders(UUID tenantId, List<UUID> orderIds, LocalDate keep) {
        if (orderIds.isEmpty()) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("orderIds", orderIds);
        params.put("keep", keep);

        jdbc.sql("""
                DELETE FROM reporting.fact_order_line
                 WHERE tenant_id = :tenantId AND order_id IN (:orderIds)
                   AND business_date <> :keep
                """).params(params).update();
        jdbc.sql("""
                DELETE FROM reporting.fact_order
                 WHERE tenant_id = :tenantId AND order_id IN (:orderIds)
                   AND business_date <> :keep
                """).params(params).update();
    }

    public void insertOrderFact(OrderFact fact) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", fact.tenantId());
        params.put("orderId", fact.orderId());
        params.put("businessDate", fact.businessDate());
        params.put("boundaryVersion", fact.boundaryVersion());
        params.put("occurredAt", utc(fact.occurredAt()));
        params.put("closedAt", utc(fact.closedAt()));
        params.put("brandId", fact.brandId());
        params.put("locationId", fact.locationId());
        params.put("legalEntityId", fact.legalEntityId());
        params.put("channelCode", fact.channelCode());
        params.put("fulfilmentType", fact.fulfilmentType());
        params.put("terminalStatus", fact.terminalStatus());
        params.put("cancellationReasonCode", fact.cancellationReasonCode());
        params.put("customerSubjectHash", fact.customerSubjectHash());
        params.put("isFirstOrder", fact.isFirstOrder());
        params.put("gross", fact.grossRevenueSom());
        params.put("discount", fact.discountSom());
        params.put("deliveryFee", fact.deliveryFeeSom());
        params.put("tax", fact.taxSom());
        params.put("net", fact.netRevenueSom());
        params.put("lineCount", fact.lineCount());
        params.put("itemCount", fact.itemCount());
        params.put("secondsToConfirm", fact.secondsToConfirm());
        params.put("secondsToReady", fact.secondsToReady());
        params.put("secondsTotal", fact.secondsTotal());
        params.put("promisedAt", utc(fact.promisedAt()));
        params.put("promiseTravelMinutes", fact.promiseTravelMinutes());
        params.put("secondsLate", fact.secondsLate());
        params.put("calculationVersion", fact.metricCalculationVersion());
        params.put("sourceOrderVersion", fact.sourceOrderVersion());

        jdbc.sql("""
                INSERT INTO reporting.fact_order (
                    tenant_id, order_id, business_date, boundary_version, occurred_at, closed_at,
                    brand_id, location_id, legal_entity_id, channel_code, fulfilment_type,
                    terminal_status, cancellation_reason_code, customer_subject_hash,
                    is_first_order, gross_revenue_som, discount_som, delivery_fee_som, tax_som,
                    net_revenue_som, line_count, item_count, seconds_to_confirm, seconds_to_ready,
                    seconds_total, promised_at, promise_travel_minutes, seconds_late,
                    metric_calculation_version, source_order_version)
                VALUES (
                    :tenantId, :orderId, :businessDate, :boundaryVersion, :occurredAt, :closedAt,
                    :brandId, :locationId, :legalEntityId, :channelCode, :fulfilmentType,
                    :terminalStatus, :cancellationReasonCode, :customerSubjectHash,
                    :isFirstOrder, :gross, :discount, :deliveryFee, :tax,
                    :net, :lineCount, :itemCount, :secondsToConfirm, :secondsToReady,
                    :secondsTotal, :promisedAt, :promiseTravelMinutes, :secondsLate,
                    :calculationVersion, :sourceOrderVersion)
                """)
                .params(params)
                .update();
    }

    public void insertLineFact(OrderLineFact fact) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", fact.tenantId());
        params.put("businessDate", fact.businessDate());
        params.put("orderId", fact.orderId());
        params.put("lineId", fact.lineId());
        params.put("locationId", fact.locationId());
        params.put("variantId", fact.variantId());
        params.put("categoryId", fact.categoryId());
        params.put("productName", fact.productNameSnapshot());
        params.put("quantity", fact.quantity());
        params.put("gross", fact.grossSom());
        params.put("discount", fact.discountSom());
        params.put("net", fact.netSom());

        jdbc.sql("""
                INSERT INTO reporting.fact_order_line (
                    tenant_id, business_date, order_id, line_id, location_id, variant_id,
                    category_id, product_name_snapshot, quantity, gross_som, discount_som, net_som)
                VALUES (
                    :tenantId, :businessDate, :orderId, :lineId, :locationId, :variantId,
                    :categoryId, :productName, :quantity, :gross, :discount, :net)
                """)
                .params(params)
                .update();
    }

    public void insertRefundFact(RefundFact fact) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", fact.tenantId());
        params.put("businessDate", fact.businessDate());
        params.put("refundId", fact.refundId());
        params.put("orderId", fact.orderId());
        params.put("orderBusinessDate", fact.orderBusinessDate());
        params.put("locationId", fact.locationId());
        params.put("legalEntityId", fact.legalEntityId());
        params.put("channelCode", fact.channelCode());
        params.put("fulfilmentType", fact.fulfilmentType());
        params.put("refunded", fact.refundedSom());
        params.put("occurredAt", utc(fact.occurredAt()));
        params.put("boundaryVersion", fact.boundaryVersion());
        params.put("calculationVersion", fact.metricCalculationVersion());

        jdbc.sql("""
                INSERT INTO reporting.fact_refund (
                    tenant_id, business_date, refund_id, order_id, order_business_date,
                    location_id, legal_entity_id, channel_code, fulfilment_type, refunded_som,
                    occurred_at, boundary_version, metric_calculation_version)
                VALUES (
                    :tenantId, :businessDate, :refundId, :orderId, :orderBusinessDate,
                    :locationId, :legalEntityId, :channelCode, :fulfilmentType, :refunded,
                    :occurredAt, :boundaryVersion, :calculationVersion)
                """)
                .params(params)
                .update();
    }

    public void insertAggregate(BranchDayAggregate row) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", row.key().tenantId());
        params.put("businessDate", row.key().businessDate());
        params.put("locationId", row.key().locationId());
        params.put("legalEntityId", row.key().legalEntityId());
        params.put("channelCode", row.key().channelCode());
        params.put("fulfilmentType", row.key().fulfilmentType());
        params.put("boundaryVersion", row.boundaryVersion());
        params.put("calculationVersion", row.metricCalculationVersion());
        params.put("orderCount", row.orderCount());
        params.put("cancelledCount", row.cancelledCount());
        params.put("gross", row.grossSom());
        params.put("discount", row.discountSom());
        params.put("net", row.netSom());
        params.put("refunded", row.refundedSom());
        params.put("avgSecondsTotal", row.avgSecondsTotal());
        params.put("promisedCount", row.promisedCount());
        params.put("lateCount", row.lateCount());
        params.put("distinctCustomers", row.distinctCustomers());
        params.put("newCustomers", row.newCustomers());

        jdbc.sql("""
                INSERT INTO reporting.agg_branch_day (
                    tenant_id, business_date, location_id, legal_entity_id, channel_code,
                    fulfilment_type, boundary_version, metric_calculation_version, order_count,
                    cancelled_count, gross_som, discount_som, net_som, refunded_som,
                    avg_seconds_total, promised_count, late_count, distinct_customers,
                    new_customers)
                VALUES (
                    :tenantId, :businessDate, :locationId, :legalEntityId, :channelCode,
                    :fulfilmentType, :boundaryVersion, :calculationVersion, :orderCount,
                    :cancelledCount, :gross, :discount, :net, :refunded,
                    :avgSecondsTotal, :promisedCount, :lateCount, :distinctCustomers,
                    :newCustomers)
                """)
                .params(params)
                .update();
    }

    public void insertSlaBucket(SlaBucketAggregate row) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", row.tenantId());
        params.put("businessDate", row.businessDate());
        params.put("scopeKind", row.scopeKind());
        params.put("scopeId", row.scopeId());
        params.put("bucketSetVersion", row.bucketSetVersion());
        params.put("bucketCode", row.bucketCode());
        params.put("orderCount", row.orderCount());
        params.put("share", row.shareBasisPoints());

        jdbc.sql("""
                INSERT INTO reporting.agg_sla_bucket_day (
                    tenant_id, business_date, scope_kind, scope_id, bucket_set_version,
                    bucket_code, order_count, share_basis_points)
                VALUES (
                    :tenantId, :businessDate, :scopeKind, :scopeId, :bucketSetVersion,
                    :bucketCode, :orderCount, :share)
                """)
                .params(params)
                .update();
    }

    // ---------------------------------------------------------- read path

    /**
     * The stored aggregate for one day.
     *
     * <p>Used both by the query API and by the settle recut, which compares this
     * against a fresh derivation and alerts rather than overwriting.
     */
    public List<BranchDayAggregate> readAggregates(UUID tenantId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT tenant_id, business_date, location_id, legal_entity_id, channel_code,
                       fulfilment_type, boundary_version, metric_calculation_version, order_count,
                       cancelled_count, gross_som, discount_som, net_som, refunded_som,
                       avg_seconds_total, promised_count, late_count, distinct_customers,
                       new_customers
                  FROM reporting.agg_branch_day
                 WHERE tenant_id = :tenantId AND business_date BETWEEN :from AND :to
                 ORDER BY business_date, location_id, channel_code, fulfilment_type
                """)
                .param("tenantId", tenantId).param("from", from).param("to", to)
                .query(JdbcReportingStore::aggregate)
                .list();
    }

    public List<SlaBucketAggregate> readSlaBuckets(UUID tenantId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT tenant_id, business_date, scope_kind, scope_id, bucket_set_version,
                       bucket_code, order_count, share_basis_points
                  FROM reporting.agg_sla_bucket_day
                 WHERE tenant_id = :tenantId AND business_date BETWEEN :from AND :to
                 ORDER BY business_date, scope_id, bucket_code
                """)
                .param("tenantId", tenantId).param("from", from).param("to", to)
                .query((ResultSet row, int number) -> new SlaBucketAggregate(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("business_date", LocalDate.class),
                        row.getString("scope_kind"),
                        row.getObject("scope_id", UUID.class),
                        row.getInt("bucket_set_version"),
                        row.getString("bucket_code"),
                        row.getInt("order_count"),
                        row.getInt("share_basis_points")))
                .list();
    }

    /**
     * The median preparation time, computed in the database.
     *
     * <p>Not derivable from {@code agg_branch_day}: a median cannot be composed
     * from per-slice medians, and averaging them is the classic way to publish a
     * number that is not the median of anything.
     *
     * @return null when no order in the range reached READY, which is not zero
     */
    public Integer medianSecondsToReady(UUID tenantId, LocalDate from, LocalDate to,
            List<UUID> locationIds) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("from", from);
        params.put("to", to);

        // The location predicate is appended rather than expressed as a nullable
        // parameter. A bind variable cannot stand in for an absent IN list, and
        // the alternatives — an empty list, or a null array compared with ANY —
        // both silently return nothing, which reads on the screen as a quiet day.
        String locationFilter = "";
        if (!locationIds.isEmpty()) {
            locationFilter = " AND location_id IN (:locations)";
            params.put("locations", locationIds);
        }

        Double median = jdbc.sql("""
                SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY seconds_to_ready)
                  FROM reporting.fact_order
                 WHERE tenant_id = :tenantId AND business_date BETWEEN :from AND :to
                   AND seconds_to_ready IS NOT NULL
                """ + locationFilter)
                .params(params)
                .query(Double.class)
                .optional()
                .orElse(null);

        return median == null ? null : (int) Math.round(median);
    }

    /** The boundary versions present in a range, so a mixed range can be refused. */
    public List<Integer> boundaryVersionsIn(UUID tenantId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT DISTINCT boundary_version
                  FROM reporting.agg_branch_day
                 WHERE tenant_id = :tenantId AND business_date BETWEEN :from AND :to
                 ORDER BY boundary_version
                """)
                .param("tenantId", tenantId).param("from", from).param("to", to)
                .query(Integer.class)
                .list();
    }

    // ------------------------------------------------ runs and divergence

    public void insertRun(UUID runId, UUID tenantId, LocalDate businessDate, String kind,
            int boundaryVersion, int calculationVersion, Instant startedAt) {
        jdbc.sql("""
                INSERT INTO reporting.close_runs (
                    id, tenant_id, business_date, run_kind, status, boundary_version,
                    metric_calculation_version, started_at)
                VALUES (
                    :id, :tenantId, :businessDate, :kind, 'RUNNING', :boundaryVersion,
                    :calculationVersion, :startedAt)
                """)
                .param("id", runId).param("tenantId", tenantId).param("businessDate", businessDate)
                .param("kind", kind).param("boundaryVersion", boundaryVersion)
                .param("calculationVersion", calculationVersion).param("startedAt", utc(startedAt))
                .update();
    }

    public void completeRun(UUID runId, int ordersWritten, int linesWritten, int divergencesFound,
            Instant completedAt) {
        jdbc.sql("""
                UPDATE reporting.close_runs
                   SET status = 'COMPLETED', orders_written = :orders, lines_written = :lines,
                       divergences_found = :divergences, completed_at = :completedAt
                 WHERE id = :id AND status = 'RUNNING'
                """)
                .param("id", runId).param("orders", ordersWritten).param("lines", linesWritten)
                .param("divergences", divergencesFound).param("completedAt", utc(completedAt))
                .update();
    }

    public void failRun(UUID runId, String reason, Instant completedAt) {
        jdbc.sql("""
                UPDATE reporting.close_runs
                   SET status = 'FAILED', failure_reason = :reason, completed_at = :completedAt
                 WHERE id = :id AND status = 'RUNNING'
                """)
                .param("id", runId)
                .param("reason", reason == null ? "Unstated" : truncate(reason, 512))
                .param("completedAt", utc(completedAt))
                .update();
    }

    /** The most recent completed run for a tenant, which is what freshness reports. */
    public Optional<CompletedRun> findLatestCompletedRun(UUID tenantId) {
        return jdbc.sql("""
                SELECT business_date, run_kind, completed_at
                  FROM reporting.close_runs
                 WHERE tenant_id = :tenantId AND status = 'COMPLETED'
                 ORDER BY business_date DESC, completed_at DESC
                 LIMIT 1
                """)
                .param("tenantId", tenantId)
                .query((ResultSet row, int number) -> new CompletedRun(
                        row.getObject("business_date", LocalDate.class),
                        row.getString("run_kind"),
                        instantOrNull(row, "completed_at")))
                .optional();
    }

    public void insertDivergence(UUID id, UUID tenantId, UUID runId, LocalDate businessDate,
            String metricId, int metricVersion, String dimensionKey, long storedValue,
            long recutValue) {
        jdbc.sql("""
                INSERT INTO reporting.aggregate_divergences (
                    id, tenant_id, run_id, business_date, metric_id, metric_version,
                    dimension_key, stored_value, recut_value, difference)
                VALUES (
                    :id, :tenantId, :runId, :businessDate, :metricId, :metricVersion,
                    :dimensionKey, :stored, :recut, :difference)
                """)
                .param("id", id).param("tenantId", tenantId).param("runId", runId)
                .param("businessDate", businessDate).param("metricId", metricId)
                .param("metricVersion", metricVersion)
                .param("dimensionKey", truncate(dimensionKey, 512))
                .param("stored", storedValue).param("recut", recutValue)
                .param("difference", recutValue - storedValue)
                .update();
    }

    public List<Divergence> readOpenDivergences(UUID tenantId) {
        return jdbc.sql("""
                SELECT business_date, metric_id, metric_version, dimension_key, stored_value,
                       recut_value, difference, detected_at
                  FROM reporting.aggregate_divergences
                 WHERE tenant_id = :tenantId AND status = 'OPEN'
                 ORDER BY detected_at DESC
                """)
                .param("tenantId", tenantId)
                .query((ResultSet row, int number) -> new Divergence(
                        row.getObject("business_date", LocalDate.class),
                        row.getString("metric_id") + ".v" + row.getInt("metric_version"),
                        row.getString("dimension_key"),
                        row.getLong("stored_value"),
                        row.getLong("recut_value"),
                        row.getLong("difference"),
                        instantOrNull(row, "detected_at")))
                .list();
    }

    public record CompletedRun(LocalDate businessDate, String runKind, Instant completedAt) {
    }

    public record Divergence(LocalDate businessDate, String metricCode, String dimensionKey,
            long storedValue, long recutValue, long difference, Instant detectedAt) {
    }

    // ------------------------------------------------------------ mapping

    private static SourceOrder sourceOrder(ResultSet row, int number) throws SQLException {
        return new SourceOrder(
                row.getObject("id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("legal_entity_id", UUID.class),
                row.getString("channel_code_snapshot"),
                row.getString("fulfillment_mode"),
                row.getString("status"),
                instantOrNull(row, "created_at"),
                instantOrNull(row, "confirmed_at"),
                instantOrNull(row, "closed_at"),
                instantOrNull(row, "ready_at"),
                row.getObject("customer_account_id", UUID.class),
                row.getBoolean("is_first_order"),
                row.getLong("subtotal_minor"),
                row.getLong("tax_minor"),
                row.getLong("discount_minor"),
                row.getLong("fee_minor"),
                row.getLong("total_minor"),
                instantOrNull(row, "promised_at"),
                // getInt would answer zero here, and a promise with no modelled
                // travel would read as a promise with zero travel.
                row.getObject("promise_travel_minutes", Integer.class),
                row.getString("cancellation_reason_code"),
                row.getInt("version"));
    }

    private static BranchDayAggregate aggregate(ResultSet row, int number) throws SQLException {
        BranchDayKey key = new BranchDayKey(
                row.getObject("tenant_id", UUID.class),
                row.getObject("business_date", LocalDate.class),
                row.getObject("location_id", UUID.class),
                row.getObject("legal_entity_id", UUID.class),
                row.getString("channel_code"),
                row.getString("fulfilment_type"));

        return new BranchDayAggregate(key,
                row.getInt("boundary_version"),
                row.getInt("metric_calculation_version"),
                row.getInt("order_count"),
                row.getInt("cancelled_count"),
                row.getLong("gross_som"),
                row.getLong("discount_som"),
                row.getLong("net_som"),
                row.getLong("refunded_som"),
                // Nullable on purpose: a day on which nothing closed has no
                // average, and getInt would report an instant one.
                row.getObject("avg_seconds_total", Integer.class),
                row.getInt("promised_count"),
                row.getInt("late_count"),
                row.getInt("distinct_customers"),
                row.getInt("new_customers"));
    }

    private static Instant instantOrNull(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
