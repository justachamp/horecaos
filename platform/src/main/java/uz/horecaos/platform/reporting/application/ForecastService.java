package uz.horecaos.platform.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.domain.HolidayCalendar;
import uz.horecaos.platform.reporting.domain.HolidayMode;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.DemandSample;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.DimensionHourCount;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.ForecastFact;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.ForecastRow;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.ForecastRun;

/**
 * Wave W02: the seasonal-naive model ADR 0043's own "Forecasting" section
 * describes, scoped down from that section's own sketch — see {@code
 * V0367}'s file header for exactly what is and is not built. Draws on the
 * identical trailing sample {@link ReportQueryService#demandHistory} already
 * reads (same gate, same {@link HolidayMode}), so a forecast and the honest
 * average sitting next to it on screen are never built from two different
 * readings of the same history.
 *
 * <p><b>The model, version 1.</b> For each operating-day hour, the mean and
 * sample standard deviation of the weekday's most recent qualifying
 * occurrences — {@link #CONFIDENCE_Z} standard deviations either side of the
 * mean is the stored interval, clamped at zero because an order count cannot
 * be negative. Under {@link HolidayMode#WEIGHT} the mean is drawn from a
 * holiday-downweighted sum ({@link HolidayAwareness}); the spread is
 * deliberately still computed from the raw, unweighted counts — a documented
 * simplification, not an oversight: reweighting the spread too would need a
 * weighted-variance formula for a band width nobody has asked to be more
 * precise than "roughly how wide a typical week varies", and the wave brief's
 * two-day budget spends its precision on the mean, which is the number a
 * kitchen manager actually reads off the chart.
 *
 * <p><b>Never more confident than the average it is built from.</b> Below
 * {@link ReportQueryService#DEMAND_HISTORY_MINIMUM_SAMPLE} qualifying dates,
 * no {@code fact_forecast} row is written for that hour at all — the same
 * refusal {@code demand-history} already enforces, applied here rather than
 * invented separately.
 *
 * <p><b>7.8a's department/product breakdown</b> reuses the exact same sample
 * dates (never a second, differently-sampled read) and a plain, unweighted,
 * zero-filled average — no confidence interval at this grain in this wave;
 * {@code confidenceLow}/{@code confidenceHigh} are written equal to the mean,
 * which satisfies {@code fact_forecast}'s own {@code confidence_high >=
 * confidence_low} check honestly rather than inventing a band nobody
 * computed. Bounded to the {@link #MAX_BREAKDOWN_VARIANTS} best-selling
 * variants in the sample, the same magnitude-ordered-and-capped shape {@code
 * JdbcReportingStore#readVariantSales} already uses for the same reason: an
 * unbounded per-product write is a table that grows with the catalogue
 * forever, not with what a manager actually reads.
 */
@Service
public class ForecastService {

    /** Version 1: seasonal-naive mean and sample standard deviation. A later model bumps this rather than changing what version 1 means. */
    public static final int MODEL_VERSION = 1;

    /** Two-sided; z = 1.2816. Written down on every {@code forecast_run} row rather than assumed. */
    public static final double CONFIDENCE_LEVEL = 0.80;

    private static final double CONFIDENCE_Z = 1.2816;

    /** Matches {@code ReportQueryService#DEMAND_HISTORY_LOOKBACK_DAYS} — the same span a caller of {@code demand-history} already gets. */
    private static final int LOOKBACK_DAYS = 400;

    /** 7.8a: at most this many products get a {@code fact_forecast} row per run — see this class's own doc. */
    private static final int MAX_BREAKDOWN_VARIANTS = 20;

    private static final int MAX_BREAKDOWN_CATEGORIES = 30;

    /**
     * Standard percentage error divides by the actual, which is undefined at
     * zero — and a genuinely quiet operating hour is the normal case for most
     * hours of most days, not an edge case to special-case away. Flooring the
     * divisor at one order (never the stored {@code actual_quantity} itself,
     * only this error calculation) keeps {@code absolute_percentage_error}
     * defined for every closed hour, at the cost of understating the error's
     * size on an hour that forecast high against a true zero — a documented,
     * conservative trade-off, not a claim that the number is precise there.
     */
    private static final double MIN_ERROR_DENOMINATOR = 1.0;

    private final JdbcReportingStore store;
    private final BusinessDayService businessDays;
    private final Clock clock;

