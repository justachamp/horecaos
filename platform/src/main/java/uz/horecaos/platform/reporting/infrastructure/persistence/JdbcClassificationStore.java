package uz.horecaos.platform.reporting.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.reporting.domain.ClassificationRun;
import uz.horecaos.platform.reporting.domain.ClassificationThresholds;

/**
 * Persistence for T14's classification run (7.7a/7.7b, ADR 0134) — {@code
 * reporting.classification_run} and {@code classification_result}. Its own
 * store rather than folded into {@link JdbcReportingStore}: a run is written
 * once by {@code ProductClassificationService} and by nothing else, so
 * keeping it apart from the thousand-line fact/aggregate store other waves
 * touch on every close-job change keeps a merge here narrow.
 *
 * <p>{@code location_ids} is kept as a comma-joined string of UUIDs rather
 * than a native array or JSONB column — see the migration's own comment for
 * why; {@link #joinLocationIds} and {@link #splitLocationIds} are its only
 * two readers.
 */
@Repository
public class JdbcClassificationStore {

    private final JdbcClient jdbc;

    public JdbcClassificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Per-variant quantity and gross revenue, bucketed into {@code
     * bucketDays}-wide windows starting at {@code from} — the substrate for
     * both ABC's revenue ranking (summed across buckets) and XYZ's
     * coefficient of variation (the per-bucket series itself).
     *
     * <p>Reads {@code fact_order_line} directly, no join back to {@code
     * fact_order}: unlike {@link JdbcReportingStore#readVariantSales}, ABC/XYZ
     * needs no fulfilment split, and the line fact already carries its own
     * {@code location_id}. A line with no {@code variant_id} (a free-text or
     * removed-product line) is excluded — there is no stable key to classify
     * it under across a re-run.
     */
    public List<VariantBucketRow> readVariantBuckets(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds, int bucketDays) {

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("from", from);
        params.put("to", to);
        params.put("bucketDays", bucketDays);

        String locationFilter = "";
        if (!locationIds.isEmpty()) {
            locationFilter = " AND l.location_id IN (:locations)";
            params.put("locations", locationIds);
        }

        return jdbc.sql("""
                SELECT l.variant_id, l.category_id, max(l.product_name_snapshot) AS product_name,
                       ((l.business_date - :from) / :bucketDays)::int AS bucket_index,
                       sum(l.quantity)::integer AS quantity,
                       sum(l.gross_som)::bigint AS gross_som
                  FROM reporting.fact_order_line l
                 WHERE l.tenant_id = :tenantId AND l.business_date BETWEEN :from AND :to
                   AND l.variant_id IS NOT NULL
                """ + locationFilter + """
                 GROUP BY l.variant_id, l.category_id, bucket_index
                 ORDER BY l.variant_id, bucket_index
                """)
                .params(params)
                .query((ResultSet row, int number) -> new VariantBucketRow(
                        row.getObject("variant_id", UUID.class),
                        row.getObject("category_id", UUID.class),
                        row.getString("product_name"),
                        row.getInt("bucket_index"),
                        row.getInt("quantity"),
                        row.getLong("gross_som")))
                .list();
    }

    /** One (product, bucket) aggregate — see {@link #readVariantBuckets}. */
    public record VariantBucketRow(
            UUID variantId,
            @Nullable UUID categoryId,
            String productName,
            int bucketIndex,
            int quantity,
            long grossSom) {}

    /** Persists a run header and every one of its result rows in one round of statements. */
    public void insertRun(ClassificationRun run) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", run.id());
        params.put("tenantId", run.tenantId());
        params.put("from", run.from());
        params.put("to", run.to());
        params.put("locationIds", joinLocationIds(run.locationIds()));
        params.put("metricCode", run.metricCode());
        params.put("abcA", run.thresholds().abcThresholdA());
        params.put("abcB", run.thresholds().abcThresholdB());
        params.put("xyzX", run.thresholds().xyzThresholdX());
        params.put("xyzY", run.thresholds().xyzThresholdY());
        params.put("bucketDays", run.bucketDays());
        params.put("bucketCount", run.bucketCount());
        params.put("productCount", run.rows().size());
        params.put("requestedBy", run.requestedBy());
        params.put("computedAt", utc(run.computedAt()));

        jdbc.sql("""
                INSERT INTO reporting.classification_run (
                    id, tenant_id, from_date, to_date, location_ids, metric_code,
                    abc_threshold_a_basis_points, abc_threshold_b_basis_points,
                    xyz_threshold_x_basis_points, xyz_threshold_y_basis_points,
                    bucket_days, bucket_count, product_count, requested_by, computed_at)
                VALUES (
                    :id, :tenantId, :from, :to, :locationIds, :metricCode,
                    :abcA, :abcB, :xyzX, :xyzY,
                    :bucketDays, :bucketCount, :productCount, :requestedBy, :computedAt)
                """).params(params).update();

        for (ClassificationRun.Row row : run.rows()) {
            insertResult(run.tenantId(), run.id(), row);
        }
    }

    private void insertResult(UUID tenantId, UUID runId, ClassificationRun.Row row) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("runId", runId);
        params.put("variantId", row.variantId());
        params.put("categoryId", row.categoryId());
        params.put("productName", row.productName());
        params.put("revenue", row.revenueGrossSom());
        params.put("share", row.revenueShareBasisPoints());
        params.put("cumulativeShare", row.cumulativeShareBasisPoints());
        params.put("abcClass", String.valueOf(row.abcClass()));
        params.put("quantity", row.quantityTotal());
        params.put("mean", row.meanQuantityPerBucket());
        params.put("stddev", row.stddevQuantityPerBucket());
        params.put("cv", row.coefficientOfVariationBasisPoints());
        params.put("xyzClass", String.valueOf(row.xyzClass()));

