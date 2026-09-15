package uz.horecaos.platform.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Wave W02 (7.8b): plain-object coverage for {@link HolidayCalendar} — no
 * database needed, since a rule's own shape and a date's match against it are
 * pure functions of the two.
 */
class HolidayCalendarTests {

    @Test
    void aRecurringRuleMatchesTheSameMonthAndDayInAnyYear() {
        // Navruz: month 3, day 21 — the actual V0203-seeded Uzbekistan row.
        HolidayCalendar calendar = HolidayCalendar.of(List.of(new HolidayCalendar.Rule(3, 21, null)));

        assertThat(calendar.isHoliday(LocalDate.of(2026, 3, 21))).isTrue();
        assertThat(calendar.isHoliday(LocalDate.of(2027, 3, 21))).isTrue();
        assertThat(calendar.isHoliday(LocalDate.of(2026, 3, 22))).isFalse();
        assertThat(calendar.isHoliday(LocalDate.of(2026, 3, 20))).isFalse();
    }

    @Test
    void aDatedRuleMatchesOnlyThatExactDate() {
        HolidayCalendar calendar =
                HolidayCalendar.of(List.of(new HolidayCalendar.Rule(null, null, LocalDate.of(2026, 4, 2))));

        assertThat(calendar.isHoliday(LocalDate.of(2026, 4, 2))).isTrue();
        // Same month/day, different year: a dated rule does not recur.
        assertThat(calendar.isHoliday(LocalDate.of(2027, 4, 2))).isFalse();
    }

    @Test
    void theEmptyCalendarFlagsNothing() {
        assertThat(HolidayCalendar.EMPTY.isHoliday(LocalDate.of(2026, 3, 21))).isFalse();
        assertThat(HolidayCalendar.of(List.of()).isHoliday(LocalDate.of(2026, 3, 21)))
                .isFalse();
    }

    @Test
    void multipleRulesEachContributeIndependently() {
        HolidayCalendar calendar = HolidayCalendar.of(List.of(
                new HolidayCalendar.Rule(1, 1, null),
                new HolidayCalendar.Rule(9, 1, null),
                new HolidayCalendar.Rule(null, null, LocalDate.of(2026, 5, 3))));

        assertThat(calendar.isHoliday(LocalDate.of(2026, 1, 1))).isTrue();
        assertThat(calendar.isHoliday(LocalDate.of(2026, 9, 1))).isTrue();
        assertThat(calendar.isHoliday(LocalDate.of(2026, 5, 3))).isTrue();
        assertThat(calendar.isHoliday(LocalDate.of(2026, 6, 15))).isFalse();
    }

    @Test
    void aRuleCannotBeBothRecurringAndDated() {
        assertThatThrownBy(() -> new HolidayCalendar.Rule(3, 21, LocalDate.of(2026, 3, 21)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRuleMustBeEitherRecurringOrDated() {
        assertThatThrownBy(() -> new HolidayCalendar.Rule(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HolidayCalendar.Rule(3, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