    public ForecastService(JdbcReportingStore store, BusinessDayService businessDays, Clock clock) {
        this.store = store;
        this.businessDays = businessDays;
        this.clock = clock;
    }

    /**
     * Generates one run for one location and weekday, forecasting its next
     * occurrence (today counts if today is still that weekday and has not
     * been asked for yet — a run before opening forecasts today's own
     * trading). Always writes the {@code forecast_run} row, even when the
     * sample is too thin for any hour to qualify, so the attempt itself is
     * auditable; writes no {@code fact_forecast} row for an hour below the
     * minimum sample.
     */
    @Transactional
    public GenerationResult generateForecast(
            UUID tenantId, UUID locationId, int weekday, int sampleSize, HolidayMode holidayMode) {

        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        LocalDate today = LocalDate.now(clock.withZone(boundary.zone()));
        LocalDate targetDate = today.plusDays(daysUntil(today.getDayOfWeek().getValue(), weekday));
        // History never includes today: today has not finished trading (or has
        // not started), so counting it would let a partial day contaminate the
        // sample the same way a same-day close would.
        LocalDate historyThrough = today.minusDays(1);
        LocalDate historyFrom = historyThrough.minusDays(LOOKBACK_DAYS);
        String timezone = boundary.zone().getId();
        String businessDayStart = ReportQueryService.businessDayStartLiteral(boundary);
        HolidayCalendar holidays = holidayCalendarFor(tenantId);

        DemandSample sample = store.readDemandHistory(
                tenantId,
                locationId,
                weekday,
                historyFrom,
                historyThrough,
                timezone,
                businessDayStart,
                sampleSize,
                holidayMode,
                holidays);

        UUID runId = Ids.newId();
        store.insertForecastRun(new ForecastRun(
                runId,
                tenantId,
                locationId,
                weekday,
                MODEL_VERSION,
                sampleSize,
                CONFIDENCE_LEVEL,
                holidayMode,
                clock.instant()));

        int actualSampleSize = sample.sampleDates().size();
        if (actualSampleSize < ReportQueryService.DEMAND_HISTORY_MINIMUM_SAMPLE) {
            return new GenerationResult(runId, targetDate, 0, 0);
        }

        Map<LocalDate, Map<Integer, Integer>> byDateThenHour = sample.byDateThenHour();
        int hoursWritten = 0;
        for (int hour = 0; hour < 24; hour++) {
            int hourOfDay = hour;
            HourStat stat = hourStat(
                    sample.sampleDates(),
                    sample.holidayDates(),
                    holidayMode,
                    date -> byDateThenHour.getOrDefault(date, Map.of()).getOrDefault(hourOfDay, 0));
            store.insertForecastFact(new ForecastFact(
                    Ids.newId(),
                    runId,
                    tenantId,
                    locationId,
                    targetDate,
                    hour,
                    null,
                    null,
                    null,
                    stat.mean(),
                    stat.low(),
                    stat.high(),
                    actualSampleSize));
            hoursWritten++;
        }

        int breakdownRows = generateBreakdown(
                tenantId, locationId, runId, targetDate, actualSampleSize, sample, timezone, businessDayStart);

        return new GenerationResult(runId, targetDate, hoursWritten, breakdownRows);
    }

