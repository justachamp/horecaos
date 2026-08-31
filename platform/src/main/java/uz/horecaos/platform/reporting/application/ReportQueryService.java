package uz.horecaos.platform.reporting.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.reporting.application.ReportingFacts.BranchDayAggregate;
import uz.horecaos.platform.reporting.application.ReportingFacts.SlaBucketAggregate;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.domain.Grain;
import uz.horecaos.platform.reporting.domain.MetricDefinition;
import uz.horecaos.platform.reporting.domain.MetricRegistry;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;

/**
 * The only way a client asks for a number (ADR 0043).
 *
 * <p>Reads {@code reporting} and nothing else. Every figure it returns is
 * composed from a registry definition; no caller supplies an aggregate, an
 * expression, or a column name.
 *
 * <p>Every answer carries its metric versions, the business-day boundary and
 * timezone it was computed under, and the instant it is current as of. ADR 0023
 * is explicit that a report which cannot state its freshness is not shipped, and
 * a tile that cannot say whether it is five minutes or five days old is a tile
 * people learn to distrust and then to ignore.
 */
@Service
public class ReportQueryService {

    private final JdbcReportingStore store;
    private final BusinessDayService businessDays;
    private final Clock clock;

    public ReportQueryService(JdbcReportingStore store, BusinessDayService businessDays, Clock clock) {
        this.store = store;
        this.businessDays = businessDays;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReportResult run(ReportQuery query) {
        List<MetricDefinition> metrics =
                query.metricCodes().stream().map(MetricRegistry::require).toList();

        for (MetricDefinition metric : metrics) {
            if (!metric.sourceAvailable()) {
                throw new ReportingRefusals.MetricNotBuiltException(
                        metric.id().code(),
                        metric.openQuestion() == null ? "its source fact does not exist" : metric.openQuestion());
            }
            switch (metric.aggregation()) {
                case MEDIAN ->
                    throw new ReportingRefusals.NonScalarMetricException(
                            metric.id().code(), "GET .../reporting/preparation-time");
                case DISTRIBUTION ->
                    throw new ReportingRefusals.NonScalarMetricException(
                            metric.id().code(), "GET .../reporting/sla-buckets");
                default -> {}
            }
        }

        BusinessDayBoundary boundary = businessDays.boundaryFor(query.tenantId());
        refuseMixedBoundaryRegime(query);

        List<BranchDayAggregate> rows = store.readAggregates(query.tenantId(), query.from(), query.to()).stream()
                .filter(row -> query.locationIds().isEmpty()
                        || query.locationIds().contains(row.key().locationId()))
                .filter(row -> query.channelCodes().isEmpty()
                        || query.channelCodes().contains(row.key().channelCode()))
                .toList();

        refuseCombinedEntityTotal(query, metrics, rows);

        Map<Slice, Bucket> byslice = new LinkedHashMap<>();
        for (BranchDayAggregate row : rows) {
            byslice.computeIfAbsent(sliceOf(query, row), ignored -> new Bucket())
                    .add(row);
        }

        List<ReportRow> resultRows = new ArrayList<>(byslice.size());
        byslice.forEach((slice, bucket) -> {
            Map<String, Long> values = new LinkedHashMap<>();
            for (MetricDefinition metric : metrics) {
                values.put(metric.id().code(), bucket.valueOf(metric));
            }
            resultRows.add(new ReportRow(slice, values));
        });
        resultRows.sort(Comparator.comparing(row -> row.slice().sortKey()));

        return new ReportResult(resultRows, provenance(query.tenantId(), metrics, boundary));
    }

    /** The fixed SLA distribution, which is several rows per slice rather than one value. */
    @Transactional(readOnly = true)
    public SlaResult slaBuckets(UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        List<SlaBucketAggregate> rows = store.readSlaBuckets(tenantId, from, to).stream()
                .filter(row -> locationIds.isEmpty() || locationIds.contains(row.scopeId()))
                .toList();
        return new SlaResult(
                rows,
                provenance(
                        tenantId,
                        List.of(MetricRegistry.require("sla_bucket_set.v1")),
                        businessDays.boundaryFor(tenantId)));
    }

    /**
     * The median preparation time.
     *
     * @return a result whose median is null when nothing reached READY in the
     *         range, which is not a zero-second kitchen
     */
    @Transactional(readOnly = true)
    public MedianResult preparationTime(UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        Integer median = store.medianSecondsToReady(tenantId, from, to, locationIds);
        return new MedianResult(
                median,
                provenance(
                        tenantId,
                        List.of(MetricRegistry.require("prep_time.median.v1")),
                        businessDays.boundaryFor(tenantId)));
    }

    /** Every definition, with whether finance has signed it. */
    @Transactional(readOnly = true)
    public List<MetricView> catalogue() {
        List<MetricView> views = new ArrayList<>();
        for (MetricDefinition definition : MetricRegistry.all()) {
            var stored = store.findStoredMetric(
                    definition.id().name(), definition.id().version());
            views.add(new MetricView(
                    definition,
                    stored.map(JdbcReportingStore.StoredMetric::signedBy).orElse(null),
                    stored.map(JdbcReportingStore.StoredMetric::signedAt).orElse(null)));
        }
        return views;
    }

    // ---------------------------------------------------- digest facts (ADR 0058)

    /** What {@code DigestScheduler} reads: one tenant's most recently closed business day, or empty if none has. */
    @Transactional(readOnly = true)
    public Optional<DigestFacts> mostRecentlyClosedDay(UUID tenantId) {
        return store.lastRunDate(tenantId, "CLOSE").map(businessDate -> {
            List<BranchDayAggregate> rows = store.readAggregates(tenantId, businessDate, businessDate);
            long completed = 0;
            long cancelled = 0;
            long gross = 0;
            long net = 0;
            long refunded = 0;
            for (BranchDayAggregate row : rows) {
                completed += row.orderCount();
                cancelled += row.cancelledCount();
                gross += row.grossSom();
                net += row.netSom();
                refunded += row.refundedSom();
            }
            boolean openDivergence = store.readOpenDivergences(tenantId).stream()
                    .anyMatch(divergence -> divergence.businessDate().equals(businessDate));
            return new DigestFacts(businessDate, completed, cancelled, gross, net, refunded, openDivergence);
        });
    }

    /** Every tenant a platform digest sums across (ADR 0058). Suspended/archived tenants stop taking orders. */
    public List<UUID> activeTenantIds() {
        return store.activeTenantIds();
    }

    // ------------------------------------------------------------- refusals

    private void refuseMixedBoundaryRegime(ReportQuery query) {
        Optional<LocalDate> recutThrough = businessDays.recutCompletedThrough(query.tenantId());
        if (recutThrough.isEmpty()) {
            return;
        }
        LocalDate frontier = recutThrough.get();
        // The range is answerable while it sits wholly on one side of the
        // frontier. It is only the crossing that mixes two regimes.
        if (!query.from().isAfter(frontier) && query.to().isAfter(frontier)) {
            throw new ReportingRefusals.MixedBoundaryRegimeException(frontier);
        }
    }

    private static void refuseCombinedEntityTotal(
            ReportQuery query, List<MetricDefinition> metrics, List<BranchDayAggregate> rows) {

        List<String> money = metrics.stream()
                .filter(MetricDefinition::isMoney)
                .map(metric -> metric.id().code())
                .toList();

        if (money.isEmpty() || query.groupsByLegalEntity()) {
            return;
        }
        Set<UUID> entities = new HashSet<>();
        rows.forEach(row -> entities.add(row.key().legalEntityId()));

        // One entity — or none recorded — sums to a figure that does reconcile,
        // so the refusal is exactly as narrow as the problem.
        if (entities.size() > 1) {
            throw new ReportingRefusals.CombinedEntityTotalException(money, entities.size());
        }
    }

    // ------------------------------------------------------------ assembly

    private static Slice sliceOf(ReportQuery query, BranchDayAggregate row) {
        return new Slice(
                row.key().businessDate(),
                query.groupBy().contains(Grain.Dimension.LOCATION) ? row.key().locationId() : null,
                query.groupBy().contains(Grain.Dimension.CHANNEL) ? row.key().channelCode() : null,
                query.groupBy().contains(Grain.Dimension.FULFILMENT_TYPE)
                        ? row.key().fulfilmentType()
                        : null,
                query.groupsByLegalEntity() ? row.key().legalEntityId() : null);
    }

    private Provenance provenance(UUID tenantId, List<MetricDefinition> metrics, BusinessDayBoundary boundary) {

        Set<String> versions = new LinkedHashSet<>();
        List<String> provisional = new ArrayList<>();
        for (MetricDefinition metric : metrics) {
            versions.add(metric.id().code());
            if (store.findStoredMetric(metric.id().name(), metric.id().version())
                    .map(JdbcReportingStore.StoredMetric::signedBy)
                    .isEmpty()) {
                provisional.add(metric.id().code());
            }
        }
        var latest = store.findLatestCompletedRun(tenantId);
        return new Provenance(
                clock.instant(),
                latest.map(JdbcReportingStore.CompletedRun::businessDate).orElse(null),
                latest.map(JdbcReportingStore.CompletedRun::completedAt).orElse(null),
                boundary.start().toString(),
                boundary.zone().getId(),
                boundary.version(),
                List.copyOf(versions),
                provisional,
                store.readOpenDivergences(tenantId).size());
    }

    /** One row's dimension values. Any of them null means "not grouped by". */
    public record Slice(
            LocalDate businessDate,
            @Nullable UUID locationId,
            @Nullable String channelCode,
            @Nullable String fulfilmentType,
            @Nullable UUID legalEntityId) {

        String sortKey() {
            return "%s|%s|%s|%s|%s".formatted(businessDate, locationId, channelCode, fulfilmentType, legalEntityId);
        }
    }

    /**
     * One row of the report: a slice, and the figures computed for it.
     *
     * @param values metric code to figure. A null value means the slice had
     *               nothing to compute the metric from — an average check with no
     *               orders — and is never rendered as zero
     */
    public record ReportRow(Slice slice, Map<String, Long> values) {}

    /**
     * What ADR 0023 requires every retained report output to declare.
     *
     * @param provisionalMetricCodes metrics finance has not signed. The console
     *                               renders these behind an amber rule rather than
     *                               presenting a provisional definition as settled
     * @param openDivergences        recuts that disagreed with a stored figure and
     *                               were left alone. A report with an open
     *                               divergence says so on its face
     */
    public record Provenance(
            Instant asOf,
            @Nullable LocalDate closedThrough,
            @Nullable Instant lastCloseCompletedAt,
            String businessDayStart,
            String timezone,
            int boundaryVersion,
            List<String> metricVersions,
            List<String> provisionalMetricCodes,
            int openDivergences) {}

    public record ReportResult(List<ReportRow> rows, Provenance provenance) {}

    public record SlaResult(List<SlaBucketAggregate> buckets, Provenance provenance) {}

    public record MedianResult(@Nullable Integer medianSeconds, Provenance provenance) {}

    /** A definition plus its signature state, which is what the metric dictionary shows. */
    public record MetricView(
            MetricDefinition definition,
            @Nullable String signedBy,
            @Nullable Instant signedAt) {

        public boolean provisional() {
            return signedBy == null;
        }
    }

    /** Accumulates one slice. Mutable only inside {@link #run}. */
    private static final class Bucket {

        private long gross;
        private long net;
        private long refunded;
        private int completed;
        private int cancelled;
        private int late;

        void add(BranchDayAggregate row) {
            gross += row.grossSom();
            net += row.netSom();
            refunded += row.refundedSom();
            completed += row.orderCount();
            cancelled += row.cancelledCount();
            late += row.lateCount();
        }

        @Nullable
        Long valueOf(MetricDefinition metric) {
            return switch (metric.id().code()) {
                case "revenue.gross.v1" -> gross;
                case "revenue.net.v1" -> net - refunded;
                // Null and not zero. A slice with no completed orders has no
                // average check, and a zero average reads as a day of free food.
                case "average_check.v1" -> completed == 0 ? null : gross / completed;
                case "orders.count.v1", "channel_mix.count.v1" -> (long) completed;
                case "orders.cancelled.v1" -> (long) cancelled;
                case "orders.late.v1" -> (long) late;
                default ->
                    throw new IllegalStateException("The registry declares %s but this build cannot compute it"
                            .formatted(metric.id().code()));
            };
        }
    }
}
