package uz.horecaos.platform.reporting.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for the customer funnel and the registration counts (ADR 0167, row 7.6c):
 * {@code reporting.fact_funnel_day} and {@code reporting.fact_registration_day}.
 *
 * <p>Two rules run through every statement. No statement here touches a column that could name a
 * person, because there is none to touch -- the tables hold counts. And a {@code NULL} count
 * is "not recorded" and is read back as {@code null} with {@code getObject(name, Integer.class)};
 * {@code getInt} would answer zero for SQL NULL and turn a gap in the chart into a quiet day.
 */
@Repository
public class JdbcFunnelStore {

    private final JdbcClient jdbc;

    public JdbcFunnelStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ writes

    /**
     * Writes one funnel row, idempotent on its primary key.
     *
     * <p>A count passed as {@code null} never overwrites a count already recorded: recomputing a day
     * whose carts the sweeper has since deleted must leave yesterday's good number alone, not
     * replace it with "not recorded" (ADR 0167 Decision 3: the recut "re-derives a day while the
     * tables still hold it and otherwise leaves it as it was"). A count passed as a number replaces
     * whatever was there, which is what makes the close repeatable.
     */
    public void upsertFunnelDay(
            UUID tenantId,
            UUID brandId,
            LocalDate businessDate,
            String channelCode,
            @Nullable Integer basketStarted,
            @Nullable Integer checkoutStarted,
            @Nullable Integer ordered,
            int boundaryVersion,
            Instant computedAt) {
        jdbc.sql("""
                INSERT INTO reporting.fact_funnel_day
                    (tenant_id, brand_id, business_date, channel_code,
                     basket_started, checkout_started, ordered, boundary_version, computed_at)
                VALUES (:tenantId, :brandId, :businessDate, :channelCode,
                        :basketStarted, :checkoutStarted, :ordered, :boundaryVersion, :computedAt)
                ON CONFLICT (tenant_id, brand_id, business_date, channel_code) DO UPDATE
                   SET basket_started = COALESCE(EXCLUDED.basket_started, reporting.fact_funnel_day.basket_started),
                       checkout_started = COALESCE(EXCLUDED.checkout_started, reporting.fact_funnel_day.checkout_started),
                       ordered = COALESCE(EXCLUDED.ordered, reporting.fact_funnel_day.ordered),
                       boundary_version = EXCLUDED.boundary_version,
                       computed_at = EXCLUDED.computed_at
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("businessDate", businessDate)
                .param("channelCode", channelCode)
                .param("basketStarted", basketStarted)
                .param("checkoutStarted", checkoutStarted)
                .param("ordered", ordered)
                .param("boundaryVersion", boundaryVersion)
                .param("computedAt", utc(computedAt))
                .update();
    }

    /** One registration row, idempotent on its primary key. A recomputed number replaces the stored one. */
    public void upsertRegistrationDay(
            UUID tenantId,
            UUID brandId,
            LocalDate businessDate,
            String origin,
            int registered,
            int boundaryVersion,
            Instant computedAt) {
        jdbc.sql("""
                INSERT INTO reporting.fact_registration_day
                    (tenant_id, brand_id, business_date, origin, registered, boundary_version, computed_at)
                VALUES (:tenantId, :brandId, :businessDate, :origin, :registered, :boundaryVersion, :computedAt)
                ON CONFLICT (tenant_id, brand_id, business_date, origin) DO UPDATE
                   SET registered = EXCLUDED.registered,
                       boundary_version = EXCLUDED.boundary_version,
                       computed_at = EXCLUDED.computed_at
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("businessDate", businessDate)
                .param("origin", origin)
                .param("registered", registered)
                .param("boundaryVersion", boundaryVersion)
                .param("computedAt", utc(computedAt))
                .update();
    }

    // ------------------------------------------------- page loads (ADR 0183 D18)

    /** Adds one page load to the day's counter for a brand and channel, creating the row on the first. */
    public void addPageLoad(UUID tenantId, UUID brandId, LocalDate businessDate, String channelCode) {
        jdbc.sql("""
                INSERT INTO reporting.page_load_counts_day (tenant_id, brand_id, business_date, channel_code, page_loads)
                VALUES (:tenantId, :brandId, :businessDate, :channelCode, 1)
                ON CONFLICT (tenant_id, brand_id, business_date, channel_code)
                DO UPDATE SET page_loads = reporting.page_load_counts_day.page_loads + 1
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("businessDate", businessDate)
                .param("channelCode", channelCode)
                .update();
    }

    /** Page loads counted in {@code [from, to]}, for one brand or every brand, one channel or every channel. */
    public long readPageLoads(
            UUID tenantId, LocalDate from, LocalDate to, @Nullable UUID brandId, @Nullable String channelCode) {
        Long total = jdbc.sql("""
                SELECT coalesce(sum(page_loads), 0)
                  FROM reporting.page_load_counts_day
                 WHERE tenant_id = :tenantId
                   AND business_date >= :from AND business_date <= :to
                   AND (CAST(:brandId AS uuid) IS NULL OR brand_id = CAST(:brandId AS uuid))
                   AND (CAST(:channelCode AS varchar) IS NULL OR channel_code = CAST(:channelCode AS varchar))
                """)
                .param("tenantId", tenantId)
                .param("from", from)
                .param("to", to)
                .param("brandId", brandId)
                .param("channelCode", channelCode)
                .query(Long.class)
                .single();
        return total == null ? 0 : total;
    }

    /**
     * The first business date the counter has any row for, for one brand or every brand: before it the counter was not
     * collecting, which the funnel reports as unrecorded days and never as zero.
     */
    public java.util.Optional<LocalDate> firstPageLoadDate(UUID tenantId, @Nullable UUID brandId) {
        return jdbc.sql("""
                SELECT min(business_date) FROM reporting.page_load_counts_day
                 WHERE tenant_id = :tenantId
                   AND (CAST(:brandId AS uuid) IS NULL OR brand_id = CAST(:brandId AS uuid))
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(LocalDate.class)
                .optional();
    }

    // ------------------------------------------------------------------- reads

    public List<FunnelDay> readFunnelDays(
            UUID tenantId, LocalDate from, LocalDate to, @Nullable UUID brandId, @Nullable String channelCode) {
        return jdbc.sql("""
                SELECT business_date, brand_id, channel_code, basket_started, checkout_started, ordered
                  FROM reporting.fact_funnel_day
                 WHERE tenant_id = :tenantId
                   AND business_date >= :from AND business_date <= :to
                   AND (CAST(:brandId AS uuid) IS NULL OR brand_id = CAST(:brandId AS uuid))
                   AND (CAST(:channelCode AS varchar) IS NULL OR channel_code = CAST(:channelCode AS varchar))
                 ORDER BY business_date, brand_id, channel_code
                """)
                .param("tenantId", tenantId)
                .param("from", from)
                .param("to", to)
                .param("brandId", brandId)
                .param("channelCode", channelCode)
                .query((row, number) -> new FunnelDay(
                        row.getObject("business_date", LocalDate.class),
                        row.getObject("brand_id", UUID.class),
                        row.getString("channel_code"),
                        row.getObject("basket_started", Integer.class),
                        row.getObject("checkout_started", Integer.class),
                        row.getObject("ordered", Integer.class)))
                .list();
    }

    public List<RegistrationDay> readRegistrationDays(
            UUID tenantId, LocalDate from, LocalDate to, @Nullable UUID brandId) {
        return jdbc.sql("""
                SELECT business_date, brand_id, origin, registered
                  FROM reporting.fact_registration_day
                 WHERE tenant_id = :tenantId
                   AND business_date >= :from AND business_date <= :to
                   AND (CAST(:brandId AS uuid) IS NULL OR brand_id = CAST(:brandId AS uuid))
                 ORDER BY business_date, brand_id, origin
                """)
                .param("tenantId", tenantId)
                .param("from", from)
                .param("to", to)
                .param("brandId", brandId)
                .query((row, number) -> new RegistrationDay(
                        row.getObject("business_date", LocalDate.class),
                        row.getObject("brand_id", UUID.class),
                        row.getString("origin"),
                        row.getInt("registered")))
                .list();
    }

    /** Whether a brand belongs to a tenant. The tenant predicate is always in the query. */
    public boolean brandBelongsToTenant(UUID tenantId, UUID brandId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM tenant.brands WHERE tenant_id = :tenantId AND id = :brandId)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(Boolean.class)
                .single());
    }

