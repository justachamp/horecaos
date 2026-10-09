package uz.horecaos.platform.kitchen.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.kitchen.application.HeadcountPlanService;
import uz.horecaos.platform.kitchen.application.HeadcountPlanService.PlanResult;
import uz.horecaos.platform.kitchen.domain.HeadcountHolidayMode;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.Plan;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.Status;
import uz.horecaos.platform.kitchen.domain.StationRole;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The cook-headcount plan of one branch for one weekday (ADR 0163 Decision 5): how many cooks each station
 * needs in each operating hour, from the same weekday's recent history and the tenant's own portions per
 * cook-hour. A planning aid, never a rota: it names no person and changes no ticket, release time or
 * ceiling.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/kitchen")
@Tag(name = "Kitchen headcount", description = "Cooks needed per station and hour (ADR 0163)")
public class KitchenHeadcountController {

    /** The metric the fact backs, ADR 0163 Decision 2. */
    private static final String METRIC_ID = "kitchen_station_portions";

    private final HeadcountPlanService plans;

    public KitchenHeadcountController(HeadcountPlanService plans) {
        this.plans = plans;
    }

    @GetMapping("/headcount-plan")
    @RequiresCapability(value = Capability.KITCHEN_HEADCOUNT_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Cooks needed per station and operating hour, for one weekday",
            description = "A planning aid, not a rota: cooks = ceil(expected portions in the hour / the tenant's "
                    + "portions per cook-hour), from the mean of the branch's most recent same-weekday dates "
                    + "(ADR 0043's method at station grain, an 80% band of 1.2816 standard deviations). hour is "
                    + "operating-day-relative: 0 is the business day's own start. A station with no figure "
                    + "answers NO_POLICY and no number; fewer than three qualifying dates answers TOO_FEW_DATES "
                    + "and the raw per-date portions instead. plantLimited means the band's top exceeds the "
                    + "station's own ceiling for that hour, where more cooks do not help. The pass (EXPO) is "
                    + "never planned. sampleSize defaults to 4, at least 3, at most 12.")
    public ResponseEntity<PlanResponse> plan(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam int weekday,
            @RequestParam(required = false) @Nullable Integer sampleSize,
            @RequestParam(required = false) @Nullable HeadcountHolidayMode holidayMode) {

        if (weekday < 1 || weekday > 7) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "weekday must be between 1 (Monday) and 7 (Sunday), ISO-8601",
                    Map.of("weekday", weekday));
        }
        int sample = sampleSize == null ? HeadcountPlanner.DEFAULT_SAMPLE_SIZE : sampleSize;
        if (sample < HeadcountPlanner.MINIMUM_DATES) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "sampleSize must be at least %d".formatted(HeadcountPlanner.MINIMUM_DATES),
                    Map.of("sampleSize", sample));
        }
        PlanResult result = plans.plan(
                tenantId,
                brandId,
                locationId,
                weekday,
                Math.min(sample, HeadcountPlanner.MAX_SAMPLE_SIZE),
                holidayMode == null ? HeadcountHolidayMode.INCLUDE : holidayMode);
        return ResponseEntity.ok(PlanResponse.of(result));
    }

    public record PlanResponse(
            LocalDate targetDate,
            int weekday,
            Status status,
            List<LocalDate> sampleDates,
            GeneratedFrom generatedFrom,
            List<RowView> rows,
            @Nullable PeakView peak,
            List<String> stationsWithoutFigure,
            List<RawCellView> history) {

        static PlanResponse of(PlanResult result) {
            Plan plan = result.plan();
            return new PlanResponse(
                    plan.targetDate(),
                    plan.weekday(),
                    plan.status(),
                    plan.sampleDates(),
                    new GeneratedFrom(METRIC_ID, result.calculationVersion()),
                    plan.rows().stream().map(RowView::of).toList(),
                    plan.peak() == null
                            ? null
                            : new PeakView(
                                    plan.peak().hour(),
                                    plan.peak().cooksExpected(),
                                    plan.peak().cooksAtPeakOfBand()),
                    plan.stationsWithoutFigure(),
                    plan.history().stream()
                            .map(cell -> new RawCellView(cell.date(), cell.stationCode(), cell.hour(), cell.portions()))
                            .toList());
        }
    }

    /** @param calculationVersion the fact rows' metric calculation version; 0 when the plan drew on none */
    public record GeneratedFrom(String metricId, int calculationVersion) {}

    public record RowView(
            UUID stationId,
            String stationCode,
            StationRole stationRole,
            int hour,
            Status status,
            @Nullable Integer portionsPerCookHour,
            @Nullable BigDecimal expectedPortions,
            @Nullable BigDecimal lowPortions,
            @Nullable BigDecimal highPortions,
            @Nullable Integer cooksExpected,
            @Nullable Integer cooksAtPeakOfBand,
            @Nullable Integer stationCeiling,
            boolean plantLimited,
            int sampleSize) {

        static RowView of(HeadcountPlanner.Row row) {
            return new RowView(
                    row.stationId(),
                    row.stationCode(),
                    row.stationRole(),
                    row.hour(),
                    row.status(),
                    row.portionsPerCookHour(),
                    row.expectedPortions(),
                    row.lowPortions(),
                    row.highPortions(),
                    row.cooksExpected(),
                    row.cooksAtPeakOfBand(),
                    row.stationCeiling(),
                    row.plantLimited(),
                    row.sampleSize());
        }
    }

    public record PeakView(int hour, int cooksExpected, int cooksAtPeakOfBand) {}

    public record RawCellView(LocalDate date, String stationCode, int hour, BigDecimal portions) {}
}
