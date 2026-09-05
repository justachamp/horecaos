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
        refuseMixedBoundaryRegime(query.tenantId(), query.from(), query.to());

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

    /**
     * Order-grain rows for 7.2's per-order tables — «Этапы», «Заказы»,
     * «Опоздания» — none of which is a day-grain slice the typed {@link #run}
     * query can answer. See {@code JdbcReportingStore#readOrders}'s doc for why
     * this is a bounded read rather than a paginated feed.
     */
    @Transactional(readOnly = true)
    public OrderListResult orders(
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            List<UUID> locationIds,
            List<String> channelCodes,
            JdbcReportingStore.OrderSort sort,
            int limit) {

        validateRange(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        List<JdbcReportingStore.OrderRow> rows =
                store.readOrders(tenantId, from, to, locationIds, channelCodes, sort, limit);
        return new OrderListResult(
                rows,
                // A full page does not prove there is no next row, but it is
                // enough to tell the console "there may be more than this bounded
                // read shows" rather than implying the list is complete.
                rows.size() >= limit,
                provenance(tenantId, List.of(), businessDays.boundaryFor(tenantId)));
    }

    /**
     * Per-variant sales for Reports 7.7's «Продажи» tab. See {@code
     * JdbcReportingStore#readVariantSales} for the delivery/pickup split.
     */
    @Transactional(readOnly = true)
    public VariantSalesResult variantSales(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds, int limit) {

        validateRange(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        List<JdbcReportingStore.VariantSalesRow> rows = store.readVariantSales(tenantId, from, to, locationIds, limit);
        return new VariantSalesResult(
                rows, rows.size() >= limit, provenance(tenantId, List.of(), businessDays.boundaryFor(tenantId)));
    }

    /**
     * Every terminal status in range, split by cancellation reason — the
     * funnel's drop-offs and the cancellation panel's reason breakdown from one
     * read. See {@code JdbcReportingStore#readOrderOutcomes}.
     */
    @Transactional(readOnly = true)
    public OutcomeResult orderOutcomes(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds, List<String> channelCodes) {

        validateRange(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        List<JdbcReportingStore.OutcomeRow> rows =
                store.readOrderOutcomes(tenantId, from, to, locationIds, channelCodes);
        return new OutcomeResult(rows, provenance(tenantId, List.of(), businessDays.boundaryFor(tenantId)));
    }

    /**
     * Reports 7.8's honest historical-average read — the owner's 2026-09-05
     * decision recorded in ADR 0043's implementation status: build the
     * same-weekday, same-hour average from real order history now, labelled as
     * exactly that, rather than the ADR's own seasonal-naive forecast model.
     *
     * <p>The average is null whenever fewer than {@link
     * #DEMAND_HISTORY_MINIMUM_SAMPLE} qualifying dates exist — a sample that
     * thin does not get to look like a confident number — and {@code
     * ordersByDate} on every {@link HourDemand} carries the raw per-date counts
     * either way, so a manager reading a thin sample sees the real numbers
     * instead of nothing.
     *
     * @param sampleSize how many of the location's most recent occurrences of
     *                   {@code weekday} to average over; the response's own
     *                   {@code sampleDates} says how many were actually found
     */
    @Transactional(readOnly = true)
    public DemandHistoryResult demandHistory(UUID tenantId, UUID locationId, int weekday, int sampleSize) {
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        LocalDate to = LocalDate.now(clock.withZone(boundary.zone()));
        LocalDate from = to.minusDays(DEMAND_HISTORY_LOOKBACK_DAYS);

        JdbcReportingStore.DemandSample sample = store.readDemandHistory(
                tenantId, locationId, weekday, from, to, boundary.zone().getId(), sampleSize);

        Map<LocalDate, Map<Integer, Integer>> byDateThenHour = new LinkedHashMap<>();
        for (LocalDate date : sample.sampleDates()) {
            byDateThenHour.put(date, new LinkedHashMap<>());
        }
        for (JdbcReportingStore.HourCount count : sample.hourCounts()) {
            byDateThenHour
                    .computeIfAbsent(count.businessDate(), ignored -> new LinkedHashMap<>())
                    .put(count.hourOfDay(), count.orderCount());
        }

        int actualSampleSize = sample.sampleDates().size();
        List<HourDemand> hours = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            Map<LocalDate, Integer> ordersByDate = new LinkedHashMap<>();
            int total = 0;
            for (LocalDate date : sample.sampleDates()) {
                // Explicitly zero, not absent: a sample date this location
                // traded on but that had nothing in this particular hour is a
                // real zero data point. Skipping it instead of counting it
                // would average only the hours that happened to have orders,
                // which overstates every quiet hour on the chart.
                int count = byDateThenHour.getOrDefault(date, Map.of()).getOrDefault(hour, 0);
                ordersByDate.put(date, count);
                total += count;
            }
            Double average =
                    actualSampleSize >= DEMAND_HISTORY_MINIMUM_SAMPLE ? (double) total / actualSampleSize : null;
            hours.add(new HourDemand(hour, ordersByDate, total, average));
        }

        return new DemandHistoryResult(
                locationId,
                weekday,
                sampleSize,
                DEMAND_HISTORY_MINIMUM_SAMPLE,
                sample.sampleDates(),
                hours,
                provenance(tenantId, List.of(), boundary));
    }

    /** How far back {@link #demandHistory} looks for qualifying dates — the same span {@link ReportQuery#MAX_DAYS} bounds a typed query to. */
    private static final int DEMAND_HISTORY_LOOKBACK_DAYS = 400;

    /**
     * Below this many qualifying dates, {@link #demandHistory} refuses to
     * publish an average at all. Three is the smallest sample where "average"
     * stops meaning "whatever the most recent week happened to do" — two
     * points is a trend line looking for an excuse, and one is just that one
     * Tuesday. Below it the caller still gets every raw count, in {@code
     * ordersByDate}, because a manager with one real week of data is better
     * served by that number than by nothing.
     */
    private static final int DEMAND_HISTORY_MINIMUM_SAMPLE = 3;

    /** One hour-of-day's demand sample — see {@link #demandHistory}. */
    public record HourDemand(
            int hourOfDay,
            Map<LocalDate, Integer> ordersByDate,
            int totalOrders,
            @Nullable Double averageOrders) {}

    /**
     * The full answer {@link #demandHistory} returns.
     *
     * @param requestedSampleSize what the caller asked for
     * @param minimumSampleSize   below this many {@code sampleDates}, every
     *                            {@code hours[].averageOrders} is null
     * @param sampleDates         the qualifying dates actually found, most
     *                            recent first — shorter than {@code
     *                            requestedSampleSize} whenever history is
     *                            thinner than asked for, empty when the
     *                            location has no history on this weekday at all
     */
    public record DemandHistoryResult(
            UUID locationId,
            int weekday,
            int requestedSampleSize,
            int minimumSampleSize,
            List<LocalDate> sampleDates,
            List<HourDemand> hours,
            Provenance provenance) {}

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

    private void refuseMixedBoundaryRegime(UUID tenantId, LocalDate from, LocalDate to) {
        Optional<LocalDate> recutThrough = businessDays.recutCompletedThrough(tenantId);
        if (recutThrough.isEmpty()) {
            return;
        }
        LocalDate frontier = recutThrough.get();
        // The range is answerable while it sits wholly on one side of the
        // frontier. It is only the crossing that mixes two regimes.
        if (!from.isAfter(frontier) && to.isAfter(frontier)) {
            throw new ReportingRefusals.MixedBoundaryRegimeException(frontier);
        }
    }

    /** Same bound {@link ReportQuery} already enforces, for the two order-grain reads below it. */
    private static void validateRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("The range ends before it starts");
        }
        if (from.plusDays(ReportQuery.MAX_DAYS).isBefore(to)) {
            throw new IllegalArgumentException("A range may cover at most " + ReportQuery.MAX_DAYS + " days");
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

    /**
     * @param maybeMore true when the bounded read came back full — there may be
     *                  rows beyond it, not a claim that there are
     */
    public record OrderListResult(List<JdbcReportingStore.OrderRow> rows, boolean maybeMore, Provenance provenance) {}

    public record VariantSalesResult(
            List<JdbcReportingStore.VariantSalesRow> rows, boolean maybeMore, Provenance provenance) {}

    public record OutcomeResult(List<JdbcReportingStore.OutcomeRow> rows, Provenance provenance) {}

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