    // --------------------------------------------------------------- backfill

    /** Orders in the stored facts on one business date, per brand and channel -- the backfill's "ordered". */
    public List<OrderedCount> readOrderedCounts(UUID tenantId, LocalDate businessDate) {
        return jdbc.sql("""
                SELECT brand_id, channel_code, count(*) AS ordered
                  FROM reporting.fact_order
                 WHERE tenant_id = :tenantId AND business_date = :businessDate
                 GROUP BY brand_id, channel_code
                 ORDER BY brand_id, channel_code
                """)
                .param("tenantId", tenantId)
                .param("businessDate", businessDate)
                .query((row, number) -> new OrderedCount(
                        row.getObject("brand_id", UUID.class), row.getString("channel_code"), row.getInt("ordered")))
                .list();
    }

    /** The first and last business date this tenant's close has completed, if it ever has. */
    public java.util.Optional<DateRange> closedRange(UUID tenantId) {
        MaybeRange range = jdbc.sql("""
                SELECT min(business_date) AS first_date, max(business_date) AS last_date
                  FROM reporting.close_runs
                 WHERE tenant_id = :tenantId AND run_kind = 'CLOSE' AND status = 'COMPLETED'
                """)
                .param("tenantId", tenantId)
                .query((row, number) -> new MaybeRange(
                        row.getObject("first_date", LocalDate.class), row.getObject("last_date", LocalDate.class)))
                .single();
        return range.first() == null || range.last() == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(new DateRange(range.first(), range.last()));
    }

