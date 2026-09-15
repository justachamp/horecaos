package uz.horecaos.platform.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.reporting.domain.ClassificationRun;
import uz.horecaos.platform.reporting.domain.ClassificationThresholds;
import uz.horecaos.platform.reporting.domain.CoefficientOfVariation;
import uz.horecaos.platform.reporting.domain.MetricDefinition;
import uz.horecaos.platform.reporting.domain.MetricRegistry;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcClassificationStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;

/**
 * The genuinely-absent half of T14 (7.7a/7.7b, ADR 0134): a persisted ABC/XYZ
 * classification run.
 *
 * <p>ABC ranks by gross revenue — {@code revenue.gross.v1}, statistics.md
 * S2.7's own printed line — cumulative share against {@link
 * ClassificationThresholds#DEFAULT}'s 80/95 split. XYZ classifies by the
 * coefficient of variation of quantity sold across {@link #BUCKET_DAYS}-day
 * buckets spanning the window: the reason the run refuses a range under
 * {@link #MINIMUM_WINDOW_DAYS} days is exactly that 28 = 4 x 7, so the
 * shortest allowed window always has at least four points to vary across.
 *
 * <p>A run is a deliberate, capability-gated write — {@code
 * Capability.REPORTING_CLASSIFICATION_RUN} — not a side effect of a read. See
 * {@code reporting.classification_run}'s own migration comment for why this
 * is not a {@code fact_*} table.
 */
@Service
public class ProductClassificationService {

    /** statistics.md S2.7: "the view refuses ranges under 28 days for ABC/XYZ". */
    static final int MINIMUM_WINDOW_DAYS = 28;

    /** 28 / 7 = 4: the minimum window always yields at least four buckets to vary across. */
    static final int BUCKET_DAYS = 7;

    static final String METRIC_CODE = "revenue.gross.v1";

    private final JdbcClassificationStore classificationStore;
    private final JdbcReportingStore reportingStore;
    private final BusinessDayService businessDays;
    private final Clock clock;

    public ProductClassificationService(
            JdbcClassificationStore classificationStore,
            JdbcReportingStore reportingStore,
            BusinessDayService businessDays,
            Clock clock) {
        this.classificationStore = classificationStore;
        this.reportingStore = reportingStore;
        this.businessDays = businessDays;
        this.clock = clock;
    }

    /**
     * Computes and persists a new run. Always writes a new row, even when an
     * identical window was just run: more orders may have closed since, and
     * a disputed class-C ruling is defended by pointing at the run that was
     * live when the dispute was raised, not by a cache that might now answer
     * differently.
     */
    @Transactional
    public ClassificationRun run(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds, String requestedBy) {
        validateWindow(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        int bucketCount = bucketCountFor(from, to);
        List<JdbcClassificationStore.VariantBucketRow> buckets =
                classificationStore.readVariantBuckets(tenantId, from, to, locationIds, BUCKET_DAYS);

        Map<UUID, VariantAccumulator> byVariant = new LinkedHashMap<>();
        for (JdbcClassificationStore.VariantBucketRow bucket : buckets) {
            byVariant
                    .computeIfAbsent(bucket.variantId(), ignored -> new VariantAccumulator(bucketCount))
                    .add(bucket);
        }

        List<VariantAccumulator> ranked = new ArrayList<>(byVariant.values());
        ranked.sort(Comparator.comparingLong(VariantAccumulator::totalGrossSom)
                .reversed()
                .thenComparing(VariantAccumulator::variantId));

        long totalRevenue =
                ranked.stream().mapToLong(VariantAccumulator::totalGrossSom).sum();

        ClassificationThresholds thresholds = ClassificationThresholds.DEFAULT;
        List<ClassificationRun.Row> rows = new ArrayList<>(ranked.size());
        long cumulative = 0;
        for (VariantAccumulator accumulator : ranked) {
            cumulative += accumulator.totalGrossSom();
            int share = basisPointsOf(accumulator.totalGrossSom(), totalRevenue);
            int cumulativeShare = basisPointsOf(cumulative, totalRevenue);
            char abcClass = thresholds.abcClassOf(cumulativeShare);

            CoefficientOfVariation.SeriesStatistics stats =
                    CoefficientOfVariation.of(accumulator.bucketQuantitiesAsLongs());
            int coefficientOfVariationBasisPoints = (int) Math.round(stats.coefficientOfVariation() * 10_000.0);
            char xyzClass = thresholds.xyzClassOf(coefficientOfVariationBasisPoints);

            rows.add(new ClassificationRun.Row(
                    accumulator.variantId(),
                    accumulator.categoryId(),
                    accumulator.productName(),
                    accumulator.totalGrossSom(),
                    share,
                    cumulativeShare,
                    abcClass,
                    accumulator.totalQuantity(),
                    stats.mean(),
                    stats.populationStandardDeviation(),
                    coefficientOfVariationBasisPoints,
                    xyzClass));
        }

        ClassificationRun run = new ClassificationRun(
                Ids.newId(),
                tenantId,
                from,
                to,
                locationIds,
                METRIC_CODE,
                thresholds,
                BUCKET_DAYS,
                bucketCount,
                requestedBy,
                clock.instant(),
                rows);

        classificationStore.insertRun(run);
        return run;
    }

    /**
     * The most recently computed run over exactly this window and location
     * set, or empty when none has ever been requested — a read, {@code
     * REPORTING_READ} rather than {@code REPORTING_CLASSIFICATION_RUN}, so a
     * repeat page view does not need to hold the write capability just to
     * see what was last computed.
     */
    @Transactional(readOnly = true)
    public Optional<ClassificationRun> latest(UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        validateWindow(from, to);
        return classificationStore.findLatestRun(tenantId, from, to, locationIds);
    }

    /** Same provenance shape every other reporting response carries (ADR 0023). */
    public ReportQueryService.Provenance provenanceFor(UUID tenantId) {
        MetricDefinition metric = MetricRegistry.require(METRIC_CODE);
        Set<String> versions = new LinkedHashSet<>(List.of(metric.id().code()));
        List<String> provisional = reportingStore
                        .findStoredMetric(metric.id().name(), metric.id().version())
                        .map(JdbcReportingStore.StoredMetric::signedBy)
                        .isPresent()
                ? List.of()
                : List.of(metric.id().code());

        var boundary = businessDays.boundaryFor(tenantId);
        var latest = reportingStore.findLatestCompletedRun(tenantId);
        return new ReportQueryService.Provenance(
                clock.instant(),
                latest.map(JdbcReportingStore.CompletedRun::businessDate).orElse(null),
                latest.map(JdbcReportingStore.CompletedRun::completedAt).orElse(null),
                boundary.start().toString(),
                boundary.zone().getId(),
                boundary.version(),
                List.copyOf(versions),
                provisional,
                reportingStore.readOpenDivergences(tenantId).size());
    }

    // ------------------------------------------------------------- helpers

    private static void validateWindow(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("The range ends before it starts");
        }
        int days = daysIn(from, to);
        if (days < MINIMUM_WINDOW_DAYS) {
            throw new ReportingRefusals.RangeTooShortException(MINIMUM_WINDOW_DAYS, days);
        }
    }

