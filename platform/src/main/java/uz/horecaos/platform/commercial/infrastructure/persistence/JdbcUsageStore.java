package uz.horecaos.platform.commercial.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.commercial.api.UsagePeriod;
import uz.horecaos.platform.commercial.domain.UsageTotals;

/**
 * The append-only usage ledger and its derived totals (ADR 0021).
 *
 * <p>Two kinds of table with two different rules, kept apart on purpose. Events
 * and adjustments are facts and are only ever inserted; aggregates are a cache
 * of their sum and may be rewritten or thrown away at will. Every figure this
 * class returns can be recomputed from the first kind, which is what makes the
 * second kind safe to have at all.
 */
@Repository
public class JdbcUsageStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcUsageStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Appends a movement, ignoring one that has already been recorded.
     *
     * <p>{@code ON CONFLICT DO NOTHING} against the source pair rather than a
     * read-then-insert. Two consumers replaying the same event concurrently both
     * reach the insert, and only the unique index can decide which one is the
     * duplicate; a prior SELECT would let both through.
     *
     * @return true when the movement was recorded by this call
     */
    public boolean appendEvent(
            UUID id,
            UUID tenantId,
            String entitlementKey,
            long quantity,
            String unit,
            String periodKey,
            String sourceType,
            String sourceEventId,
            Instant occurredAt,
            Map<String, String> dimensions,
            Instant recordedAt) {

        return jdbc.sql("""
                INSERT INTO commercial.usage_events (
                    id, tenant_id, entitlement_key, quantity, unit, period_key,
                    source_type, source_event_id, occurred_at, recorded_at, dimensions)
                VALUES (
                    :id, :tenantId, :key, :quantity, :unit, :periodKey,
                    :sourceType, :sourceEventId, :occurredAt, :recordedAt, :dimensions::jsonb)
                ON CONFLICT (tenant_id, entitlement_key, source_type, source_event_id) DO NOTHING
                """)
                        .param("id", id)
                        .param("tenantId", tenantId)
                        .param("key", entitlementKey)
                        .param("quantity", quantity)
                        .param("unit", unit)
                        .param("periodKey", periodKey)
                        .param("sourceType", sourceType)
                        .param("sourceEventId", sourceEventId)
                        .param("occurredAt", utc(occurredAt))
                        .param("recordedAt", utc(recordedAt))
                        // Sorted, so two recordings of the same dimensions produce the
                        // same stored document and a byte comparison during a dispute
                        // means something.
                        .param("dimensions", objectMapper.writeValueAsString(new TreeMap<>(dimensions)))
                        .update()
                == 1;
    }

    public void insertAdjustment(
            UUID id,
            UUID tenantId,
            String entitlementKey,
            String periodKey,
            long quantityDelta,
            String reason,
            String sourceReference,
            String createdBy,
            String approvedBy,
            Instant now) {

        jdbc.sql("""
                INSERT INTO commercial.usage_adjustments (
                    id, tenant_id, entitlement_key, period_key, quantity_delta,
                    reason, source_reference, approved_by, created_by, created_at)
                VALUES (
                    :id, :tenantId, :key, :periodKey, :delta,
                    :reason, :sourceReference, :approvedBy, :createdBy, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("key", entitlementKey)
                .param("periodKey", periodKey)
                .param("delta", quantityDelta)
                .param("reason", reason)
                .param("sourceReference", sourceReference)
                .param("approvedBy", approvedBy)
                .param("createdBy", createdBy)
                .param("now", utc(now))
                .update();
    }

    /**
     * Recomputes one period's totals from the ledger.
     *
     * <p>The definition of the number. {@link #storedTotals} reads a cache of
     * this; a reconciliation compares the two; and a bug in a consumer is
     * repaired by fixing the consumer and running this, without any historical
     * row being edited.
     */
    public UsageTotals recompute(UUID tenantId, String entitlementKey, UsagePeriod period) {
        return jdbc.sql("""
                SELECT
                    (SELECT COALESCE(SUM(quantity), 0) FROM commercial.usage_events
                      WHERE tenant_id = :tenantId AND entitlement_key = :key
                        AND period_key = :periodKey) AS event_quantity,
                    (SELECT COUNT(*) FROM commercial.usage_events
                      WHERE tenant_id = :tenantId AND entitlement_key = :key
                        AND period_key = :periodKey) AS event_count,
                    (SELECT MAX(occurred_at) FROM commercial.usage_events
                      WHERE tenant_id = :tenantId AND entitlement_key = :key
                        AND period_key = :periodKey) AS last_event_at,
                    (SELECT COALESCE(SUM(quantity_delta), 0) FROM commercial.usage_adjustments
                      WHERE tenant_id = :tenantId AND entitlement_key = :key
                        AND period_key = :periodKey) AS adjustment_quantity
                """)
                .param("tenantId", tenantId)
                .param("key", entitlementKey)
                .param("periodKey", period.key())
                .query((row, number) -> new UsageTotals(
                        entitlementKey,
                        period,
                        row.getLong("event_quantity"),
                        row.getLong("adjustment_quantity"),
                        row.getInt("event_count"),
                        instant(row, "last_event_at")))
                .single();
    }

    /** Writes the recomputed totals into the cache. */
    public void storeTotals(UUID tenantId, UsageTotals totals, Instant now) {
        jdbc.sql("""
                INSERT INTO commercial.usage_aggregates (
                    tenant_id, entitlement_key, period_key, period_start, period_end,
                    event_quantity, adjustment_quantity, consumed_quantity, event_count,
                    last_event_at, calculation_version, updated_at)
                VALUES (
                    :tenantId, :key, :periodKey, :periodStart, :periodEnd,
                    :eventQuantity, :adjustmentQuantity, :consumed, :eventCount,
                    :lastEventAt, 1, :now)
                ON CONFLICT (tenant_id, entitlement_key, period_key) DO UPDATE SET
                    period_start = EXCLUDED.period_start,
                    period_end = EXCLUDED.period_end,
                    event_quantity = EXCLUDED.event_quantity,
                    adjustment_quantity = EXCLUDED.adjustment_quantity,
                    consumed_quantity = EXCLUDED.consumed_quantity,
                    event_count = EXCLUDED.event_count,
                    last_event_at = EXCLUDED.last_event_at,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("tenantId", tenantId)
                .param("key", totals.entitlementKey())
                .param("periodKey", totals.period().key())
                .param("periodStart", utc(totals.period().start()))
                .param("periodEnd", utc(totals.period().end()))
                .param("eventQuantity", totals.eventQuantity())
                .param("adjustmentQuantity", totals.adjustmentQuantity())
                .param("consumed", totals.consumed())
                .param("eventCount", totals.eventCount())
                .param("lastEventAt", utc(totals.lastEventAt()))
                .param("now", utc(now))
                .update();
    }

    public Optional<UsageTotals> storedTotals(UUID tenantId, String entitlementKey, UsagePeriod period) {
        return jdbc.sql("""
                SELECT event_quantity, adjustment_quantity, event_count, last_event_at
                  FROM commercial.usage_aggregates
                 WHERE tenant_id = :tenantId AND entitlement_key = :key AND period_key = :periodKey
                """)
                .param("tenantId", tenantId)
                .param("key", entitlementKey)
                .param("periodKey", period.key())
                .query((row, number) -> new UsageTotals(
                        entitlementKey,
                        period,
                        row.getLong("event_quantity"),
                        row.getLong("adjustment_quantity"),
                        row.getInt("event_count"),
                        instant(row, "last_event_at")))
                .optional();
    }

    /**
     * The window a cached period was computed over.
     *
     * <p>Read back rather than recomputed during a rebuild. The bounds were
     * decided in the tenant's timezone at the moment the first movement landed,
     * and a tenant that has since moved timezone must not have last March's
     * window silently redrawn underneath its invoice.
     */
    public Optional<UsagePeriod> storedWindow(UUID tenantId, String entitlementKey, String periodKey) {
        return jdbc.sql("""
                SELECT period_start, period_end FROM commercial.usage_aggregates
                 WHERE tenant_id = :tenantId AND entitlement_key = :key AND period_key = :periodKey
                """)
                .param("tenantId", tenantId)
                .param("key", entitlementKey)
                .param("periodKey", periodKey)
                // period_start and period_end are NOT NULL columns; the assertion
                // documents that constraint rather than leaving it implicit.
                .query((row, number) -> new UsagePeriod(
                        periodKey,
                        Objects.requireNonNull(instant(row, "period_start"), "period_start is NOT NULL"),
                        Objects.requireNonNull(instant(row, "period_end"), "period_end is NOT NULL")))
                .optional();
    }

    /**
     * The earliest movement in a period, which is by construction an instant
     * inside it and therefore a safe reference for recomputing its bounds.
     */
    public Optional<Instant> earliestOccurrence(UUID tenantId, String entitlementKey, String periodKey) {
        // A bare aggregate with no GROUP BY always returns exactly one row, with
        // NULL when nothing matched — mapping straight to OffsetDateTime.class
        // rather than through a custom RowMapper sidesteps the checker settling
        // the mapper's type parameter on a non-nullable type.
        OffsetDateTime earliest = jdbc.sql("""
                SELECT MIN(occurred_at) AS earliest FROM commercial.usage_events
                 WHERE tenant_id = :tenantId AND entitlement_key = :key AND period_key = :periodKey
                """)
                .param("tenantId", tenantId)
                .param("key", entitlementKey)
                .param("periodKey", periodKey)
                .query(OffsetDateTime.class)
                .single();
        return Optional.ofNullable(earliest).map(OffsetDateTime::toInstant);
    }

    /** Every cached total for a tenant, for the usage screen. */
    public List<StoredPeriodTotal> listStoredTotals(UUID tenantId) {
        return jdbc.sql("""
                SELECT entitlement_key, period_key, period_start, period_end,
                       event_quantity, adjustment_quantity, consumed_quantity, event_count,
                       last_event_at
                  FROM commercial.usage_aggregates
                 WHERE tenant_id = :tenantId
                 ORDER BY entitlement_key, period_key DESC
                """)
                .param("tenantId", tenantId)
                // period_start and period_end are NOT NULL columns; the assertion
                // documents that constraint rather than leaving it implicit.
                .query((row, number) -> new StoredPeriodTotal(
                        row.getString("entitlement_key"),
                        row.getString("period_key"),
                        Objects.requireNonNull(instant(row, "period_start"), "period_start is NOT NULL"),
                        Objects.requireNonNull(instant(row, "period_end"), "period_end is NOT NULL"),
                        row.getLong("event_quantity"),
                        row.getLong("adjustment_quantity"),
                        row.getLong("consumed_quantity"),
                        row.getInt("event_count"),
                        instant(row, "last_event_at")))
                .list();
    }

    /** The distinct keys and periods a tenant has ever recorded anything against. */
    public List<PeriodRef> recordedPeriods(UUID tenantId) {
        return jdbc.sql("""
                SELECT DISTINCT entitlement_key, period_key
                  FROM commercial.usage_events WHERE tenant_id = :tenantId
                 UNION
                SELECT DISTINCT entitlement_key, period_key
                  FROM commercial.usage_adjustments WHERE tenant_id = :tenantId
                """)
                .param("tenantId", tenantId)
                .query((row, number) -> new PeriodRef(row.getString("entitlement_key"), row.getString("period_key")))
                .list();
    }

    /** One cached total as the usage screen reads it. */
    public record StoredPeriodTotal(
            String entitlementKey,
            String periodKey,
            Instant periodStart,
            Instant periodEnd,
            long eventQuantity,
            long adjustmentQuantity,
            long consumedQuantity,
            int eventCount,
            @Nullable Instant lastEventAt) {}

    /** A key and the period partition it was recorded in. */
    public record PeriodRef(String entitlementKey, String periodKey) {}

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
