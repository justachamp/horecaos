package uz.horecaos.platform.kitchen.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.kitchen.domain.StationCapacityShift.WindowOccurrence;

/**
 * The pure half of ADR 0041's throughput-ceiling shift: which instant a local
 * weekday-and-time window resolves to, and how much extra lead time an
 * overloaded station's ceiling asks for.
 *
 * <p>No database, no clock, no Spring — {@link StationCapacityShift} takes
 * nothing but its arguments, so every property here is a property of the
 * function rather than of a fixture.
 */
class StationCapacityShiftTests {

    private static final String TASHKENT = "Asia/Tashkent";
    private static final String NEW_YORK = "America/New_York";

    // ---------------------------------------------------------------- occurrence

    @Test
    @DisplayName("a window occurrence resolves to the same local date as the anchor instant")
    void occurrenceUsesTheAnchorsOwnLocalDate() {
        ZoneId zone = ZoneId.of(TASHKENT);
        Instant anchor = ZonedDateTime.of(LocalDate.of(2026, 8, 25), LocalTime.of(13, 30), zone)
                .toInstant();

        WindowOccurrence occurrence =
                StationCapacityShift.occurrence(anchor, zone, LocalTime.of(11, 0), LocalTime.of(14, 0));

        assertThat(occurrence.start().atZone(zone).toLocalDateTime())
                .isEqualTo(LocalDate.of(2026, 8, 25).atTime(11, 0));
        assertThat(occurrence.end().atZone(zone).toLocalDateTime())
                .isEqualTo(LocalDate.of(2026, 8, 25).atTime(14, 0));
        assertThat(occurrence.duration()).isEqualTo(Duration.ofHours(3));
    }

    @Test
    @DisplayName(
            "no drift in a zone with no daylight saving: a three-hour window is exactly three hours of elapsed time")
    void tashkentWindowNeverDrifts() {
        ZoneId zone = ZoneId.of(TASHKENT);
        Instant anchor = ZonedDateTime.of(LocalDate.of(2026, 12, 20), LocalTime.of(12, 0), zone)
                .toInstant();

        WindowOccurrence occurrence =
                StationCapacityShift.occurrence(anchor, zone, LocalTime.of(11, 0), LocalTime.of(14, 0));

        assertThat(Duration.between(occurrence.start(), occurrence.end())).isEqualTo(Duration.ofHours(3));
    }

    @Test
    @DisplayName("a window spanning a spring-forward gap is one hour shorter in elapsed time, not silently wrong")
    void windowAcrossSpringForwardIsShorterInElapsedTime() {
        ZoneId zone = ZoneId.of(NEW_YORK);
        ZoneOffsetTransition springForward = zone.getRules().nextTransition(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(springForward.isGap()).isTrue();
        LocalDate transitionDate = springForward.getDateTimeBefore().toLocalDate();
        // The transition sits inside 01:00-04:00 on its date in every zone that
        // observes it at 2 a.m., so a window spanning it loses exactly the gap.
        Instant anchor =
                ZonedDateTime.of(transitionDate, LocalTime.of(1, 30), zone).toInstant();

        WindowOccurrence occurrence =
                StationCapacityShift.occurrence(anchor, zone, LocalTime.of(1, 0), LocalTime.of(4, 0));

        // Three wall-clock hours, minus the one the clocks skipped: a naive
        // Duration.ofHours(3) added to the start would land on the wrong wall
        // clock, exactly the failure ScheduleCadence's own tests guard against.
        assertThat(occurrence.duration()).isEqualTo(Duration.ofHours(2));
    }

    // -------------------------------------------------------------- offsetSeconds

    @Test
    @DisplayName("a station under its ceiling needs no offset")
    void noOffsetWhenUnderCeiling() {
        long offset = StationCapacityShift.offsetSeconds(40, Duration.ofHours(1), 20, 10);
        assertThat(offset).isZero();
    }

    @Test
    @DisplayName("a station landing exactly on its ceiling needs no offset")
    void noOffsetAtExactCeiling() {
        long offset = StationCapacityShift.offsetSeconds(40, Duration.ofHours(1), 30, 10);
        assertThat(offset).isZero();
    }

    @Test
    @DisplayName("overage translates to lead time at the station's own rate")
    void offsetScalesWithOverageAtTheStationsRate() {
        // 40 portions/hour is one every 90 seconds. Ten portions over the
        // ceiling therefore need fifteen minutes of extra lead.
        long offset = StationCapacityShift.offsetSeconds(40, Duration.ofHours(1), 35, 15);
        assertThat(offset).isEqualTo(Duration.ofMinutes(15).toSeconds());
    }

    @Test
    @DisplayName("a window shorter than an hour scales the ceiling down before comparing")
    void shorterWindowScalesCapacityDown() {
        // 40/hour over a 30-minute window is a ceiling of 20 for this
        // occurrence, not 40. Eighteen already committed plus this ticket's
        // four is two over that scaled-down ceiling — at 40/hour (one every 90
        // seconds), two portions of overage need three minutes of extra lead.
        // The same 22 portions against the un-scaled 40/hour ceiling would not
        // have exceeded it at all, which is the mistake scaling down guards
        // against.
        long offset = StationCapacityShift.offsetSeconds(40, Duration.ofMinutes(30), 18, 4);
        assertThat(offset).isEqualTo(Duration.ofMinutes(3).toSeconds());
        assertThat(StationCapacityShift.offsetSeconds(40, Duration.ofHours(1), 18, 4))
                .as("the same load against the un-scaled hourly ceiling would not have been over it")
                .isZero();
    }

    @Test
    @DisplayName("more overage always asks for at least as much lead time as less overage")
    void offsetIsMonotonicInOverage() {
        long smaller = StationCapacityShift.offsetSeconds(40, Duration.ofHours(1), 30, 15);
        long larger = StationCapacityShift.offsetSeconds(40, Duration.ofHours(1), 30, 30);
        assertThat(smaller).isGreaterThan(0);
        assertThat(larger).isGreaterThan(smaller);
    }

    @Test
    @DisplayName("overage that does not divide the rate evenly still rounds up to a whole second")
    void offsetRoundsUpRatherThanDown() {
        // 7/hour is one every 3600/7 = 514.28... seconds. One portion of
        // overage must not be answered with 514 seconds of lead — that is
        // less than the ceiling's own rate actually needs.
        long offset = StationCapacityShift.offsetSeconds(7, Duration.ofHours(1), 7, 1);
        assertThat(offset).isEqualTo(515);
    }

    @Test
    @DisplayName("no configured ceiling — a non-positive rate — is read as unbounded, never a division error")
    void nonPositiveRateIsUnbounded() {
        assertThat(StationCapacityShift.offsetSeconds(0, Duration.ofHours(1), 1000, 1000))
                .isZero();
    }
}
