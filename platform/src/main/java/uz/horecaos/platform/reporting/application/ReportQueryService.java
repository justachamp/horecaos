package uz.horecaos.platform.reporting.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import uz.horecaos.platform.reporting.domain.HolidayCalendar;
import uz.horecaos.platform.reporting.domain.HolidayMode;
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
                // Two DISTRIBUTION metrics, two endpoints: a share-per-bucket and a
                // share-per-payment-method breakdown are both several rows per
                // slice, but not the same rows, so each is refused by name toward
                // the endpoint that actually answers it (P39).
                case DISTRIBUTION ->
                    throw new ReportingRefusals.NonScalarMetricException(
                            metric.id().code(),
                            metric.id().code().startsWith("payment_mix")
                                    ? "GET .../reporting/payment-mix"
                                    : "GET .../reporting/sla-buckets");
                // Wave 9 w4-reports-distance-crm (7.1): delivery_distance.average.v1
                // is sourced from fact_order directly, the same physical-read
                // reasoning MEDIAN's own case above already gives.
                case AVERAGE ->
                    throw new ReportingRefusals.NonScalarMetricException(
                            metric.id().code(), "GET .../reporting/delivery-distance");
                default -> {}
            }
        }

        // T13 (7.6a): revenue.new_vs_returning.v1 is sourced from fact_order
        // directly, never agg_branch_day — a different physical read that
        // cannot share a slice with every other metric here, so it is
        // refused rather than silently dropped or silently answered alone.
        boolean anyCustomerTypeGrain =
                metrics.stream().anyMatch(metric -> metric.grain() == Grain.DAY_LOCATION_LEGAL_ENTITY_CUSTOMER_TYPE);
        if (anyCustomerTypeGrain) {
            if (metrics.size() > 1) {
                throw new ReportingRefusals.MixedCustomerTypeGrainException(
                        metrics.stream().map(metric -> metric.id().code()).toList());
            }
            return runCustomerTypeQuery(query, metrics.getFirst());
        }

        BusinessDayBoundary boundary = businessDays.boundaryFor(query.tenantId());
        refuseMixedBoundaryRegime(query.tenantId(), query.from(), query.to());

        List<BranchDayAggregate> rows = store.readAggregates(query.tenantId(), query.from(), query.to()).stream()
                .filter(row -> query.locationIds().isEmpty()
                        || query.locationIds().contains(row.key().locationId()))
                .filter(row -> query.channelCodes().isEmpty()
                        || query.channelCodes().contains(row.key().channelCode()))
                .filter(row -> query.legalEntityIds().isEmpty()
                        || query.legalEntityIds().contains(row.key().legalEntityId()))
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

    /**
     * T13 (7.6a): {@code revenue.new_vs_returning.v1} — the one {@code
     * /queries} metric sourced from {@code reporting.fact_order} directly.
     * {@link #run} branches here before touching {@code agg_branch_day} at
     * all, mirroring that method's own shape (boundary refusal, entity-total
     * refusal, slice/bucket fold) over {@link JdbcReportingStore.CustomerTypeDayAggregate}
     * instead of {@code BranchDayAggregate}.
     */
    private ReportResult runCustomerTypeQuery(ReportQuery query, MetricDefinition metric) {
        BusinessDayBoundary boundary = businessDays.boundaryFor(query.tenantId());
        refuseMixedBoundaryRegime(query.tenantId(), query.from(), query.to());

        List<JdbcReportingStore.CustomerTypeDayAggregate> rows = store.readCustomerTypeRevenue(
                query.tenantId(),
                query.from(),
                query.to(),
                query.locationIds(),
                query.legalEntityIds(),
                query.channelCodes());

        if (metric.isMoney() && !query.groupsByLegalEntity()) {
            Set<UUID> entities = new HashSet<>();
            rows.forEach(row -> entities.add(row.legalEntityId()));
            if (entities.size() > 1) {
                throw new ReportingRefusals.CombinedEntityTotalException(
                        List.of(metric.id().code()), entities.size());
            }
        }

        Map<Slice, Long> bySlice = new LinkedHashMap<>();
        for (JdbcReportingStore.CustomerTypeDayAggregate row : rows) {
            bySlice.merge(customerTypeSliceOf(query, row), row.grossSom(), Long::sum);
        }

        List<ReportRow> resultRows = new ArrayList<>(bySlice.size());
        bySlice.forEach((slice, grossSom) ->
                resultRows.add(new ReportRow(slice, Map.of(metric.id().code(), grossSom))));
        resultRows.sort(Comparator.comparing(row -> row.slice().sortKey()));

        return new ReportResult(resultRows, provenance(query.tenantId(), List.of(metric), boundary));
    }

    /**
     * T13 (7.6): the KPI-tile figures — one folded read over the whole
     * requested range (never a day-grain breakdown; a tile shows one number
     * for its period). {@code customers.ltv.v1} is not here: it is
     * registered {@code sourceAvailable = false} and this method answers
     * only the six the registry declares built.
     */
    @Transactional(readOnly = true)
    public CustomerKpiResult customerKpis(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds, List<UUID> legalEntityIds) {
        validateRange(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        JdbcReportingStore.CustomerKpiRow row = store.readCustomerKpis(tenantId, from, to, locationIds, legalEntityIds);

        // customers.value.v1 is money (ADR 0038): refused rather than folded
        // across more than one legal entity when the caller did not narrow
        // to one, the same rule /queries applies to every money metric.
        if (legalEntityIds.isEmpty() && row.legalEntityCount() > 1) {
            throw new ReportingRefusals.CombinedEntityTotalException(
                    List.of("customers.value.v1"), row.legalEntityCount());
        }

        Long repeatShareBasisPoints = row.distinctCustomers() == 0
                ? null
                : Math.round(10_000.0 * (row.distinctCustomers() - row.newCustomers()) / row.distinctCustomers());
        Double orderFrequency =
                row.distinctCustomers() == 0 ? null : (double) row.orderCount() / row.distinctCustomers();
        Long customerValueSom =
                row.distinctCustomers() == 0 ? null : Math.round((double) row.netSom() / row.distinctCustomers());
        Double basketDepth = row.orderCount() == 0 ? null : (double) row.itemCountSum() / row.orderCount();

        List<MetricDefinition> metrics = List.of(
                MetricRegistry.require("customers.new.v1"),
                MetricRegistry.require("customers.distinct.v1"),
                MetricRegistry.require("customers.repeat_share.v1"),
                MetricRegistry.require("customers.order_frequency.v1"),
                MetricRegistry.require("customers.value.v1"),
                MetricRegistry.require("customers.basket_depth.v1"));

        return new CustomerKpiResult(
                row.newCustomers(),
                row.distinctCustomers(),
                repeatShareBasisPoints,
                orderFrequency,
                customerValueSom,
                basketDepth,
                provenance(tenantId, metrics, businessDays.boundaryFor(tenantId)));
    }

    public record CustomerKpiResult(
            int newCustomers,
            int distinctCustomers,
            @Nullable Long repeatShareBasisPoints,
            @Nullable Double orderFrequency,
            @Nullable Long customerValueSom,
            @Nullable Double basketDepth,
            Provenance provenance) {}

    /** How many months {@link #customerCohorts} tracks cohort formation and retention over, in one call. */
    public static final int COHORT_WINDOW_MONTHS = 12;

    /**
     * T13 (7.6a): the cohort/retention grid. Refuses a range wider than
     * {@link #COHORT_WINDOW_MONTHS} rather than silently truncating
     * retention for the earliest cohorts in a wider request.
     */
    @Transactional(readOnly = true)
    public CohortResult customerCohorts(UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        validateRange(from, to);
        int spanMonths = (int) (ChronoUnit.MONTHS.between(from.withDayOfMonth(1), to.withDayOfMonth(1)) + 1);
        if (spanMonths > COHORT_WINDOW_MONTHS) {
            throw new ReportingRefusals.CohortRangeTooWideException(spanMonths, COHORT_WINDOW_MONTHS);
        }
        refuseMixedBoundaryRegime(tenantId, from, to);

        List<JdbcReportingStore.CohortCell> cells = store.readCustomerCohorts(tenantId, from, to, locationIds);

        Map<LocalDate, Map<LocalDate, Integer>> byCohort = new LinkedHashMap<>();
        Map<LocalDate, Integer> cohortSizes = new LinkedHashMap<>();
        for (JdbcReportingStore.CohortCell cell : cells) {
            byCohort.computeIfAbsent(cell.cohortMonth(), ignored -> new LinkedHashMap<>())
                    .put(cell.orderMonth(), cell.customerCount());
            if (cell.orderMonth().equals(cell.cohortMonth())) {
                cohortSizes.put(cell.cohortMonth(), cell.customerCount());
            }
        }

        List<Cohort> cohorts = new ArrayList<>(byCohort.size());
        byCohort.forEach((cohortMonth, byOrderMonth) -> {
            int size = cohortSizes.getOrDefault(cohortMonth, 0);
            List<RetentionPoint> points = new ArrayList<>();
            byOrderMonth.forEach((orderMonth, count) -> {
                int offset = (int) ChronoUnit.MONTHS.between(cohortMonth, orderMonth);
                Long retainedBasisPoints = size == 0 ? null : Math.round(10_000.0 * count / size);
                points.add(new RetentionPoint(offset, orderMonth, count, retainedBasisPoints));
            });
            points.sort(Comparator.comparingInt(RetentionPoint::monthOffset));
            cohorts.add(new Cohort(cohortMonth, size, points));
        });
        cohorts.sort(Comparator.comparing(Cohort::cohortMonth));

        return new CohortResult(
                cohorts, COHORT_WINDOW_MONTHS, provenance(tenantId, List.of(), businessDays.boundaryFor(tenantId)));
    }

    /**
     * One cohort's retention curve.
     *
     * @param size the cohort's own member count — {@code monthOffset = 0}'s
     *             {@code customerCount}, restated here so a caller never
     *             recomputes it from {@code points}
     */
    public record Cohort(LocalDate cohortMonth, int size, List<RetentionPoint> points) {}

    /**
     * @param retainedBasisPoints null when the cohort's size is zero —
     *                            never a zero that would read as "nobody
     *                            came back" rather than "there is no cohort"
     */
    public record RetentionPoint(
            int monthOffset,
            LocalDate orderMonth,
            int customerCount,
            @Nullable Long retainedBasisPoints) {}

    public record CohortResult(List<Cohort> cohorts, int windowMonths, Provenance provenance) {}

    /**
     * T13 (7.6b): platform-fixed Frequency bands — never tenant-configurable,
     * the same rule {@code SlaBucketSet} already applies to elapsed-time
     * buckets: an editable band would rewrite every grid already drawn and
     * nothing would record that it happened.
     */
    public enum FrequencyBand {
        F1_SINGLE,
        F2_FEW,
        F3_FREQUENT;

        static FrequencyBand of(int orderCount) {
            if (orderCount <= 1) {
                return F1_SINGLE;
            }
            return orderCount <= 3 ? F2_FEW : F3_FREQUENT;
        }
    }

    /** T13 (7.6b): platform-fixed Recency bands, in days before the range's own {@code to}. */
    public enum RecencyBand {
        R1_RECENT,
        R2_LAPSING,
        R3_AT_RISK;

        static RecencyBand of(long daysSinceLastOrder) {
            if (daysSinceLastOrder <= 6) {
                return R1_RECENT;
            }
            return daysSinceLastOrder <= 29 ? R2_LAPSING : R3_AT_RISK;
        }
    }

    /**
     * T13 (7.6b): the R×F cross-tab — cells with member counts and revenue,
     * distinct from 5.3's segment builder. Monetary is a cell value here,
     * never a third bucketed axis: the row's own brief calls this "R×F", not
     * a three-axis RFM cube.
     *
     * <p>{@code revenueSom} is money (ADR 0038): refused, like {@code customers.value.v1},
     * rather than folded across more than one legal entity when the caller did not narrow to
     * one — see the {@code readLegalEntityCount} call at the top of this method.
     *
     * @throws ReportingRefusals.CombinedEntityTotalException when {@code legalEntityIds} is
     *         empty and the read spans more than one legal entity
     */
    @Transactional(readOnly = true)
    public RfmResult customerRfm(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds, List<UUID> legalEntityIds) {
        validateRange(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        // revenueSom below is money (ADR 0038): refused rather than folded across
        // more than one legal entity when the caller did not narrow to one — the
        // same rule customerKpis applies to customers.value.v1. readCustomerRfmInputs
        // groups by customer_subject_hash alone, so the count comes from a second,
        // cheap scalar read rather than a column on CustomerRfmRow — see
        // readLegalEntityCount's own doc.
        int legalEntityCount = store.readLegalEntityCount(tenantId, from, to, locationIds, legalEntityIds);
        if (legalEntityIds.isEmpty() && legalEntityCount > 1) {
            throw new ReportingRefusals.CombinedEntityTotalException(List.of("customers.value.v1"), legalEntityCount);
        }

        List<JdbcReportingStore.CustomerRfmRow> inputs =
                store.readCustomerRfmInputs(tenantId, from, to, locationIds, legalEntityIds);

        Map<RfmCellKey, RfmAccumulator> byCell = new LinkedHashMap<>();
        for (JdbcReportingStore.CustomerRfmRow input : inputs) {
            long daysSinceLastOrder = ChronoUnit.DAYS.between(input.lastOrderDate(), to);
            RfmCellKey key = new RfmCellKey(RecencyBand.of(daysSinceLastOrder), FrequencyBand.of(input.orderCount()));
            byCell.computeIfAbsent(key, ignored -> new RfmAccumulator()).add(input.netSom());
        }

        List<RfmCell> cells = new ArrayList<>();
        for (RecencyBand recency : RecencyBand.values()) {
            for (FrequencyBand frequency : FrequencyBand.values()) {
                RfmAccumulator accumulator = byCell.get(new RfmCellKey(recency, frequency));
                cells.add(new RfmCell(
                        recency,
                        frequency,
                        accumulator == null ? 0 : accumulator.memberCount,
                        accumulator == null ? 0L : accumulator.revenueSom));
            }
        }

        return new RfmResult(cells, inputs.size(), provenance(tenantId, List.of(), businessDays.boundaryFor(tenantId)));
    }

    private record RfmCellKey(RecencyBand recency, FrequencyBand frequency) {}

    private static final class RfmAccumulator {
        private int memberCount;
        private long revenueSom;

        void add(long netSom) {
            memberCount++;
            revenueSom += netSom;
        }
    }

    /** One (Recency band, Frequency band) cell of {@link #customerRfm}. */
    public record RfmCell(RecencyBand recency, FrequencyBand frequency, int memberCount, long revenueSom) {}

    public record RfmResult(List<RfmCell> cells, int totalCustomers, Provenance provenance) {}

    /**
     * The fixed SLA distribution, which is several rows per slice rather than
     * one value — plus, wave T06 (7.3a), each branch's own {@code
     * handover_time.median.v1}: statistics.md §2.3's «Медиана» column, over
     * the same population the six buckets summarise. One extra grouped query,
     * still the one request this endpoint always was.
     */
    @Transactional(readOnly = true)
    public BranchSlaResult slaBuckets(UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        List<SlaBucketAggregate> rows = store.readSlaBuckets(tenantId, from, to).stream()
                .filter(row -> locationIds.isEmpty() || locationIds.contains(row.scopeId()))
                .toList();
        List<JdbcReportingStore.LocationMedianRow> medians =
                store.medianSecondsTotalByLocation(tenantId, from, to, locationIds);
        return new BranchSlaResult(
                rows,
                medians,
                provenance(
                        tenantId,
                        List.of(
                                MetricRegistry.require("sla_bucket_set.v1"),
                                MetricRegistry.require("handover_time.median.v1")),
                        businessDays.boundaryFor(tenantId)));
    }

    /**
     * T11 (7.4, ADR 0125): the courier leaderboard — one row per courier
     * across the range, straight off {@code reporting.fact_delivery}. Never
     * a courier's protected name: {@link JdbcReportingStore.CourierLeaderboardRow}
     * carries {@code courierId} alone, and the caller resolves display
     * through P19's reveal.
     */
    @Transactional(readOnly = true)
    public CourierLeaderboardResult courierLeaderboard(UUID tenantId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        List<JdbcReportingStore.CourierLeaderboardRow> rows = store.readCourierLeaderboard(tenantId, from, to);
        return new CourierLeaderboardResult(rows, provenance(tenantId, List.of(), businessDays.boundaryFor(tenantId)));
    }

    /**
     * Row 7.10b (wave 10 w5-reports-exports): the geography page's distance
     * histogram — {@link JdbcReportingStore#readDistanceBuckets}'s live count
     * per {@link uz.horecaos.platform.reporting.domain.DistanceBucketSet} bucket,
     * zero-filled here so a bucket with no deliveries in range is a real zero on
     * the chart rather than a missing bar.
     */
    @Transactional(readOnly = true)
    public DistanceBucketsResult distanceBuckets(UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        validateRange(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        Map<String, Integer> byCode = new LinkedHashMap<>();
        for (String code : uz.horecaos.platform.reporting.domain.DistanceBucketSet.codes()) {
            byCode.put(code, 0);
        }
        for (JdbcReportingStore.DistanceBucketRow row : store.readDistanceBuckets(tenantId, from, to, locationIds)) {
            byCode.put(row.bucketCode(), row.deliveryCount());
        }
        List<JdbcReportingStore.DistanceBucketRow> rows = byCode.entrySet().stream()
                .map(entry -> new JdbcReportingStore.DistanceBucketRow(entry.getKey(), entry.getValue()))
                .toList();
        return new DistanceBucketsResult(rows, provenance(tenantId, List.of(), businessDays.boundaryFor(tenantId)));
    }

    public record DistanceBucketsResult(List<JdbcReportingStore.DistanceBucketRow> buckets, Provenance provenance) {}

    /**
     * T11 (7.4a, ADR 0125): the {@code COURIER} scope of the fixed SLA
     * distribution — same shape {@link #slaBuckets} returns for {@code
     * LOCATION}, narrowed to the courier scope at the store layer rather
     * than filtered here, so a courier row is never accidentally mixed into
     * a location caller's read or vice versa.
     */
    @Transactional(readOnly = true)
    public SlaResult courierSlaBuckets(UUID tenantId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        return new SlaResult(
                store.readCourierSlaBuckets(tenantId, from, to),
                provenance(
                        tenantId,
                        List.of(MetricRegistry.require("sla_bucket_set.v1")),
                        businessDays.boundaryFor(tenantId)));
    }

    /**
     * T11 (7.4b, ADR 0125): the delivery-sum-by-tariff audit — see {@link
     * JdbcReportingStore#readTariffAudit}. Reads the tenant's own business
     * day boundary purely to turn the caller's date range into the instant
     * range {@code delivery_fee_resolutions.created_at} is compared against;
     * unlike every other method here this is not itself a business-day-grain
     * fact, so there is no recut frontier to refuse crossing.
     */
    @Transactional(readOnly = true)
    public TariffAuditResult tariffAudit(UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        validateRange(from, to);
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        List<JdbcReportingStore.TariffAuditRow> rows =
                store.readTariffAudit(tenantId, boundary.startOf(from), boundary.endOf(to), locationIds);
        return new TariffAuditResult(rows, provenance(tenantId, List.of(), boundary));
    }

    /**
     * T11 (7.4c, ADR 0125): per-order external-delivery cost — the one
     * courier report that finds money. See {@link
     * JdbcReportingStore#readExternalDeliveryCost} for {@code UNBILLED}'s
     * derivation and why it is never folded into {@code PENDING}. Resolves
     * the tenant's own business-day boundary to turn the caller's date range
     * into the instant range {@code shipment.delivered_at} is compared
     * against, mirroring {@link #tariffAudit} — an adversarial review
     * (2026-09-14) found this method previously passed the raw {@code
     * LocalDate} range straight to the store, which cast {@code
     * delivered_at} to a date in the database session's timezone rather than
     * the tenant's.
     */
    @Transactional(readOnly = true)
    public ExternalDeliveryCostResult externalDeliveryCost(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        validateRange(from, to);
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        List<JdbcReportingStore.ExternalDeliveryCostRow> rows =
                store.readExternalDeliveryCost(tenantId, boundary.startOf(from), boundary.endOf(to), locationIds);
        return new ExternalDeliveryCostResult(rows, provenance(tenantId, List.of(), boundary));
    }

    public record CourierLeaderboardResult(
            List<JdbcReportingStore.CourierLeaderboardRow> rows, Provenance provenance) {}

    public record TariffAuditResult(List<JdbcReportingStore.TariffAuditRow> rows, Provenance provenance) {}

    public record ExternalDeliveryCostResult(
            List<JdbcReportingStore.ExternalDeliveryCostRow> rows, Provenance provenance) {}

    /**
     * P39 (7.1c/7.3b): takings split by payment method — the cash-collection
     * control figure. Its own method rather than the typed {@link #run}: a
     * share-per-method breakdown is several rows per slice, the same reason
     * {@link #slaBuckets} above is not folded into {@code /queries}.
     *
     * <p>{@code overview} folds every branch into one row per (legal entity,
     * payment method) — never across legal entities, since this is money and
     * ADR 0038 forbids summing two taxpayers into one figure; {@code
     * byLocation} keeps the branch split so a manager can answer «7.3b»'s cash
     * reconciliation from the same read. Both come from the one grouped SQL
     * read in {@link JdbcReportingStore#readPaymentMix}, never a second query.
     *
     * <p>{@code paymentMethodCodes} narrows both {@code overview} and {@code
     * byLocation} to those methods only — empty means every method, the same
     * convention {@code locationIds} already uses.
     */
    @Transactional(readOnly = true)
    public PaymentMixResult paymentMix(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds, List<String> paymentMethodCodes) {
        validateRange(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        List<JdbcReportingStore.PaymentMixRow> rows =
                store.readPaymentMix(tenantId, from, to, locationIds, paymentMethodCodes);

        Map<OverviewKey, PaymentMixAccumulator> overview = new LinkedHashMap<>();
        for (JdbcReportingStore.PaymentMixRow row : rows) {
            overview.computeIfAbsent(
                            new OverviewKey(row.legalEntityId(), row.paymentMethodCode()),
                            key -> new PaymentMixAccumulator(row.paymentMethodCode(), row.settlesFromBalance()))
                    .add(row.tenderCount(), row.amountSom());
        }
        List<PaymentMixRow> overviewRows = new ArrayList<>(overview.size());
        overview.forEach((key, accumulator) -> overviewRows.add(accumulator.toRow(null, key.legalEntityId())));
        overviewRows.sort(Comparator.comparing(PaymentMixRow::paymentMethodCode));

        List<PaymentMixRow> byLocationRows = rows.stream()
                .map(row -> new PaymentMixRow(
                        row.locationId(),
                        row.legalEntityId(),
                        row.paymentMethodCode(),
                        row.settlesFromBalance(),
                        row.tenderCount(),
                        row.amountSom()))
                .toList();

        return new PaymentMixResult(
                overviewRows,
                byLocationRows,
                provenance(
                        tenantId,
                        List.of(MetricRegistry.require("payment_mix.amount.v1")),
                        businessDays.boundaryFor(tenantId)));
    }

    /** The median preparation time.
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
     * Wave T06 (7.3): every branch's median preparation time from one query,
     * replacing the branch leaderboard's previous one-{@link #preparationTime}
     * -call-per-branch fan-out. {@code locationIds} narrows the same way every
     * other read here does; empty means every branch the caller may read.
     *
     * <p>A branch with no order that reached READY in range is simply absent
     * from {@code rows} — see {@code JdbcReportingStore#medianSecondsToReadyByLocation}'s
     * own doc for why that is not a row carrying a null median.
     */
    @Transactional(readOnly = true)
    public LocationMedianResult preparationTimeByLocation(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        validateRange(from, to);
        List<JdbcReportingStore.LocationMedianRow> rows =
                store.medianSecondsToReadyByLocation(tenantId, from, to, locationIds);
        return new LocationMedianResult(
                rows,
                provenance(
                        tenantId,
                        List.of(MetricRegistry.require("prep_time.median.v1")),
                        businessDays.boundaryFor(tenantId)));
    }

    /**
     * Wave 11 w5-fulfillment-destination (7.3): every branch's average courier
     * transit time from one query — {@code delivery_transit_time.average.v1},
     * read straight off {@code reporting.fact_delivery}, on the same "own
     * endpoint, not /queries" footing {@link #deliveryDistance} already
     * establishes for the sibling average beside it.
     */
    @Transactional(readOnly = true)
    public LocationAverageResult averageDeliveryTimeByLocation(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        validateRange(from, to);
        List<JdbcReportingStore.LocationAverageRow> rows =
                store.averageTransitSecondsByLocation(tenantId, from, to, locationIds);
        return new LocationAverageResult(
                rows,
                provenance(
                        tenantId,
                        List.of(MetricRegistry.require("delivery_transit_time.average.v1")),
                        businessDays.boundaryFor(tenantId)));
    }

    /**
     * Wave P27 (7.1): the pickup/delivery elapsed-time tile — see {@code
     * JdbcReportingStore#medianSecondsTotalByFulfilment}'s own doc for why
     * this is a registry-and-endpoint gap over already-written data rather
     * than a new fact.
     */
    @Transactional(readOnly = true)
    public MedianResult fulfilmentTime(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds, String fulfilmentType) {
        Integer median = store.medianSecondsTotalByFulfilment(tenantId, from, to, locationIds, fulfilmentType);
        String metricCode = "DELIVERY".equals(fulfilmentType) ? "delivery_time.median.v1" : "pickup_time.median.v1";
        return new MedianResult(
                median,
                provenance(tenantId, List.of(MetricRegistry.require(metricCode)), businessDays.boundaryFor(tenantId)));
    }

    /**
     * Wave 9 w4-reports-distance-crm (7.1): the overview's distance KPI tile —
     * {@code delivery_distance.average.v1}. Its own endpoint on the same
     * footing as {@link #fulfilmentTime}: a mean over {@code fact_order}
     * directly, which the typed {@link #run} pipeline (pre-aggregated {@code
     * agg_branch_day}) cannot answer.
     */
    @Transactional(readOnly = true)
    public DistanceResult deliveryDistance(UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        validateRange(from, to);
        Integer averageMeters = store.averageDeliveryDistanceMeters(tenantId, from, to, locationIds);
        return new DistanceResult(
                averageMeters,
                provenance(
                        tenantId,
                        List.of(MetricRegistry.require("delivery_distance.average.v1")),
                        businessDays.boundaryFor(tenantId)));
    }

    /** Wave P27 (7.1a): resolves a cancellation reason code to its tenant-chosen label. */
    @Transactional(readOnly = true)
    public List<JdbcReportingStore.CancellationReasonRow> cancellationReasons(UUID tenantId) {
        return store.readCancellationReasons(tenantId);
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
        return orders(tenantId, from, to, locationIds, channelCodes, List.of(), List.of(), sort, limit, null);
    }

    /**
     * Wave P27 (7.2/7.2a): the fulfilment axis pushed into the query rather
     * than filtered client-side over an already-fetched page, a legal-entity
     * filter, and cursor paging for {@link JdbcReportingStore.OrderSort#DATE_DESC}
     * — see {@code JdbcReportingStore#readOrders}'s own doc for what the
     * cursor does on the other two sorts.
     */
    @Transactional(readOnly = true)
    public OrderListResult orders(
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            List<UUID> locationIds,
            List<String> channelCodes,
            List<String> fulfilmentTypes,
            List<UUID> legalEntityIds,
            JdbcReportingStore.OrderSort sort,
            int limit,
            JdbcReportingStore.@Nullable OrderCursor cursor) {

        validateRange(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        List<JdbcReportingStore.OrderRow> rows = store.readOrders(
                tenantId, from, to, locationIds, channelCodes, fulfilmentTypes, legalEntityIds, sort, limit, cursor);
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
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            List<UUID> locationIds,
            List<String> fulfilmentTypes,
            int limit) {
        return variantSales(
                tenantId,
                from,
                to,
                locationIds,
                fulfilmentTypes,
                JdbcReportingStore.VariantSalesSort.REVENUE_DESC,
                limit,
                null);
    }

    /**
     * Wave 10 w5-reports-exports (7.7): server-side sort and cursor paging, past the page's
     * previous hard-coded revenue order and 200-row cap.
     */
    @Transactional(readOnly = true)
    public VariantSalesResult variantSales(
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            List<UUID> locationIds,
            List<String> fulfilmentTypes,
            JdbcReportingStore.VariantSalesSort sort,
            int limit,
            JdbcReportingStore.@Nullable VariantSalesCursor cursor) {

        validateRange(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        List<JdbcReportingStore.VariantSalesRow> rows =
                store.readVariantSales(tenantId, from, to, locationIds, fulfilmentTypes, sort, limit, cursor);
        return new VariantSalesResult(
                rows, rows.size() >= limit, provenance(tenantId, List.of(), businessDays.boundaryFor(tenantId)));
    }

    /**
     * 7.5's operator leaderboard and 7.5a's receipt depth, one row per
     * operator — human or pseudo (ADR 0043; see {@code OperatorAttribution}).
     * Orders taken, revenue, average check, average handling time (seconds to
     * confirm — the closest fact this build has to time on the call), the
     * delivery/pickup/dine-in split, and a per-channel breakdown so the bot
     * and the website read beside people rather than as a footnote.
     *
     * <p>Not the typed {@link #run} pipeline: a per-operator breakdown is a
     * shape the registry's one-value-per-slice contract does not express, the
     * same reason {@link #orders} and {@link #variantSales} get their own
     * method rather than a {@code groupBy}.
     */
    @Transactional(readOnly = true)
    public OperatorLeaderboardResult operatorLeaderboard(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {

        validateRange(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        List<JdbcReportingStore.OperatorChannelRow> groups =
                store.readOperatorChannelBreakdown(tenantId, from, to, locationIds);

        Map<String, OperatorAccumulator> byOperator = new LinkedHashMap<>();
        for (JdbcReportingStore.OperatorChannelRow group : groups) {
            byOperator
                    .computeIfAbsent(group.operatorPrincipalId(), ignored -> new OperatorAccumulator())
                    .add(group);
        }

        List<OperatorLeaderboardRow> rows = new ArrayList<>(byOperator.size());
        byOperator.forEach((operatorPrincipalId, accumulator) -> rows.add(accumulator.toRow(operatorPrincipalId)));
        // Highest revenue first — the leaderboard's own reason to exist.
        rows.sort(
                Comparator.comparingLong(OperatorLeaderboardRow::netRevenueSom).reversed());

        return new OperatorLeaderboardResult(
                rows,
                provenance(
                        tenantId,
                        List.of(MetricRegistry.require("receipt_depth.v1")),
                        businessDays.boundaryFor(tenantId)));
    }

    /**
     * 7.5a: one operator's product mix — the upsell/coaching drill-down from a
     * leaderboard row. Straight off {@code fact_order_line} on the same
     * footing {@link #variantSales} already establishes, filtered to the one
     * operator.
     */
    @Transactional(readOnly = true)
    public OperatorProductResult operatorProducts(
            UUID tenantId,
            String operatorPrincipalId,
            LocalDate from,
            LocalDate to,
            List<UUID> locationIds,
            int limit) {

        validateRange(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        List<JdbcReportingStore.VariantSalesRow> rows =
                store.readOperatorProductSales(tenantId, operatorPrincipalId, from, to, locationIds, limit);
        return new OperatorProductResult(
                rows, rows.size() >= limit, provenance(tenantId, List.of(), businessDays.boundaryFor(tenantId)));
    }

    /**
     * Every terminal status in range, split by cancellation reason — the
     * funnel's drop-offs and the cancellation panel's reason breakdown from one
     * read. See {@code JdbcReportingStore#readOrderOutcomes}.
     */
    @Transactional(readOnly = true)
    public OutcomeResult orderOutcomes(
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            List<UUID> locationIds,
            List<String> channelCodes,
            List<UUID> legalEntityIds) {

        validateRange(from, to);
        refuseMixedBoundaryRegime(tenantId, from, to);

        List<JdbcReportingStore.OutcomeRow> rows =
                store.readOrderOutcomes(tenantId, from, to, locationIds, channelCodes, legalEntityIds);
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
        return demandHistory(tenantId, locationId, weekday, sampleSize, HolidayMode.INCLUDE);
    }

    /**
     * @param holidayMode 7.8b: INCLUDE (default, every qualifying date counts
     *                    fully — see the no-arg overload), EXCLUDE (a flagged
     *                    {@code tenant.public_holidays} date for the location's
     *                    country is dropped from the sample) or WEIGHT (kept,
     *                    counted at {@link HolidayAwareness#HOLIDAY_WEIGHT})
     */
    @Transactional(readOnly = true)
    public DemandHistoryResult demandHistory(
            UUID tenantId, UUID locationId, int weekday, int sampleSize, HolidayMode holidayMode) {
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        LocalDate to = LocalDate.now(clock.withZone(boundary.zone()));
        LocalDate from = to.minusDays(DEMAND_HISTORY_LOOKBACK_DAYS);
        HolidayCalendar holidays = holidayCalendarFor(tenantId);

        JdbcReportingStore.DemandSample sample = store.readDemandHistory(
                tenantId,
                locationId,
                weekday,
                from,
                to,
                boundary.zone().getId(),
                businessDayStartLiteral(boundary),
                sampleSize,
                holidayMode,
                holidays);

        Map<LocalDate, Map<Integer, Integer>> byDateThenHour = sample.byDateThenHour();

        int actualSampleSize = sample.sampleDates().size();
        List<HourDemand> hours = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            Map<LocalDate, Integer> ordersByDate = new LinkedHashMap<>();
            int total = 0;
            double weightedSum = 0;
            double weightSum = 0;
            for (LocalDate date : sample.sampleDates()) {
                // Explicitly zero, not absent: a sample date this location
                // traded on but that had nothing in this particular hour is a
                // real zero data point. Skipping it instead of counting it
                // would average only the hours that happened to have orders,
                // which overstates every quiet hour on the chart.
                int count = byDateThenHour.getOrDefault(date, Map.of()).getOrDefault(hour, 0);
                ordersByDate.put(date, count);
                total += count;
                double weight = HolidayAwareness.weightOf(date, holidayMode, sample.holidayDates());
                weightedSum += count * weight;
                weightSum += weight;
            }
            // Reduces to total / actualSampleSize whenever every weight is 1.0
            // (INCLUDE and EXCLUDE always, WEIGHT with no holiday in sample) —
            // the exact figure this method always computed before 7.8b.
            Double average = actualSampleSize >= DEMAND_HISTORY_MINIMUM_SAMPLE ? weightedSum / weightSum : null;
            hours.add(new HourDemand(hour, ordersByDate, total, average));
        }

        return new DemandHistoryResult(
                locationId,
                weekday,
                sampleSize,
                DEMAND_HISTORY_MINIMUM_SAMPLE,
                sample.sampleDates(),
                sample.holidayDates(),
                holidayMode,
                hours,
                provenance(tenantId, List.of(), boundary));
    }

    /** 7.8b: the tenant's country's {@code tenant.public_holidays} rows, or {@link HolidayCalendar#EMPTY} when the tenant has none recorded. */
    private HolidayCalendar holidayCalendarFor(UUID tenantId) {
        return store.findTenantCountryCode(tenantId)
                .map(store::readPublicHolidayRules)
                .map(HolidayCalendar::of)
                .orElse(HolidayCalendar.EMPTY);
    }

    /** {@code "HH:mm:ss"}, always with seconds, so Postgres parses it as an interval literal unambiguously. */
    static String businessDayStartLiteral(BusinessDayBoundary boundary) {
        return boundary.start().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
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
     *
     * <p>Package-private rather than {@code private}: {@link ForecastService}
     * (wave W02) draws on the same trailing sample and enforces the identical
     * gate before it will write a {@code fact_forecast} row, on purpose — a
     * forecast is not allowed to look more confident than the honest average
     * it is built from.
     */
    static final int DEMAND_HISTORY_MINIMUM_SAMPLE = 3;

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
    /**
     * @param holidayDates the subset of {@code sampleDates} a {@link
     *                     HolidayCalendar} flagged (7.8b) — always populated,
     *                     whatever {@code holidayMode} was requested; empty
     *                     under {@code EXCLUDE} because a flagged date never
     *                     reaches {@code sampleDates} in the first place
     * @param holidayMode  what was requested — echoed back so a caller never
     *                     has to remember what it asked for
     */
    public record DemandHistoryResult(
            UUID locationId,
            int weekday,
            int requestedSampleSize,
            int minimumSampleSize,
            List<LocalDate> sampleDates,
            Set<LocalDate> holidayDates,
            HolidayMode holidayMode,
            List<HourDemand> hours,
            Provenance provenance) {}

    /**
     * Wave W02: {@link ForecastService}'s most recent run for one location
     * and weekday — the seasonal-naive forecast, its confidence interval, and
     * (once available) the actual — plus a short trend of earlier runs'
     * forecast-vs-actual, the comparison 7.8's own row name asks for.
     *
     * <p>Empty when {@link ForecastScheduler} has not generated a run for
     * this (tenant, location, weekday) yet, or the last run's sample was too
     * thin to write any hour ({@code runId} is still present then — a run is
     * always recorded — but {@code hours} is empty, the identical shape
     * {@code demand-history} uses for "no history on this weekday" rather
     * than a distinct error).
     */
    @Transactional(readOnly = true)
    public DemandForecastResult demandForecast(UUID tenantId, UUID locationId, int weekday, int comparisonLimit) {
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        Optional<UUID> runId = store.findLatestForecastRunId(tenantId, locationId, weekday);
        if (runId.isEmpty()) {
            return new DemandForecastResult(
                    locationId,
                    weekday,
                    null,
                    ForecastService.MODEL_VERSION,
                    ForecastService.CONFIDENCE_LEVEL,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    provenance(tenantId, List.of(), boundary));
        }

        JdbcReportingStore.ForecastRun run = store.findForecastRun(tenantId, runId.get())
                .orElseThrow(() -> new IllegalStateException(
                        "forecast_run %s was just found by findLatestForecastRunId but is now missing"
                                .formatted(runId.get())));
        List<JdbcReportingStore.ForecastRow> hourRows = store.readForecastHours(tenantId, runId.get());
        List<JdbcReportingStore.ForecastRow> comparisonRows =
                store.readForecastComparisons(tenantId, locationId, weekday, comparisonLimit);

        return new DemandForecastResult(
                locationId,
                weekday,
                runId.get(),
                run.modelVersion(),
                run.confidenceLevel(),
                run.generatedAt(),
                hourRows.isEmpty() ? null : hourRows.get(0).businessDate(),
                hourRows.stream().map(DemandForecastHour::of).toList(),
                comparisonRows.stream().map(DemandForecastComparison::of).toList(),
                provenance(tenantId, List.of(), boundary));
    }

    /** One hour of the latest run's own forecast — see {@link #demandForecast}. */
    public record DemandForecastHour(
            int operatingHour,
            double forecastQuantity,
            double confidenceLow,
            double confidenceHigh,
            @Nullable Double actualQuantity,
            @Nullable Double absolutePercentageError) {

        static DemandForecastHour of(JdbcReportingStore.ForecastRow row) {
            return new DemandForecastHour(
                    row.operatingHour(),
                    row.forecastQuantity(),
                    row.confidenceLow(),
                    row.confidenceHigh(),
                    row.actualQuantity(),
                    row.absolutePercentageError());
        }
    }

    /** One earlier run's forecast for one business date and hour, with its actual once known — see {@link #demandForecast}. */
    public record DemandForecastComparison(
            LocalDate businessDate,
            int operatingHour,
            double forecastQuantity,
            @Nullable Double actualQuantity,
            @Nullable Double absolutePercentageError) {

        static DemandForecastComparison of(JdbcReportingStore.ForecastRow row) {
            return new DemandForecastComparison(
                    row.businessDate(),
                    row.operatingHour(),
                    row.forecastQuantity(),
                    row.actualQuantity(),
                    row.absolutePercentageError());
        }
    }

    /**
     * @param runId          null when no run has ever been generated for this
     *                       (tenant, location, weekday) — {@code hours} and
     *                       {@code comparisons} are then both empty
     * @param targetDate     the business date the latest run forecasts; null
     *                       alongside an empty {@code hours} when the run's
     *                       sample was too thin to write any
     * @param comparisons    earlier runs' forecast-vs-actual, most recent
     *                       business date first — 7.8's own "forecast vs
     *                       actual" comparison
     */
    public record DemandForecastResult(
            UUID locationId,
            int weekday,
            @Nullable UUID runId,
            int modelVersion,
            double confidenceLevel,
            @Nullable Instant generatedAt,
            @Nullable LocalDate targetDate,
            List<DemandForecastHour> hours,
            List<DemandForecastComparison> comparisons,
            Provenance provenance) {}

    /**
     * 7.8a: the latest run's department (category) or product (variant)
     * breakdown — {@code byProduct} chooses which. Empty exactly when {@link
     * #demandForecast} would report an empty {@code hours} too: no run yet,
     * or the branch-level sample was too thin for {@link ForecastService} to
     * have generated anything under it.
     */
    @Transactional(readOnly = true)
    public DemandForecastBreakdownResult demandForecastBreakdown(
            UUID tenantId, UUID locationId, int weekday, boolean byProduct) {
        Optional<UUID> runId = store.findLatestForecastRunId(tenantId, locationId, weekday);
        if (runId.isEmpty()) {
            return new DemandForecastBreakdownResult(locationId, weekday, byProduct, List.of());
        }
        List<JdbcReportingStore.ForecastRow> rows = store.readForecastBreakdown(tenantId, runId.get(), byProduct);
        return new DemandForecastBreakdownResult(
                locationId,
                weekday,
                byProduct,
                rows.stream().map(DemandForecastBreakdownRow::of).toList());
    }

    /** One department or product's forecast for one operating hour, with its actual once known — see {@link #demandForecastBreakdown}. */
    public record DemandForecastBreakdownRow(
            @Nullable UUID categoryId,
            @Nullable UUID variantId,
            @Nullable String productName,
            int operatingHour,
            double forecastQuantity,
            @Nullable Double actualQuantity,
            @Nullable Double absolutePercentageError) {

        static DemandForecastBreakdownRow of(JdbcReportingStore.ForecastRow row) {
            return new DemandForecastBreakdownRow(
                    row.categoryId(),
                    row.variantId(),
                    row.productName(),
                    row.operatingHour(),
                    row.forecastQuantity(),
                    row.actualQuantity(),
                    row.absolutePercentageError());
        }
    }

    public record DemandForecastBreakdownResult(
            UUID locationId, int weekday, boolean byProduct, List<DemandForecastBreakdownRow> rows) {}

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
                query.groupsByLegalEntity() ? row.key().legalEntityId() : null,
                null);
    }

    /** T13 (7.6a): {@link #runCustomerTypeQuery}'s own slice key — customer type replaces channel/fulfilment. */
    private static Slice customerTypeSliceOf(ReportQuery query, JdbcReportingStore.CustomerTypeDayAggregate row) {
        return new Slice(
                row.businessDate(),
                query.groupBy().contains(Grain.Dimension.LOCATION) ? row.locationId() : null,
                null,
                null,
                query.groupsByLegalEntity() ? row.legalEntityId() : null,
                query.groupBy().contains(Grain.Dimension.CUSTOMER_TYPE)
                        ? (row.isFirstOrder() ? "NEW" : "RETURNING")
                        : null);
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

    /**
     * One row's dimension values. Any of them null means "not grouped by".
     *
     * @param customerType T13 (7.6a): {@code "NEW"} or {@code "RETURNING"},
     *                     set only on a row from {@link #run}'s
     *                     customer-type-grain branch — every other caller
     *                     leaves it null, same as an ungrouped dimension.
     */
    public record Slice(
            LocalDate businessDate,
            @Nullable UUID locationId,
            @Nullable String channelCode,
            @Nullable String fulfilmentType,
            @Nullable UUID legalEntityId,
            @Nullable String customerType) {

        String sortKey() {
            return "%s|%s|%s|%s|%s|%s"
                    .formatted(businessDate, locationId, channelCode, fulfilmentType, legalEntityId, customerType);
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

    /**
     * Wave T06 (7.3a): {@link #slaBuckets}'s own result — the {@code LOCATION}
     * scope only, which is the one that carries a per-branch median. {@link
     * #courierSlaBuckets} keeps returning the plain {@link SlaResult}: the
     * courier scope (T11, 7.4a) has no median column to carry.
     */
    public record BranchSlaResult(
            List<SlaBucketAggregate> buckets,
            List<JdbcReportingStore.LocationMedianRow> medians,
            Provenance provenance) {}

    /**
     * One payment-mix row: either an {@code overview} row ({@code locationId}
     * null, folded across every branch in range) or a {@code byLocation} row
     * (branch-specific) — see {@link #paymentMix}. Never across two legal
     * entities either way.
     */
    public record PaymentMixRow(
            @Nullable UUID locationId,
            @Nullable UUID legalEntityId,
            String paymentMethodCode,
            boolean settlesFromBalance,
            int tenderCount,
            long amountSom) {}

    public record PaymentMixResult(
            List<PaymentMixRow> overview, List<PaymentMixRow> byLocation, Provenance provenance) {}

    /** {@link #paymentMix}'s overview folding key — never across legal entities (ADR 0038). */
    private record OverviewKey(@Nullable UUID legalEntityId, String paymentMethodCode) {}

    /** Accumulates one overview slice. Mutable only inside {@link #paymentMix}. */
    private static final class PaymentMixAccumulator {

        private final String paymentMethodCode;
        private final boolean settlesFromBalance;
        private int tenderCount;
        private long amountSom;

        PaymentMixAccumulator(String paymentMethodCode, boolean settlesFromBalance) {
            this.paymentMethodCode = paymentMethodCode;
            this.settlesFromBalance = settlesFromBalance;
        }

        void add(int tenderCount, long amountSom) {
            this.tenderCount += tenderCount;
            this.amountSom += amountSom;
        }

        PaymentMixRow toRow(@Nullable UUID locationId, @Nullable UUID legalEntityId) {
            return new PaymentMixRow(
                    locationId, legalEntityId, paymentMethodCode, settlesFromBalance, tenderCount, amountSom);
        }
    }

    public record MedianResult(@Nullable Integer medianSeconds, Provenance provenance) {}

    /** Wave 9 w4-reports-distance-crm (7.1): {@code delivery_distance.average.v1} — see {@link #deliveryDistance}. */
    public record DistanceResult(@Nullable Integer averageMeters, Provenance provenance) {}

    /** Wave T06 (7.3): every branch's median preparation time from one query — see {@link #preparationTimeByLocation}. */
    public record LocationMedianResult(List<JdbcReportingStore.LocationMedianRow> rows, Provenance provenance) {}

    /**
     * Wave 11 w5-fulfillment-destination (7.3): every branch's average
     * courier transit time from one query — see {@link
     * #averageDeliveryTimeByLocation}.
     */
    public record LocationAverageResult(List<JdbcReportingStore.LocationAverageRow> rows, Provenance provenance) {}

    /**
     * @param maybeMore true when the bounded read came back full — there may be
     *                  rows beyond it, not a claim that there are
     */
    public record OrderListResult(List<JdbcReportingStore.OrderRow> rows, boolean maybeMore, Provenance provenance) {}

    public record VariantSalesResult(
            List<JdbcReportingStore.VariantSalesRow> rows, boolean maybeMore, Provenance provenance) {}

    public record OutcomeResult(List<JdbcReportingStore.OutcomeRow> rows, Provenance provenance) {}

    /**
     * One operator's totals across the range — human or pseudo.
     *
     * @param operatorPrincipalId a staff subject, or {@code "channel:<code>"}
     *                            as a pseudo-operator — see {@code
     *                            OperatorAttribution}
     * @param principalKind       {@code "STAFF"} or {@code "MACHINE"} — the
     *                            only "kind" this build can say until the
     *                            staff-identity ADR lands, so a caller never
     *                            renders a bare id with no explanation
     * @param subject             the Keycloak subject for {@code STAFF}, or
     *                            the channel code for {@code MACHINE} — what a
     *                            surface prints beside {@code principalKind}
     * @param averageCheckSom     null when {@code orderCount} is zero, never a
     *                            zero-som average — the same rule {@code
     *                            average_check.v1} follows
     * @param avgHandlingSeconds  average {@code seconds_to_confirm} across
     *                            orders that recorded one; null when none did
     * @param avgItemsPerOrder    7.5a's receipt depth (registered as {@code
     *                            receipt_depth.v1}), zero when {@code
     *                            orderCount} is zero
     */
    public record OperatorLeaderboardRow(
            String operatorPrincipalId,
            String principalKind,
            String subject,
            int orderCount,
            long grossRevenueSom,
            long netRevenueSom,
            @Nullable Long averageCheckSom,
            @Nullable Integer avgHandlingSeconds,
            int deliveryCount,
            int pickupCount,
            int dineInCount,
            double avgItemsPerOrder,
            List<ChannelCount> byChannel) {}

    /** One operator's completed-order count on one channel. */
    public record ChannelCount(String channelCode, int orderCount) {}

    public record OperatorLeaderboardResult(List<OperatorLeaderboardRow> rows, Provenance provenance) {}

    public record OperatorProductResult(
            List<JdbcReportingStore.VariantSalesRow> rows, boolean maybeMore, Provenance provenance) {}

    /** Folds one operator's per-channel groups into one leaderboard row. Mutable only inside {@link #operatorLeaderboard}. */
    private static final class OperatorAccumulator {

        private int orderCount;
        private long gross;
        private long net;
        private long itemCountSum;
        private long handlingSecondsSum;
        private int handlingSecondsCount;
        private int deliveryCount;
        private int pickupCount;
        private int dineInCount;
        private final List<ChannelCount> byChannel = new ArrayList<>();

        void add(JdbcReportingStore.OperatorChannelRow group) {
            orderCount += group.orderCount();
            gross += group.grossRevenueSom();
            net += group.netRevenueSom();
            itemCountSum += group.itemCountSum();
            if (group.handlingSecondsSum() != null) {
                handlingSecondsSum += group.handlingSecondsSum();
                handlingSecondsCount += group.handlingSecondsCount();
            }
            deliveryCount += group.deliveryCount();
            pickupCount += group.pickupCount();
            dineInCount += group.dineInCount();
            byChannel.add(new ChannelCount(group.channelCode(), group.orderCount()));
        }

        OperatorLeaderboardRow toRow(String operatorPrincipalId) {
            boolean pseudo = OperatorAttribution.isPseudoOperator(operatorPrincipalId);
            return new OperatorLeaderboardRow(
                    operatorPrincipalId,
                    pseudo ? "MACHINE" : "STAFF",
                    pseudo ? OperatorAttribution.channelOf(operatorPrincipalId) : operatorPrincipalId,
                    orderCount,
                    gross,
                    net,
                    orderCount == 0 ? null : gross / orderCount,
                    handlingSecondsCount == 0
                            ? null
                            : (int) Math.round((double) handlingSecondsSum / handlingSecondsCount),
                    deliveryCount,
                    pickupCount,
                    dineInCount,
                    orderCount == 0 ? 0.0 : (double) itemCountSum / orderCount,
                    List.copyOf(byChannel));
        }
    }

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
        private int promised;
        private long deliveryFee;

        void add(BranchDayAggregate row) {
            gross += row.grossSom();
            net += row.netSom();
            refunded += row.refundedSom();
            completed += row.orderCount();
            cancelled += row.cancelledCount();
            late += row.lateCount();
            promised += row.promisedCount();
            deliveryFee += row.deliveryFeeSom();
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
                // Wave T06 (7.3): the on-time percentage's own denominator.
                case "orders.promised.v1" -> (long) promised;
                // Wave 8 w7-reports (7.2c): already part of gross above — this
                // is the fee's own total, for a report that wants both figures.
                case "delivery_fee.v1" -> deliveryFee;
                default ->
                    throw new IllegalStateException("The registry declares %s but this build cannot compute it"
                            .formatted(metric.id().code()));
            };
        }
    }
}