        jdbc.sql("""
                INSERT INTO reporting.classification_result (
                    tenant_id, run_id, variant_id, category_id, product_name,
                    revenue_gross_som, revenue_share_basis_points, cumulative_share_basis_points, abc_class,
                    quantity_total, mean_quantity_per_bucket, stddev_quantity_per_bucket,
                    coefficient_of_variation_basis_points, xyz_class)
                VALUES (
                    :tenantId, :runId, :variantId, :categoryId, :productName,
                    :revenue, :share, :cumulativeShare, :abcClass,
                    :quantity, :mean, :stddev, :cv, :xyzClass)
                """).params(params).update();
    }

    /**
     * The most recently computed run over exactly this window and location
     * set, so a repeat page view can show what was last computed without a
     * new {@code REPORTING_CLASSIFICATION_RUN}-gated write.
     */
    public Optional<ClassificationRun> findLatestRun(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        Map<String, Object> headerParams =
                Map.of("tenantId", tenantId, "from", from, "to", to, "locationIds", joinLocationIds(locationIds));

        Optional<RunHeader> header = jdbc.sql("""
                SELECT id, from_date, to_date, location_ids, metric_code,
                       abc_threshold_a_basis_points, abc_threshold_b_basis_points,
                       xyz_threshold_x_basis_points, xyz_threshold_y_basis_points,
                       bucket_days, bucket_count, requested_by, computed_at
                  FROM reporting.classification_run
                 WHERE tenant_id = :tenantId AND from_date = :from AND to_date = :to
                   AND location_ids = :locationIds
                 ORDER BY computed_at DESC
                 LIMIT 1
                """)
                .params(headerParams)
                .query((ResultSet row, int number) -> new RunHeader(
                        row.getObject("id", UUID.class),
                        row.getObject("from_date", LocalDate.class),
                        row.getObject("to_date", LocalDate.class),
                        row.getString("location_ids"),
                        row.getString("metric_code"),
                        row.getInt("abc_threshold_a_basis_points"),
                        row.getInt("abc_threshold_b_basis_points"),
                        row.getInt("xyz_threshold_x_basis_points"),
                        row.getInt("xyz_threshold_y_basis_points"),
                        row.getInt("bucket_days"),
                        row.getInt("bucket_count"),
                        row.getString("requested_by"),
                        requireInstant(row, "computed_at")))
                .optional();

        if (header.isEmpty()) {
            return Optional.empty();
        }
        RunHeader h = header.get();

        List<ClassificationRun.Row> rows = jdbc.sql("""
                SELECT variant_id, category_id, product_name, revenue_gross_som,
                       revenue_share_basis_points, cumulative_share_basis_points, abc_class,
                       quantity_total, mean_quantity_per_bucket, stddev_quantity_per_bucket,
                       coefficient_of_variation_basis_points, xyz_class
                  FROM reporting.classification_result
                 WHERE tenant_id = :tenantId AND run_id = :runId
                 ORDER BY cumulative_share_basis_points ASC, revenue_gross_som DESC
                """)
                .param("tenantId", tenantId)
                .param("runId", h.id())
                .query((ResultSet row, int number) -> new ClassificationRun.Row(
                        row.getObject("variant_id", UUID.class),
                        row.getObject("category_id", UUID.class),
                        row.getString("product_name"),
                        row.getLong("revenue_gross_som"),
                        row.getInt("revenue_share_basis_points"),
                        row.getInt("cumulative_share_basis_points"),
                        row.getString("abc_class").charAt(0),
                        row.getInt("quantity_total"),
                        row.getDouble("mean_quantity_per_bucket"),
                        row.getDouble("stddev_quantity_per_bucket"),
                        row.getInt("coefficient_of_variation_basis_points"),
                        row.getString("xyz_class").charAt(0)))
                .list();

        return Optional.of(new ClassificationRun(
                h.id(),
                tenantId,
                h.from(),
                h.to(),
                splitLocationIds(h.locationIds()),
                h.metricCode(),
                new ClassificationThresholds(h.abcA(), h.abcB(), h.xyzX(), h.xyzY()),
                h.bucketDays(),
                h.bucketCount(),
                h.requestedBy(),
                h.computedAt(),
                rows));
    }

    private record RunHeader(
            UUID id,
            LocalDate from,
            LocalDate to,
            String locationIds,
            String metricCode,
            int abcA,
            int abcB,
            int xyzX,
            int xyzY,
            int bucketDays,
            int bucketCount,
            String requestedBy,
            Instant computedAt) {}

    static String joinLocationIds(List<UUID> locationIds) {
        if (locationIds.isEmpty()) {
            return "";
        }
        return locationIds.stream()
                .map(UUID::toString)
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    static List<UUID> splitLocationIds(String joined) {
        if (joined.isBlank()) {
            return List.of();
        }
        return Arrays.stream(joined.split(",")).map(UUID::fromString).toList();
    }

    private static Instant requireInstant(ResultSet row, String column) {
        try {
            OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
            if (value == null) {
                throw new IllegalStateException(column + " is NOT NULL but was null");
            }
            return value.toInstant();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read " + column, exception);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
