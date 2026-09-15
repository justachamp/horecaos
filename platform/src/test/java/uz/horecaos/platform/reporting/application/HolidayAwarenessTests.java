package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.reporting.domain.HolidayMode;

/** Wave W02 (7.8b): {@link HolidayAwareness#weightOf} — pure, no database needed. */
class HolidayAwarenessTests {

    private static final LocalDate NAVRUZ = LocalDate.of(2026, 3, 21);
    private static final LocalDate ORDINARY = LocalDate.of(2026, 3, 14);
    private static final Set<LocalDate> HOLIDAYS = Set.of(NAVRUZ);

    @Test
    void includeAlwaysWeighsFullEvenOnAFlaggedDate() {
        assertThat(HolidayAwareness.weightOf(NAVRUZ, HolidayMode.INCLUDE, HOLIDAYS))
                .isEqualTo(1.0);
        assertThat(HolidayAwareness.weightOf(ORDINARY, HolidayMode.INCLUDE, HOLIDAYS))
                .isEqualTo(1.0);
    }

    @Test
    void excludeAlwaysWeighsFullBecauseAFlaggedDateNeverReachesThisMethod() {
        // JdbcReportingStore#readDemandHistory drops an EXCLUDE-mode holiday
        // from the sample before HolidayAwareness ever sees it, so whatever
        // date this method is asked about under EXCLUDE is, by construction,
        // never one of the flagged ones.
        assertThat(HolidayAwareness.weightOf(NAVRUZ, HolidayMode.EXCLUDE, HOLIDAYS))
                .isEqualTo(1.0);
        assertThat(HolidayAwareness.weightOf(ORDINARY, HolidayMode.EXCLUDE, HOLIDAYS))
                .isEqualTo(1.0);
    }

    @Test
    void weightHalvesOnlyTheFlaggedDate() {
        assertThat(HolidayAwareness.weightOf(NAVRUZ, HolidayMode.WEIGHT, HOLIDAYS))
                .isEqualTo(HolidayAwareness.HOLIDAY_WEIGHT);
        assertThat(HolidayAwareness.weightOf(ORDINARY, HolidayMode.WEIGHT, HOLIDAYS))
                .isEqualTo(1.0);
    }

    @Test
    void theHolidayWeightIsStrictlyBetweenZeroAndOne() {
        // Never full weight (a holiday genuinely trades differently) and
        // never zero (it still happened) — the constant's own doc, asserted
        // rather than only stated.
        assertThat(HolidayAwareness.HOLIDAY_WEIGHT).isGreaterThan(0.0).isLessThan(1.0);
    }
}
