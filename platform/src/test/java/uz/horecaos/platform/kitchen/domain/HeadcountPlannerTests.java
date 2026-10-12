package uz.horecaos.platform.kitchen.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.CapacityWindow;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.Input;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.LoadCell;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.Plan;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.Row;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.Station;
import uz.horecaos.platform.kitchen.domain.HeadcountPlanner.Status;

/**
 * The headcount plan's arithmetic (ADR 0163 Decisions 3 and 5), against a fixture computed by hand: four
 * Fridays, two cooking stations and the pass.
 *
 * <p>Grill at 12:00, in portions: 40, 50, 60, 50. Mean 50; the raw deviations are -10, 0, 10, 0, so the
 * sample variance is 200 / 3 and the standard deviation 8.165; the band is 1.2816 of those either side,
 * 39.536 to 60.464. At 20 portions per cook-hour that is {@code ceil(50 / 20) = 3} cooks expected and {@code
 * ceil(60.464 / 20) = 4} at the top of the band. Hot at 12:00 is 10 four times (a band of zero width).
 * At 19:00 the grill fires 80 every Friday and the hot line 60.
 */
class HeadcountPlannerTests {

    private static final LocalDate TARGET = LocalDate.of(2026, 10, 9);
    private static final List<LocalDate> FRIDAYS = List.of(
            LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 4));

    private static final Station GRILL = new Station(UUID.randomUUID(), "GRILL-1", StationRole.GRILL);
    private static final Station HOT = new Station(UUID.randomUUID(), "HOT-1", StationRole.HOT);
    private static final Station PASS = new Station(UUID.randomUUID(), "PASS", StationRole.EXPO);

    // ------------------------------------------------------------------ the arithmetic

    @Test
    @DisplayName("a hand-computed Friday: expected, band, cooks, and the cooks at the top of the band")
    void theFixtureMathIsWhatTheSpecificationSays() {
        Plan plan = HeadcountPlanner.plan(fixture());

        assertThat(plan.status()).isEqualTo(Status.OK);
        Row grillNoon = row(plan, GRILL, 12);
        assertThat(grillNoon.status()).isEqualTo(Status.OK);
        assertThat(grillNoon.expectedPortions()).isEqualByComparingTo("50.000");
        assertThat(grillNoon.lowPortions()).isEqualByComparingTo("39.536");
        assertThat(grillNoon.highPortions()).isEqualByComparingTo("60.464");
        assertThat(grillNoon.portionsPerCookHour()).isEqualTo(20);
        assertThat(grillNoon.cooksExpected()).as("ceil(50 / 20)").isEqualTo(3);
        assertThat(grillNoon.cooksAtPeakOfBand()).as("ceil(60.464 / 20)").isEqualTo(4);
        assertThat(grillNoon.sampleSize()).isEqualTo(4);

        Row hotNoon = row(plan, HOT, 12);
        assertThat(hotNoon.expectedPortions()).isEqualByComparingTo("10.000");
        assertThat(hotNoon.lowPortions()).isEqualByComparingTo("10.000");
        assertThat(hotNoon.highPortions()).isEqualByComparingTo("10.000");
        assertThat(hotNoon.cooksExpected()).isEqualTo(1);
        assertThat(hotNoon.cooksAtPeakOfBand()).isEqualTo(1);
    }

    @Test
    @DisplayName("a whole division does not round up: 60 portions at 30 per cook-hour is two cooks, not three")
    void ceilingIsExactOnAWholeDivision() {
        Row hotEvening = row(HeadcountPlanner.plan(fixture()), HOT, 19);

        assertThat(hotEvening.expectedPortions()).isEqualByComparingTo("60.000");
        assertThat(hotEvening.cooksExpected()).isEqualTo(2);
    }

    @Test
    @DisplayName("a date with no row for the hour counts as zero, not as a missing date")
    void anAbsentRowIsAZero() {
        List<LoadCell> loads = new ArrayList<>(fixtureLoads());
        loads.removeIf(cell -> cell.stationId().equals(GRILL.id())
                && cell.hour() == 12
                && cell.date().equals(FRIDAYS.get(1)));

        Row grillNoon = row(HeadcountPlanner.plan(withLoads(loads)), GRILL, 12);

        // The 60 is gone: 50, 0, 50, 40 -> mean 35, sample standard deviation 23.805, band 4.492 to 65.508.
        assertThat(grillNoon.expectedPortions()).isEqualByComparingTo("35.000");
        assertThat(grillNoon.lowPortions()).isEqualByComparingTo("4.492");
        assertThat(grillNoon.highPortions()).isEqualByComparingTo("65.508");
    }

    @Test
    @DisplayName("the peak is the hour with the most cooks across stations, and counts only stations with a figure")
    void thePeakAcrossStations() {
        Plan plan = HeadcountPlanner.plan(fixture());

        assertThat(plan.peak()).isNotNull();
        assertThat(peak(plan).hour()).isEqualTo(19);
        assertThat(peak(plan).cooksExpected()).as("grill 4 + hot 2").isEqualTo(6);
        assertThat(peak(plan).cooksAtPeakOfBand()).isEqualTo(6);
    }

    // ------------------------------------------------------------------ the refusals

    @Test
    @DisplayName("below three qualifying dates every hour is TOO_FEW_DATES with no number, and the raw dates come back")
    void tooFewDatesRefusesEveryHour() {
        Input input = new Input(
                TARGET,
                5,
                FRIDAYS.subList(0, 2),
                Set.of(),
                HeadcountHolidayMode.INCLUDE,
                List.of(GRILL, HOT),
                Map.of(GRILL.id(), 20, HOT.id(), 30),
                fixtureLoads(),
                List.of(),
                0);

        Plan plan = HeadcountPlanner.plan(input);

        assertThat(plan.status()).isEqualTo(Status.TOO_FEW_DATES);
        assertThat(plan.rows()).isNotEmpty().allSatisfy(row -> {
            assertThat(row.status()).isEqualTo(Status.TOO_FEW_DATES);
            assertThat(row.expectedPortions()).isNull();
            assertThat(row.cooksExpected()).isNull();
        });
        assertThat(plan.peak()).isNull();
        assertThat(plan.history())
                .as("the raw per-date portions instead, the refusal demand-history already makes")
                .anySatisfy(cell -> {
                    assertThat(cell.date()).isEqualTo(FRIDAYS.get(0));
                    assertThat(cell.stationCode()).isEqualTo("GRILL-1");
                    assertThat(cell.hour()).isEqualTo(12);
                    assertThat(cell.portions()).isEqualByComparingTo("50");
                });
    }

    @Test
    @DisplayName("exactly three qualifying dates is enough")
    void threeDatesIsTheFloor() {
        Input input = new Input(
                TARGET,
                5,
                FRIDAYS.subList(0, 3),
                Set.of(),
                HeadcountHolidayMode.INCLUDE,
                List.of(GRILL),
                Map.of(GRILL.id(), 20),
                fixtureLoads(),
                List.of(),
                0);

        assertThat(HeadcountPlanner.plan(input).status()).isEqualTo(Status.OK);
    }

    @Test
    @DisplayName("a station with no figure is NO_POLICY with no number, is listed, and is left out of the peak")
    void noPolicyShowsNoNumber() {
        Input input = new Input(
                TARGET,
                5,
                FRIDAYS,
                Set.of(),
                HeadcountHolidayMode.INCLUDE,
                List.of(GRILL, HOT),
                Map.of(HOT.id(), 30),
                fixtureLoads(),
                List.of(),
                0);

        Plan plan = HeadcountPlanner.plan(input);

        Row grill = row(plan, GRILL, 19);
        assertThat(grill.status()).isEqualTo(Status.NO_POLICY);
        assertThat(grill.expectedPortions()).isNull();
        assertThat(grill.cooksExpected()).isNull();
        assertThat(grill.portionsPerCookHour()).isNull();
        assertThat(plan.stationsWithoutFigure()).containsExactly("GRILL-1");
        assertThat(peak(plan).cooksExpected()).as("the hot line alone").isEqualTo(2);
    }

    @Test
    @DisplayName("the pass is never planned, even when its load is in the fact and a figure names it")
    void expoIsNeverPlanned() {
        Plan plan = HeadcountPlanner.plan(withExpoLoad());

        assertThat(plan.rows()).extracting(Row::stationCode).doesNotContain("PASS");
        assertThat(plan.stationsWithoutFigure()).doesNotContain("PASS");
        assertThat(peak(plan).cooksExpected())
                .as("expo's 100 portions an hour add no cooks")
                .isEqualTo(6);
    }

    // ------------------------------------------------------------------ the plant ceiling

    @Test
    @DisplayName("plantLimited is exactly: the top of the band exceeds the station's ceiling for that hour")
    void plantLimitedAtTheBoundary() {
        // Grill noon: the band tops out at 60.464.
        assertThat(grillNoonWith(window(GRILL, 5, 11 * 60, 13 * 60, 60)).plantLimited())
                .as("ceiling 60 < 60.464")
                .isTrue();
        assertThat(grillNoonWith(window(GRILL, 5, 11 * 60, 13 * 60, 61)).plantLimited())
                .as("ceiling 61 >= 60.464")
                .isFalse();
        assertThat(grillNoonWith().plantLimited()).as("no window, no ceiling").isFalse();
        assertThat(grillNoonWith().stationCeiling()).isNull();
    }

    @Test
    @DisplayName("a ceiling exactly equal to the top of the band is not a limit: the station can cook it")
    void aCeilingEqualToTheBandTopIsNotALimit() {
        Input base = fixture();
        Input input = new Input(
                base.targetDate(),
                base.weekday(),
                base.sampleDates(),
                base.holidayDates(),
                base.holidayMode(),
                base.stations(),
                base.figures(),
                base.loads(),
                List.of(window(HOT, 5, 11 * 60, 13 * 60, 10)),
                0);

        Row hotNoon = row(HeadcountPlanner.plan(input), HOT, 12);

        assertThat(hotNoon.highPortions()).isEqualByComparingTo("10.000");
        assertThat(hotNoon.stationCeiling()).isEqualTo(10);
        assertThat(hotNoon.plantLimited()).isFalse();
    }

    @Test
    @DisplayName(
            "of several windows overlapping the hour the lowest ceiling applies; a window that only touches does not")
    void theLowestOverlappingCeilingWins() {
        Row row = grillNoonWith(
                window(GRILL, 5, 11 * 60, 12 * 60 + 30, 90),
                window(GRILL, 5, 12 * 60 + 30, 14 * 60, 40),
                window(GRILL, 5, 13 * 60, 15 * 60, 5),
                window(GRILL, 4, 11 * 60, 15 * 60, 5));

        assertThat(row.stationCeiling()).isEqualTo(40);
        assertThat(row.plantLimited()).isTrue();
    }

    @Test
    @DisplayName("a 09:00 business day's hour 17 is 02:00 on the next calendar day, so Saturday's window applies")
    void aBusinessDayThatStartsAtNineLooksAtTheNextCalendarDay() {
        List<LoadCell> loads = List.of(
                new LoadCell(FRIDAYS.get(0), GRILL.id(), 17, new BigDecimal("100")),
                new LoadCell(FRIDAYS.get(1), GRILL.id(), 17, new BigDecimal("100")),
                new LoadCell(FRIDAYS.get(2), GRILL.id(), 17, new BigDecimal("100")));
        List<CapacityWindow> windows = List.of(
                window(GRILL, 6, 60, 180, 30), // Saturday 01:00-03:00 overlaps 02:00-03:00
                window(GRILL, 5, 60, 180, 10)); // Friday 01:00-03:00 is the wrong calendar day
        Input input = new Input(
                TARGET,
                5,
                FRIDAYS.subList(0, 3),
                Set.of(),
                HeadcountHolidayMode.INCLUDE,
                List.of(GRILL),
                Map.of(GRILL.id(), 20),
                loads,
                windows,
                9 * 60);

        Row row = row(HeadcountPlanner.plan(input), GRILL, 17);

        assertThat(row.stationCeiling()).isEqualTo(30);
        assertThat(row.plantLimited()).isTrue();
    }

    // ------------------------------------------------------------------ holidays

    @Test
    @DisplayName("WEIGHT counts a flagged holiday at half, the same mean ForecastService computes; INCLUDE does not")
    void weightChangesTheMean() {
        LocalDate holiday = FRIDAYS.get(1); // the 60-portion Friday

        Row weighted = row(HeadcountPlanner.plan(withHoliday(HeadcountHolidayMode.WEIGHT, holiday)), GRILL, 12);
        Row included = row(HeadcountPlanner.plan(withHoliday(HeadcountHolidayMode.INCLUDE, holiday)), GRILL, 12);

        assertThat(weighted.expectedPortions())
                .as("(40 + 50 + 60 * 0.5 + 50) / 3.5")
                .isEqualByComparingTo("48.571");
        assertThat(weighted.highPortions())
                .as("the band's width is the raw standard deviation, only its centre moves")
                .isEqualByComparingTo("59.036");
        assertThat(included.expectedPortions()).isEqualByComparingTo("50.000");
    }

    @Test
    @DisplayName("EXCLUDE drops a flagged date before the sample is taken, so an older ordinary date fills its place")
    void excludeDropsTheDateFromTheSample() {
        List<LocalDate> candidates = List.of(
                LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 9, 18),
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 4),
                LocalDate.of(2026, 8, 28));
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 9, 18));

        assertThat(HeadcountPlanner.sampleOf(candidates, 4, HeadcountHolidayMode.EXCLUDE, holidays::contains))
                .containsExactly(
                        LocalDate.of(2026, 9, 25),
                        LocalDate.of(2026, 9, 11),
                        LocalDate.of(2026, 9, 4),
                        LocalDate.of(2026, 8, 28));
        assertThat(HeadcountPlanner.sampleOf(candidates, 4, HeadcountHolidayMode.WEIGHT, holidays::contains))
                .as("WEIGHT keeps the flagged date")
                .contains(LocalDate.of(2026, 9, 18))
                .hasSize(4);
        assertThat(HeadcountPlanner.candidateLimit(4, HeadcountHolidayMode.INCLUDE))
                .isEqualTo(4);
        assertThat(HeadcountPlanner.candidateLimit(4, HeadcountHolidayMode.EXCLUDE))
                .as("over-fetched, so EXCLUDE can still fill the sample")
                .isEqualTo(12);
        assertThat(HeadcountPlanner.candidateLimit(12, HeadcountHolidayMode.EXCLUDE))
                .isEqualTo(36);
    }

    // ------------------------------------------------------------------ fixtures

    private static HeadcountPlanner.Peak peak(Plan plan) {
        HeadcountPlanner.Peak peak = plan.peak();
        assertThat(peak).as("a peak").isNotNull();
        return Objects.requireNonNull(peak);
    }

    private static Row row(Plan plan, Station station, int hour) {
        Optional<Row> found = plan.rows().stream()
                .filter(row -> row.stationId().equals(station.id()) && row.hour() == hour)
                .findFirst();
        assertThat(found).as("a row for %s at hour %d", station.code(), hour).isPresent();
        return found.get();
    }

    private static Row grillNoonWith(CapacityWindow... windows) {
        Input base = fixture();
        Input input = new Input(
                base.targetDate(),
                base.weekday(),
                base.sampleDates(),
                base.holidayDates(),
                base.holidayMode(),
                base.stations(),
                base.figures(),
                base.loads(),
                List.of(windows),
                base.businessDayStartMinute());
        return row(HeadcountPlanner.plan(input), GRILL, 12);
    }

    private static CapacityWindow window(Station station, int weekday, int from, int to, int ceiling) {
        return new CapacityWindow(station.id(), weekday, from, to, ceiling);
    }

    private static Input fixture() {
        return withLoads(fixtureLoads());
    }

    private static Input withLoads(List<LoadCell> loads) {
        return new Input(
                TARGET,
                5,
                FRIDAYS,
                Set.of(),
                HeadcountHolidayMode.INCLUDE,
                List.of(GRILL, HOT),
                Map.of(GRILL.id(), 20, HOT.id(), 30),
                loads,
                List.of(),
                0);
    }

    private static Input withExpoLoad() {
        List<LoadCell> loads = new ArrayList<>(fixtureLoads());
        for (LocalDate date : FRIDAYS) {
            loads.add(new LoadCell(date, PASS.id(), 19, new BigDecimal("100")));
        }
        return new Input(
                TARGET,
                5,
                FRIDAYS,
                Set.of(),
                HeadcountHolidayMode.INCLUDE,
                List.of(GRILL, HOT, PASS),
                Map.of(GRILL.id(), 20, HOT.id(), 30, PASS.id(), 10),
                loads,
                List.of(),
                0);
    }

    private static Input withHoliday(HeadcountHolidayMode mode, LocalDate holiday) {
        Input base = fixture();
        return new Input(
                base.targetDate(),
                base.weekday(),
                base.sampleDates(),
                Set.of(holiday),
                mode,
                base.stations(),
                base.figures(),
                base.loads(),
                base.windows(),
                base.businessDayStartMinute());
    }

    /** Grill at 12:00: 50, 60, 50, 40 for the four Fridays newest first; 19:00 is 80 and 60 every Friday. */
    private static List<LoadCell> fixtureLoads() {
        int[] grillNoon = {50, 60, 50, 40};
        List<LoadCell> loads = new ArrayList<>();
        for (int i = 0; i < FRIDAYS.size(); i++) {
            LocalDate date = FRIDAYS.get(i);
            loads.add(new LoadCell(date, GRILL.id(), 12, BigDecimal.valueOf(grillNoon[i])));
            loads.add(new LoadCell(date, HOT.id(), 12, BigDecimal.TEN));
            loads.add(new LoadCell(date, GRILL.id(), 19, BigDecimal.valueOf(80)));
            loads.add(new LoadCell(date, HOT.id(), 19, BigDecimal.valueOf(60)));
        }
        return loads;
    }
}