    /** 7.8a: department (category) and product (variant) rows, same sample dates, plain zero-filled average, no per-row CI (see class doc). */
    private int generateBreakdown(
            UUID tenantId,
            UUID locationId,
            UUID runId,
            LocalDate targetDate,
            int actualSampleSize,
            DemandSample sample,
            String timezone,
            String businessDayStart) {

        List<DimensionHourCount> lines =
                store.readLineDemandByDimension(tenantId, locationId, sample.sampleDates(), timezone, businessDayStart);
        if (lines.isEmpty()) {
            return 0;
        }

        // date -> hour -> quantity, per category and per variant, zero-filled
        // the same way the branch-level read already is.
        Map<UUID, Map<LocalDate, Map<Integer, Integer>>> byCategory = new LinkedHashMap<>();
        Map<UUID, Map<LocalDate, Map<Integer, Integer>>> byVariant = new LinkedHashMap<>();
        Map<UUID, String> productNames = new HashMap<>();
        Map<UUID, Long> totalByVariant = new HashMap<>();

        for (DimensionHourCount row : lines) {
            if (row.categoryId() != null) {
                byCategory
                        .computeIfAbsent(row.categoryId(), ignored -> new HashMap<>())
                        .computeIfAbsent(row.businessDate(), ignored -> new HashMap<>())
                        .merge(row.hourOfDay(), row.quantity(), Integer::sum);
            }
            if (row.variantId() != null) {
                byVariant
                        .computeIfAbsent(row.variantId(), ignored -> new HashMap<>())
                        .computeIfAbsent(row.businessDate(), ignored -> new HashMap<>())
                        .merge(row.hourOfDay(), row.quantity(), Integer::sum);
                if (row.productName() != null) {
                    productNames.put(row.variantId(), row.productName());
                }
                totalByVariant.merge(row.variantId(), (long) row.quantity(), Long::sum);
            }
        }

        int written = 0;
        written += writeDimensionRows(
                tenantId,
                locationId,
                runId,
                targetDate,
                actualSampleSize,
                sample.sampleDates(),
                capByVolume(
                        byCategory.keySet(),
                        id -> volumeOf(byCategory.getOrDefault(id, Map.of())),
                        MAX_BREAKDOWN_CATEGORIES),
                byCategory,
                true,
                productNames);
        written += writeDimensionRows(
                tenantId,
                locationId,
                runId,
                targetDate,
                actualSampleSize,
                sample.sampleDates(),
                capByVolume(byVariant.keySet(), id -> totalByVariant.getOrDefault(id, 0L), MAX_BREAKDOWN_VARIANTS),
                byVariant,
                false,
                productNames);
        return written;
    }

    /**
     * Writes one {@code fact_forecast} row per (id, hour) — {@code isCategory}
     * says whether {@code id} fills the row's {@code category_id} or its
     * {@code variant_id}, the two department/product grains {@code
     * V0367}'s partial unique indexes define. No per-row CI at this grain
     * (see class doc): {@code confidenceLow}/{@code confidenceHigh} are the
     * mean itself.
     */
    private int writeDimensionRows(
            UUID tenantId,
            UUID locationId,
            UUID runId,
            LocalDate targetDate,
            int actualSampleSize,
            List<LocalDate> sampleDates,
            List<UUID> ids,
            Map<UUID, Map<LocalDate, Map<Integer, Integer>>> byIdThenDateThenHour,
            boolean isCategory,
            Map<UUID, String> productNames) {

        int written = 0;
        for (UUID id : ids) {
            Map<LocalDate, Map<Integer, Integer>> byDateThenHour = byIdThenDateThenHour.getOrDefault(id, Map.of());
            for (int hour = 0; hour < 24; hour++) {
                int total = 0;
                for (LocalDate date : sampleDates) {
                    total += byDateThenHour.getOrDefault(date, Map.of()).getOrDefault(hour, 0);
                }
                double mean = (double) total / actualSampleSize;
                store.insertForecastFact(new ForecastFact(
                        Ids.newId(),
                        runId,
                        tenantId,
                        locationId,
                        targetDate,
                        hour,
                        isCategory ? id : null,
                        isCategory ? null : id,
                        isCategory ? null : productNames.get(id),
                        mean,
                        mean,
                        mean,
                        actualSampleSize));
                written++;
            }
        }
        return written;
    }

    private static List<UUID> capByVolume(Set<UUID> ids, Function<UUID, Long> volumeOf, int max) {
        return ids.stream()
                .sorted(Comparator.comparing(volumeOf).reversed())
                .limit(max)
                .toList();
    }

    private static long volumeOf(Map<LocalDate, Map<Integer, Integer>> byDateThenHour) {
        return byDateThenHour.values().stream()
                .flatMap(hours -> hours.values().stream())
                .mapToLong(Integer::longValue)
                .sum();
    }

