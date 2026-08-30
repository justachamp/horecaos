package uz.horecaos.platform.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * ADR 0043: the business day is not the calendar day, and getting the boundary
 * wrong files a third of the evening under tomorrow.
 *
 * <p>Every instant here is written as a Tashkent wall-clock time and converted,
 * because that is how the bug reads when a merchant reports it: "the orders I
 * took at half past midnight are on the wrong day".
 */
class BusinessDayBoundaryTests {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    @Test
    void aMidnightTenantFilesAnOrderUnderItsLocalCalendarDate() {
        BusinessDayBoundary boundary = BusinessDayBoundary.midnight(TASHKENT);

        assertThat(boundary.dateOf(tashkent(2026, 8, 21, 23, 59))).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(boundary.dateOf(tashkent(2026, 8, 22, 0, 1))).isEqualTo(LocalDate.of(2026, 8, 22));
    }

    @Test
    void aNineOClockTenantKeepsTheNightOnTheDayItStarted() {
        BusinessDayBoundary boundary = new BusinessDayBoundary(TASHKENT, LocalTime.of(9, 0), 1);

        // The pair the ADR names. 08:59 is still yesterday's service; the day
        // turns over at 09:00 and not at midnight.
        assertThat(boundary.dateOf(tashkent(2026, 8, 22, 8, 59)))
                .as("08:59 belongs to the business day that began at 09:00 yesterday")
                .isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(boundary.dateOf(tashkent(2026, 8, 22, 9, 1))).isEqualTo(LocalDate.of(2026, 8, 22));
    }

    @Test
    void anOrderTakenAfterMidnightStaysOnTheEveningThatProducedIt() {
        BusinessDayBoundary boundary = new BusinessDayBoundary(TASHKENT, LocalTime.of(9, 0), 1);

        // A restaurant closing at 02:00 sees these on the evening's report. Seeing
        // them on the next date is what makes a merchant conclude the report is
        // broken, and they are not wrong.
        assertThat(boundary.dateOf(tashkent(2026, 8, 22, 1, 40))).isEqualTo(LocalDate.of(2026, 8, 21));
    }

    @Test
    void theWindowIsHalfOpenSoNoInstantFallsInTwoDays() {
        BusinessDayBoundary boundary = new BusinessDayBoundary(TASHKENT, LocalTime.of(9, 0), 1);
        LocalDate day = LocalDate.of(2026, 8, 21);

        Instant start = boundary.startOf(day);
        Instant end = boundary.endOf(day);

        assertThat(boundary.dateOf(start)).isEqualTo(day);
        assertThat(boundary.dateOf(end))
                .as("the end instant belongs to the next day, or an order at exactly 09:00 " + "would be counted twice")
                .isEqualTo(day.plusDays(1));
        assertThat(boundary.dateOf(end.minusSeconds(1))).isEqualTo(day);
    }

    @Test
    void aBoundaryOnAPartMinuteIsRefused() {
        assertThatThrownBy(() -> new BusinessDayBoundary(TASHKENT, LocalTime.of(9, 0, 30), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole minute");
    }

    private static Instant tashkent(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, TASHKENT).toInstant();
    }
}
