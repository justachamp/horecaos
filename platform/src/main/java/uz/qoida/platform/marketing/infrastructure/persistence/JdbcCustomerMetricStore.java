package uz.qoida.platform.marketing.infrastructure.persistence;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The ADR 0044 customer metric projection: how it is built, and how it is checked
 * against its own source.
 *
 * <p>The recomputation below is the only statement in the marketing module that
 * reads {@code ordering}. Everything an audience, a campaign, or a report goes
 * through touches {@code marketing} alone. That is the same division ADR 0043 draws
 * for its close job, and for the same reason: a read model whose read path reaches
 * back into the transactional schema is not a read model, it is a join with extra
 * steps and a scan on the database that is taking orders.
 *
 * <p>The projection is driven from {@code customer.brand_profiles} rather than from
 * the orders. A customer who registered and never ordered has to have a row —
 * "registered in March and has not ordered" is one of the first segments anybody
 * asks for, and driving from orders would make that segment silently empty.
 */
@Repository
public class JdbcCustomerMetricStore {

    /**
     * The order statuses that count as money the customer actually spent.
     *
     * <p>ADR 0043's registry has not settled the signed treatment of cancelled and
     * refunded orders, which is why {@code gross_spend_minor} and
     * {@code net_spend_minor} are both carried and both computed here. A registry
     * revision restates this projection; it does not reshape it.
     */
    private static final String COMPLETED = "'COMPLETED'";
    private static final String ABANDONED = "'CANCELLED', 'REJECTED', 'EXPIRED'";

    private final JdbcClient jdbc;

    public JdbcCustomerMetricStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Rebuilds every projection row for one brand from source.
     *
     * <p>Used for the backfill and for the nightly sweep. The sweep calls
     * {@link #observeDrift} first: a sweep that recomputes and overwrites in one
     * statement hides the bug that made the two disagree, and the first person to
     * notice is a merchant comparing a campaign against a report.
     *
     * @param asOf the moment recency is measured from, passed in rather than taken
     *             from {@code now()} so a backfill and its verification agree
     * @return how many rows the recomputation touched
     */
    public int recompute(UUID tenantId, UUID brandId, Instant asOf, int metricDefinitionVersion) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);
        parameters.put("asOf", utc(asOf));
        parameters.put("metricVersion", metricDefinitionVersion);