    /** Same refusal {@link ReportQueryService} applies to every other read (ADR 0043). */
    private void refuseMixedBoundaryRegime(UUID tenantId, LocalDate from, LocalDate to) {
        Optional<LocalDate> recutThrough = businessDays.recutCompletedThrough(tenantId);
        if (recutThrough.isEmpty()) {
            return;
        }
        LocalDate frontier = recutThrough.get();
        if (!from.isAfter(frontier) && to.isAfter(frontier)) {
            throw new ReportingRefusals.MixedBoundaryRegimeException(frontier);
        }
    }

    private static int daysIn(LocalDate from, LocalDate to) {
        return (int) (ChronoUnit.DAYS.between(from, to) + 1);
    }

    private static int bucketCountFor(LocalDate from, LocalDate to) {
        int days = daysIn(from, to);
        return (days + BUCKET_DAYS - 1) / BUCKET_DAYS;
    }

    private static int basisPointsOf(long part, long whole) {
        return whole <= 0 ? 0 : (int) Math.round(part * 10_000.0 / whole);
    }

    /** Running totals for one variant across every bucket in the window. */
    private static final class VariantAccumulator {

        private final long[] bucketQuantities;
        private @Nullable UUID variantId;
        private @Nullable UUID categoryId;
        private @Nullable String productName;
        private long totalGrossSom;
        private int totalQuantity;

        VariantAccumulator(int bucketCount) {
            this.bucketQuantities = new long[bucketCount];
        }

        void add(JdbcClassificationStore.VariantBucketRow bucket) {
            this.variantId = bucket.variantId();
            this.categoryId = bucket.categoryId();
            this.productName = bucket.productName();
            this.totalGrossSom += bucket.grossSom();
            this.totalQuantity += bucket.quantity();
            int index = bucket.bucketIndex();
            if (index >= 0 && index < bucketQuantities.length) {
                bucketQuantities[index] += bucket.quantity();
            }
        }

        UUID variantId() {
            return Objects.requireNonNull(variantId, "accumulator was never fed a bucket");
        }

        @Nullable
        UUID categoryId() {
            return categoryId;
        }

        String productName() {
            return Objects.requireNonNull(productName, "accumulator was never fed a bucket");
        }

        long totalGrossSom() {
            return totalGrossSom;
        }

        int totalQuantity() {
            return totalQuantity;
        }

        List<Long> bucketQuantitiesAsLongs() {
            List<Long> series = new ArrayList<>(bucketQuantities.length);
            for (long quantity : bucketQuantities) {
                series.add(quantity);
            }
            return series;
        }
    }
}