    /**
     * Backfills every {@code fact_forecast} row still waiting on an actual for
     * one tenant's business date — called once that date's business day has
     * closed (a {@code DayCloseService} caller, {@link ForecastScheduler}).
     *
     * @return how many rows were backfilled
     */
    @Transactional
    public int backfillActuals(UUID tenantId, LocalDate businessDate) {
        List<ForecastRow> pending = store.findPendingForecastRows(tenantId, businessDate);
        if (pending.isEmpty()) {
            return 0;
        }
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        String timezone = boundary.zone().getId();
        String businessDayStart = ReportQueryService.businessDayStartLiteral(boundary);

        Map<UUID, List<ForecastRow>> byLocation = new LinkedHashMap<>();
        for (ForecastRow row : pending) {
            byLocation
                    .computeIfAbsent(row.locationId(), ignored -> new ArrayList<>())
                    .add(row);
        }

        int updated = 0;
        for (Map.Entry<UUID, List<ForecastRow>> entry : byLocation.entrySet()) {
            UUID locationId = entry.getKey();
            List<JdbcReportingStore.HourCount> branchActuals =
                    store.readActualHourCounts(tenantId, locationId, businessDate, timezone, businessDayStart);
            Map<Integer, Integer> branchByHour = new HashMap<>();
            for (JdbcReportingStore.HourCount count : branchActuals) {
                branchByHour.merge(count.hourOfDay(), count.orderCount(), Integer::sum);
            }

            List<DimensionHourCount> lineActuals = store.readLineDemandByDimension(
                    tenantId, locationId, List.of(businessDate), timezone, businessDayStart);
            Map<UUID, Map<Integer, Integer>> categoryByHour = new HashMap<>();
            Map<UUID, Map<Integer, Integer>> variantByHour = new HashMap<>();
            for (DimensionHourCount row : lineActuals) {
                if (row.categoryId() != null) {
                    categoryByHour
                            .computeIfAbsent(row.categoryId(), ignored -> new HashMap<>())
                            .merge(row.hourOfDay(), row.quantity(), Integer::sum);
                }
                if (row.variantId() != null) {
                    variantByHour
                            .computeIfAbsent(row.variantId(), ignored -> new HashMap<>())
                            .merge(row.hourOfDay(), row.quantity(), Integer::sum);
                }
            }

            for (ForecastRow row : entry.getValue()) {
                int actual;
                if (row.variantId() != null) {
                    actual = variantByHour
                            .getOrDefault(row.variantId(), Map.of())
                            .getOrDefault(row.operatingHour(), 0);
                } else if (row.categoryId() != null) {
                    actual = categoryByHour
                            .getOrDefault(row.categoryId(), Map.of())
                            .getOrDefault(row.operatingHour(), 0);
                } else {
                    actual = branchByHour.getOrDefault(row.operatingHour(), 0);
                }
                double ape = Math.abs(row.forecastQuantity() - actual) / Math.max(actual, MIN_ERROR_DENOMINATOR);
                store.updateForecastActual(tenantId, row.id(), actual, ape);
                updated++;
            }
        }
        return updated;
    }

    /** Days from {@code fromWeekday} to the next (or same) occurrence of {@code targetWeekday}, both ISO-8601 (1 = Monday). Zero when they match. */
    private static int daysUntil(int fromWeekday, int targetWeekday) {
        return Math.floorMod(targetWeekday - fromWeekday, 7);
    }

    private HolidayCalendar holidayCalendarFor(UUID tenantId) {
        return store.findTenantCountryCode(tenantId)
                .map(store::readPublicHolidayRules)
                .map(HolidayCalendar::of)
                .orElse(HolidayCalendar.EMPTY);
    }

    private static HourStat hourStat(
            List<LocalDate> sampleDates,
            Set<LocalDate> holidayDates,
            HolidayMode holidayMode,
            Function<LocalDate, Integer> countOf) {

        double weightedSum = 0;
        double weightSum = 0;
        List<Integer> rawCounts = new ArrayList<>(sampleDates.size());
        for (LocalDate date : sampleDates) {
            int count = countOf.apply(date);
            rawCounts.add(count);
            double weight = HolidayAwareness.weightOf(date, holidayMode, holidayDates);
            weightedSum += count * weight;
            weightSum += weight;
        }
        double mean = weightedSum / weightSum;

        double rawMean =
                rawCounts.stream().mapToInt(Integer::intValue).average().orElse(0);
        double sumSquares = rawCounts.stream()
                .mapToDouble(v -> (v - rawMean) * (v - rawMean))
                .sum();
        double variance = rawCounts.size() >= 2 ? sumSquares / (rawCounts.size() - 1) : 0;
        double margin = CONFIDENCE_Z * Math.sqrt(variance);

        return new HourStat(mean, Math.max(0, mean - margin), mean + margin);
    }

    private record HourStat(double mean, double low, double high) {}

    /**
     * @param hoursGenerated     branch-level rows written (0 when the sample
     *                           was below the minimum — the run row still
     *                           exists, auditable, with nothing under it)
     * @param breakdownRowsWritten department- and product-level rows written
     */
    public record GenerationResult(UUID runId, LocalDate targetDate, int hoursGenerated, int breakdownRowsWritten) {}
}