        return jdbc.sql(RECOMPUTE_SQL)
                .params(parameters)
                .update();
    }

    /**
     * Records where the stored projection disagrees with a fresh recomputation,
     * and changes nothing.
     *
     * <p>Four metrics rather than every column. These are the ones an audience
     * slider and an ADR 0043 report both show, so they are the ones a merchant can
     * catch disagreeing; a drift row for a derived column nobody displays would be
     * noise in a table that has to stay worth reading.
     *
     * @return how many disagreements were recorded
     */
    public int observeDrift(UUID tenantId, UUID brandId, Instant asOf, int metricDefinitionVersion) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);
        parameters.put("asOf", utc(asOf));
        parameters.put("metricVersion", metricDefinitionVersion);

        return jdbc.sql(DRIFT_SQL)
                .params(parameters)
                .update();
    }

    /**
     * The nightly pass: observe the drift and rebuild the projection, over one
     * aggregation of the brand's order history rather than two.
     *
     * <p>Equivalent to {@link #observeDrift} followed by {@link #recompute} — the
     * same rows are written and the same counts come back — because PostgreSQL
     * hands every sub-statement of a data-modifying {@code WITH} the same snapshot,
     * so the drift half cannot see the rebuild the other half is doing. The two
     * separate calls hold that property only by sharing a transaction and running
     * in that order, and pay for a second scan of {@code ordering.orders} to get
     * it. A brand's whole order history is not a small read, and a nightly sweep
     * across every brand on the platform did it twice per brand.
     *
     * @param asOf the moment recency is measured from, passed in rather than taken
     *             from {@code now()} so the observation and the rebuild agree
     */
    public SweepCounts sweep(UUID tenantId, UUID brandId, Instant asOf,
            int metricDefinitionVersion) {

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);
        parameters.put("asOf", utc(asOf));
        parameters.put("metricVersion", metricDefinitionVersion);

        return jdbc.sql(SWEEP_SQL)
                .params(parameters)
                .query((ResultSet row, int number) -> new SweepCounts(
                        row.getInt("metric_rows"),
                        row.getInt("drift_rows")))
                .single();
    }

    public List<DriftRow> drift(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT customer_account_id, metric_name, projected_value, recomputed_value,
                       observed_at
                  FROM marketing.metric_drift_observations
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                 ORDER BY observed_at DESC, metric_name
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((ResultSet row, int number) -> new DriftRow(
                        row.getObject("customer_account_id", UUID.class),
                        row.getString("metric_name"),
                        row.getString("projected_value"),
                        row.getString("recomputed_value"),
                        instant(row.getObject("observed_at", OffsetDateTime.class))))
                .list();
    }

    public Optional<MetricRow> find(UUID tenantId, UUID brandId, UUID accountId) {
        return jdbc.sql("""
                SELECT order_count, completed_order_count, cancelled_order_count,
                       gross_spend_minor, net_spend_minor, average_check_minor,
                       days_since_last_order, preferred_locale, birth_month_day,
                       marketing_messages_7d, marketing_messages_30d,
                       metric_definition_version, watermark_event_at
                  FROM marketing.customer_metrics
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND customer_account_id = :accountId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", accountId)
                .query((ResultSet row, int number) -> new MetricRow(
                        row.getInt("order_count"),
                        row.getInt("completed_order_count"),
                        row.getInt("cancelled_order_count"),
                        row.getLong("gross_spend_minor"),
                        row.getLong("net_spend_minor"),
                        row.getLong("average_check_minor"),
                        row.getObject("days_since_last_order", Integer.class),
                        row.getString("preferred_locale"),
                        row.getString("birth_month_day"),
                        row.getInt("marketing_messages_7d"),
                        row.getInt("marketing_messages_30d"),
                        row.getInt("metric_definition_version"),
                        instant(row.getObject("watermark_event_at", OffsetDateTime.class))))
                .optional();
    }

    /** The newest fact folded into this brand's projection, which a snapshot records. */
    public Optional<Instant> watermark(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT MAX(watermark_event_at)
                  FROM marketing.customer_metrics
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /** Refreshes the cached rolling counters from the send ledger. */
    public int refreshFrequencyCounters(UUID tenantId, UUID brandId, Instant asOf) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", tenantId);
        parameters.put("brandId", brandId);
        parameters.put("asOf", utc(asOf));

        return jdbc.sql("""
                UPDATE marketing.customer_metrics m
                   SET marketing_messages_7d = COALESCE(counted.week, 0),
                       marketing_messages_30d = COALESCE(counted.month, 0),
                       last_marketing_message_at = counted.latest,
                       updated_at = :asOf
                  FROM (SELECT s.customer_account_id,
                               COUNT(*) FILTER (
                                   WHERE s.sent_at >= CAST(:asOf AS timestamptz)
                                                      - INTERVAL '7 days') AS week,
                               COUNT(*) FILTER (
                                   WHERE s.sent_at >= CAST(:asOf AS timestamptz)
                                                      - INTERVAL '30 days') AS month,
                               MAX(s.sent_at) AS latest
                          FROM marketing.marketing_sends s
                         WHERE s.tenant_id = :tenantId AND s.brand_id = :brandId
                         GROUP BY s.customer_account_id) AS counted
                 WHERE m.tenant_id = :tenantId
                   AND m.brand_id = :brandId
                   AND m.customer_account_id = counted.customer_account_id
                """)
                .params(parameters)
                .update();
    }

    /**
     * ADR 0029 erasure: the customer's projection row goes.
     *
     * <p>Campaign counts and spend are deliberately not touched by the caller. An
     * aggregate that no longer identifies anyone is not erased; reversing a finance
     * number to honour a privacy request is a different kind of wrong.
     */
    public int erase(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                DELETE FROM marketing.customer_metrics
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .update();
    }

    /**
     * One pass over the brand's order history.
     *
     * <p>Extracted because it is the expensive half of every statement here — a
     * scan of {@code ordering.orders} for the brand, on the database that is also
     * taking orders — and it was written out twice, so a sweep paid for it twice
     * over the same rows. One definition is also one place for the registry
     * revision ADR 0043 still owes to land.
     *
     * <p>Not incremental, and the data model is why. A fold that only reads what
     * changed needs a column that moves whenever a row changes;
     * {@code ordering.orders} carries {@code created_at} and a {@code version}, and
     * an order's status moves to COMPLETED or CANCELLED long after it was created.
     * A {@code created_at} watermark would therefore miss exactly the transitions
     * that change {@code completed_order_count} and {@code net_spend_minor} — a
     * projection that drifts by design, which is the fault the drift table exists
     * to catch rather than one to introduce.
     */
    private static final String ORDER_AGGREGATE = """
            SELECT ord.customer_account_id,
                   COUNT(*) AS order_count,
                   COUNT(*) FILTER (WHERE ord.status IN (%s)) AS completed_count,
                   COUNT(*) FILTER (WHERE ord.status IN (%s)) AS cancelled_count,
                   SUM(ord.total_minor) AS gross_minor,
                   SUM(ord.total_minor) FILTER (WHERE ord.status IN (%s)) AS net_minor,
                   MIN(ord.created_at) AS first_order_at,
                   MAX(ord.created_at) AS last_order_at
              FROM ordering.orders ord
             WHERE ord.tenant_id = :tenantId
               AND ord.brand_id = :brandId
               AND ord.customer_account_id IS NOT NULL
             GROUP BY ord.customer_account_id
            """.formatted(COMPLETED, ABANDONED, COMPLETED);

    /**
     * What the projection would say if it were built right now.
     *
     * <p>Driven from {@code customer.brand_profiles}, so a customer who registered
     * and never ordered still has a row. The join to the account is inner because
     * {@code fk_brand_profile_account} makes it total; it is here rather than in the
     * caller so the registration date and locale ride along with the counts.
     */
    private static final String RECOMPUTED = """
            SELECT p.tenant_id,
                   p.brand_id,
                   p.customer_account_id,
                   a.created_at AS registered_at,
                   a.preferred_locale,
                   COALESCE(o.order_count, 0) AS order_count,
                   COALESCE(o.completed_count, 0) AS completed_count,
                   COALESCE(o.cancelled_count, 0) AS cancelled_count,
                   COALESCE(o.gross_minor, 0) AS gross_minor,
                   COALESCE(o.net_minor, 0) AS net_minor,
                   o.first_order_at,
                   o.last_order_at
              FROM customer.brand_profiles p
              JOIN customer.customer_accounts a
                ON a.id = p.customer_account_id AND a.tenant_id = p.tenant_id
              LEFT JOIN (%s) AS o
                ON o.customer_account_id = p.customer_account_id
             WHERE p.tenant_id = :tenantId
               AND p.brand_id = :brandId
            """.formatted(ORDER_AGGREGATE);

    /**
     * The upsert, given the recomputation.
     *
     * <p>{@code average_check_minor} is integer division on purpose. Money here is
     * integer minor units and for UZS a minor unit is a whole som, so an average
     * check is a whole number of som; carrying it as a double would introduce a
     * representation that a formatter somewhere would eventually divide by a
     * hundred and show a customer.
     */
    private static final String UPSERT = """
            INSERT INTO marketing.customer_metrics AS m (
                tenant_id, brand_id, customer_account_id,
                order_count, completed_order_count, cancelled_order_count,
                gross_spend_minor, net_spend_minor, average_check_minor,
                first_order_at, last_order_at, days_since_last_order,
                registered_at, preferred_locale,
                metric_definition_version, watermark_event_at, updated_at)
            SELECT r.tenant_id,
                   r.brand_id,
                   r.customer_account_id,
                   r.order_count,
                   r.completed_count,
                   r.cancelled_count,
                   r.gross_minor,
                   r.net_minor,
                   CASE WHEN r.completed_count = 0 THEN 0
                        ELSE r.net_minor / r.completed_count END,
                   r.first_order_at,
                   r.last_order_at,
                   CASE WHEN r.last_order_at IS NULL THEN NULL
                        ELSE GREATEST(0, (CAST(:asOf AS timestamptz)::date
                                          - r.last_order_at::date)) END,
                   r.registered_at,
                   r.preferred_locale,
                   :metricVersion,
                   r.last_order_at,
                   CAST(:asOf AS timestamptz)
              FROM recomputed r
            ON CONFLICT (tenant_id, brand_id, customer_account_id) DO UPDATE SET
                order_count = EXCLUDED.order_count,
                completed_order_count = EXCLUDED.completed_order_count,
                cancelled_order_count = EXCLUDED.cancelled_order_count,
                gross_spend_minor = EXCLUDED.gross_spend_minor,
                net_spend_minor = EXCLUDED.net_spend_minor,
                average_check_minor = EXCLUDED.average_check_minor,
                first_order_at = EXCLUDED.first_order_at,
                last_order_at = EXCLUDED.last_order_at,
                days_since_last_order = EXCLUDED.days_since_last_order,
                preferred_locale = EXCLUDED.preferred_locale,
                metric_definition_version = EXCLUDED.metric_definition_version,
                watermark_event_at = EXCLUDED.watermark_event_at,
                updated_at = EXCLUDED.updated_at
            """;

    /**
     * The four metrics the drift report compares, unpivoted into rows.
     *
     * <p>Four rather than every column. These are the ones an audience slider and
     * an ADR 0043 report both show, so they are the ones a merchant can catch
     * disagreeing; a drift row for a derived column nobody displays would be noise
     * in a table that has to stay worth reading. One CHECK on the observation
     * table — that the two values differ — does the filtering for all of them.
     */
    private static final String COMPARED = """
            SELECT m.customer_account_id, 'order_count' AS metric_name,
                   m.order_count::text AS projected, r.order_count::text AS recomputed
              FROM marketing.customer_metrics m
              JOIN recomputed r ON r.customer_account_id = m.customer_account_id
             WHERE m.tenant_id = :tenantId AND m.brand_id = :brandId
            UNION ALL
            SELECT m.customer_account_id, 'completed_order_count',
                   m.completed_order_count::text, r.completed_count::text
              FROM marketing.customer_metrics m
              JOIN recomputed r ON r.customer_account_id = m.customer_account_id
             WHERE m.tenant_id = :tenantId AND m.brand_id = :brandId
            UNION ALL
            SELECT m.customer_account_id, 'net_spend_minor',
                   m.net_spend_minor::text, r.net_minor::text
              FROM marketing.customer_metrics m
              JOIN recomputed r ON r.customer_account_id = m.customer_account_id
             WHERE m.tenant_id = :tenantId AND m.brand_id = :brandId
            UNION ALL
            SELECT m.customer_account_id, 'last_order_at',
                   m.last_order_at::text, r.last_order_at::text
              FROM marketing.customer_metrics m
              JOIN recomputed r ON r.customer_account_id = m.customer_account_id
             WHERE m.tenant_id = :tenantId AND m.brand_id = :brandId
            """;

    private static final String DRIFT_INSERT = """
            INSERT INTO marketing.metric_drift_observations (
                id, tenant_id, brand_id, customer_account_id, observed_at,
                metric_name, projected_value, recomputed_value, metric_definition_version)
            SELECT gen_random_uuid(), :tenantId, :brandId, customer_account_id,
                   CAST(:asOf AS timestamptz), metric_name, projected, recomputed,
                   :metricVersion
              FROM compared
             WHERE projected IS DISTINCT FROM recomputed
            """;

    private static final String RECOMPUTE_SQL =
            "WITH recomputed AS (%s)\n%s".formatted(RECOMPUTED, UPSERT);

    private static final String DRIFT_SQL =
            "WITH recomputed AS (%s),\ncompared AS (%s)\n%s"
                    .formatted(RECOMPUTED, COMPARED, DRIFT_INSERT);

    /**
     * Observe and recompute in one statement, over one aggregation.
     *
     * <p>The order the two halves are written in stops mattering here, and that is
     * the point rather than a side effect: PostgreSQL gives every sub-statement of
     * a data-modifying {@code WITH} the same snapshot, so the drift half compares
     * against the projection as it stood before this statement whichever half the
     * executor runs first. Calling {@code observeDrift} and then {@code recompute}
     * gets the same answer only because they share a transaction and run in that
     * order — and pays for the brand's order history twice to get it.
     */
    private static final String SWEEP_SQL = """
            WITH recomputed AS (%s),
            compared AS (%s),
            drifted AS (%s
                RETURNING 1),
            projected AS (%s
                RETURNING 1)
            SELECT (SELECT count(*) FROM drifted) AS drift_rows,
                   (SELECT count(*) FROM projected) AS metric_rows
            """.formatted(RECOMPUTED, COMPARED, DRIFT_INSERT, UPSERT);

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record MetricRow(int orderCount, int completedOrderCount, int cancelledOrderCount,
            long grossSpendMinor, long netSpendMinor, long averageCheckMinor,
            Integer daysSinceLastOrder, String preferredLocale, String birthMonthDay,
            int marketingMessages7d, int marketingMessages30d, int metricDefinitionVersion,
            Instant watermarkEventAt) { }

    public record DriftRow(UUID customerAccountId, String metricName, String projectedValue,
            String recomputedValue, Instant observedAt) { }

    /** What one {@link #sweep} rebuilt, and what it refused to fix. */
    public record SweepCounts(int rowsRecomputed, int driftObservations) { }
}
