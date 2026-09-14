package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.catalog.domain.ItemSaleSchedule;
import uz.horecaos.platform.catalog.domain.ItemSaleSchedule.Window;

/**
 * Row 4.2g's own resolution rule, on literals — no database, no clock, exactly
 * {@code CatalogValidatorTests}' own reason for staying pure: each case is the
 * one {@link LocalDateTime} that should trip {@link ItemSaleSchedule#isOnSaleAt}
 * and the one nearby moment that should not.
 */
class ItemSaleScheduleTests {

    @Test
    @DisplayName("a variant with no windows at all is always on sale")
    void emptyScheduleIsAlwaysOnSale() {
        ItemSaleSchedule schedule = new ItemSaleSchedule(List.of());

        // Monday 03:00 — a moment no breakfast window would ever cover — proves
        // this is the unrestricted default and not an accidental "closed unless
        // proven open".
        assertThat(schedule.isOnSaleAt(LocalDateTime.of(2026, 9, 14, 3, 0))).isTrue();
    }

    @Test
    @DisplayName("a breakfast window covers the moment inside it and refuses the moments outside it")
    void breakfastWindowCoversOnlyItsOwnHours() {
        // 2026-09-14 is a Monday (dayOfWeek 1).
        ItemSaleSchedule schedule =
                new ItemSaleSchedule(List.of(new Window(1, LocalTime.of(6, 0), LocalTime.of(11, 0))));

        assertThat(schedule.isOnSaleAt(LocalDateTime.of(2026, 9, 14, 6, 0))).isTrue();
        assertThat(schedule.isOnSaleAt(LocalDateTime.of(2026, 9, 14, 8, 30))).isTrue();
        // Half-open: the closing instant itself is not on sale.
        assertThat(schedule.isOnSaleAt(LocalDateTime.of(2026, 9, 14, 11, 0))).isFalse();
        assertThat(schedule.isOnSaleAt(LocalDateTime.of(2026, 9, 14, 5, 59))).isFalse();
        assertThat(schedule.isOnSaleAt(LocalDateTime.of(2026, 9, 14, 12, 0))).isFalse();
    }

    @Test
    @DisplayName("a window bound to Monday does not cover the same clock time on Tuesday")
    void aWindowIsScopedToItsOwnWeekday() {
        ItemSaleSchedule schedule =
                new ItemSaleSchedule(List.of(new Window(1, LocalTime.of(6, 0), LocalTime.of(11, 0))));

        // 2026-09-15 is a Tuesday.
        assertThat(schedule.isOnSaleAt(LocalDateTime.of(2026, 9, 15, 8, 0))).isFalse();
    }

    @Test
    @DisplayName("a late window crossing midnight is read correctly, not as an empty same-day range")
    void aMidnightCrossingWindowIsHonoured() {
        // Monday 22:00 through Tuesday 02:00 — closesAt <= opensAt means "the
        // following day", the same convention V0020's service_schedule_rules
        // documents at length.
        ItemSaleSchedule schedule =
                new ItemSaleSchedule(List.of(new Window(1, LocalTime.of(22, 0), LocalTime.of(2, 0))));

        // Tuesday 01:00 — after midnight, still inside Monday's late window.
        assertThat(schedule.isOnSaleAt(LocalDateTime.of(2026, 9, 15, 1, 0))).isTrue();
        // Monday 23:00 — squarely inside the same window on the day it opened.
        assertThat(schedule.isOnSaleAt(LocalDateTime.of(2026, 9, 14, 23, 0))).isTrue();
        // Tuesday 03:00 — past the window's close.
        assertThat(schedule.isOnSaleAt(LocalDateTime.of(2026, 9, 15, 3, 0))).isFalse();
        // Monday 20:00 — before the window opens.
        assertThat(schedule.isOnSaleAt(LocalDateTime.of(2026, 9, 14, 20, 0))).isFalse();
    }

    @Test
    @DisplayName("a window rejects a day-of-week outside 1..7")
    void aWindowRejectsAnOutOfRangeDay() {
        assertThatThrownBy(() -> new Window(0, LocalTime.of(6, 0), LocalTime.of(11, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Window(8, LocalTime.of(6, 0), LocalTime.of(11, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
