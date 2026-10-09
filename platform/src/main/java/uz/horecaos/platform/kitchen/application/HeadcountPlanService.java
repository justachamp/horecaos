package uz.horecaos.platform.kitchen.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.kitchen.application.port.HeadcountSource;
import uz.horecaos.platform.kitchen.application.port.HeadcountSource.BusinessDay;
import uz.horecaos.platform.kitchen.application.port.HeadcountSource.Loads;
import uz.horecaos.platform.kitchen.domain.HeadcountHolidayMode;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.Plan;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.Station;
import uz.horecaos.platform.kitchen.domain.PublicHolidays;
import uz.horecaos.platform.kitchen.domain.StationProductivityPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The cook-headcount plan for one branch and weekday (ADR 0163 Decisions 3 and 5): computed on read from the
 * {@code reporting.fact_station_load} rows the day close wrote, the productivity figures the tenant
 * published and the stations' own ceilings. Nothing is persisted per request, so there is no second copy of
 * the number to disagree with the first, and no ticket is read on this path.
 *
 * <p>It is a planning aid and never a roster. It writes nothing, changes no ceiling, no release time and no
 * ticket.
 */
@Service
public class HeadcountPlanService {

    /** The same span {@code demand-history} looks back over. */
    private static final int LOOKBACK_DAYS = 400;

    private final HeadcountSource source;
    private final StationProductivityPolicies productivity;
    private final Clock clock;
    private final MeterRegistry meters;

    public HeadcountPlanService(
            HeadcountSource source, StationProductivityPolicies productivity, Clock clock, MeterRegistry meters) {
        this.source = source;
        this.productivity = productivity;
        this.clock = clock;
        this.meters = meters;
    }

    /** The plan, with the calculation version of the fact rows it was drawn from (0 when it drew on none). */
    public PlanResult plan(
            UUID tenantId, UUID brandId, UUID locationId, int weekday, int sampleSize, HeadcountHolidayMode mode) {
        Timer.Sample timer = Timer.start(meters);
        if (!source.locationExists(tenantId, brandId, locationId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such location");
        }
        BusinessDay businessDay = source.businessDay(tenantId);
        LocalDate today = LocalDate.now(clock.withZone(businessDay.zone()));
        LocalDate targetDate =
                today.plusDays(Math.floorMod(weekday - today.getDayOfWeek().getValue(), 7));
        // History never includes today: it has not finished trading, or has not started.
        LocalDate historyThrough = today.minusDays(1);
        LocalDate historyFrom = historyThrough.minusDays(LOOKBACK_DAYS);

        PublicHolidays holidays = source.holidays(tenantId);
        List<LocalDate> candidates = source.candidateDates(
                tenantId,
                locationId,
                weekday,
                historyFrom,
                historyThrough,
                HeadcountPlanner.candidateLimit(sampleSize, mode));
        List<LocalDate> sampleDates = HeadcountPlanner.sampleOf(candidates, sampleSize, mode, holidays::isHoliday);

        Loads loads = source.loads(tenantId, locationId, sampleDates);
        List<Station> stations = source.stations(tenantId, locationId);
        StationProductivityPolicy policy =
                productivity.resolveFor(ResourceScope.location(tenantId, brandId, locationId));
        Map<UUID, Integer> figures = new HashMap<>();
        for (Station station : stations) {
            policy.figureFor(station.code(), station.role()).ifPresent(figure -> figures.put(station.id(), figure));
        }

        Plan plan = HeadcountPlanner.plan(new HeadcountPlanner.Input(
                targetDate,
                weekday,
                sampleDates,
                Set.copyOf(sampleDates.stream().filter(holidays::isHoliday).toList()),
                mode,
                stations,
                figures,
                loads.cells(),
                source.windows(tenantId, locationId),
                businessDay.start().getHour() * 60 + businessDay.start().getMinute()));

        meters.counter("kitchen.headcount.plan", "status", plan.status().name()).increment();
        timer.stop(meters.timer("kitchen.headcount.plan.latency"));
        return new PlanResult(plan, loads.calculationVersion());
    }

    public record PlanResult(Plan plan, int calculationVersion) {}
}