    public boolean isBackfilled(UUID tenantId) {
        return Boolean.TRUE.equals(
                jdbc.sql("SELECT EXISTS (SELECT 1 FROM reporting.funnel_backfill_state WHERE tenant_id = :tenantId)")
                        .param("tenantId", tenantId)
                        .query(Boolean.class)
                        .single());
    }

    public void markBackfilled(UUID tenantId, LocalDate through, Instant at) {
        jdbc.sql("""
                INSERT INTO reporting.funnel_backfill_state (tenant_id, backfilled_through, updated_at)
                VALUES (:tenantId, :through, :at)
                ON CONFLICT (tenant_id) DO UPDATE
                   SET backfilled_through = EXCLUDED.backfilled_through, updated_at = EXCLUDED.updated_at
                """)
                .param("tenantId", tenantId)
                .param("through", through)
                .param("at", utc(at))
                .update();
    }

    // --------------------------------------------------------------- retention

    /** Rows older than {@code cutoff} (exclusive) across the count tables; the report-only count. */
    public long countOlderThan(LocalDate cutoff) {
        return count("reporting.fact_funnel_day", cutoff)
                + count("reporting.fact_registration_day", cutoff)
                + count("reporting.page_load_counts_day", cutoff);
    }

    private long count(String table, LocalDate cutoff) {
        Long rows = jdbc.sql("SELECT count(*) FROM " + table + " WHERE business_date < :cutoff")
                .param("cutoff", cutoff)
                .query(Long.class)
                .single();
        return rows == null ? 0 : rows;
    }

    /** Deletes rows older than {@code cutoff} (exclusive) from the count tables; returns how many. */
    public int deleteOlderThan(LocalDate cutoff) {
        return delete("reporting.fact_funnel_day", cutoff)
                + delete("reporting.fact_registration_day", cutoff)
                + delete("reporting.page_load_counts_day", cutoff);
    }

    private int delete(String table, LocalDate cutoff) {
        return jdbc.sql("DELETE FROM " + table + " WHERE business_date < :cutoff")
                .param("cutoff", cutoff)
                .update();
    }

    // ------------------------------------------------------------------ health

    /**
     * Funnel rows older than {@code unrecordedBefore} (inclusive of nothing newer) that still carry a
     * {@code NULL} basket or checkout count -- the gaps that stay gaps. A gap in the first week is
     * expected while a late close catches up; one beyond it is a day that was lost.
     */
    public long countUnrecordedRowsBefore(LocalDate unrecordedBefore, LocalDate notOlderThan) {
        Long count = jdbc.sql("""
                SELECT count(*) FROM reporting.fact_funnel_day
                 WHERE business_date < :before AND business_date >= :notOlder
                   AND (basket_started IS NULL OR checkout_started IS NULL)
                """)
                .param("before", unrecordedBefore)
                .param("notOlder", notOlderThan)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    /**
     * Business dates in {@code [from, to]} that were closed and hold orders yet have no funnel row
     * at all. A day with orders and no row is the one loss that cannot be repaired once the carts
     * are gone (ADR 0167, Observability), so it is counted by itself and not folded into the gaps above.
     */
    public long countClosedDaysWithOrdersAndNoFunnelRow(LocalDate from, LocalDate to) {
        Long count = jdbc.sql("""
                SELECT count(*) FROM (
                    SELECT DISTINCT o.tenant_id, o.business_date
                      FROM reporting.fact_order o
                     WHERE o.business_date >= :from AND o.business_date <= :to
                       AND NOT EXISTS (
                           SELECT 1 FROM reporting.fact_funnel_day f
                            WHERE f.tenant_id = o.tenant_id AND f.business_date = o.business_date)
                ) missing
                """)
                .param("from", from)
                .param("to", to)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** A stored funnel row. A {@code null} count means "not recorded". */
    public record FunnelDay(
            LocalDate businessDate,
            UUID brandId,
            String channelCode,
            @Nullable Integer basketStarted,
            @Nullable Integer checkoutStarted,
            @Nullable Integer ordered) {}

    public record RegistrationDay(LocalDate businessDate, UUID brandId, String origin, int registered) {}

    public record OrderedCount(UUID brandId, String channelCode, int ordered) {}

    public record DateRange(LocalDate first, LocalDate last) {}

    private record MaybeRange(
            @Nullable LocalDate first, @Nullable LocalDate last) {}
}
