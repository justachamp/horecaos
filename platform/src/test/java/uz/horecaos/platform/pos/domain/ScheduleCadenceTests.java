package uz.horecaos.platform.pos.domain;

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

/**
 * When ADR 0012's daily schedule next fires, including across a daylight-saving
 * transition.
 *
 * <p>Every transition date below is found from {@link ZoneId#getRules()} rather
 * than hard-coded, so these tests hold for whichever year they happen to run in
 * and prove the rule rather than one calendar's arithmetic.
 */
class ScheduleCadenceTests {

    private static final String TASHKENT = "Asia/Tashkent";
    private static final String NEW_YORK = "America/New_York";
    private static final LocalTime FOUR_AM = LocalTime.of(4, 0);

    @Test
    @DisplayName("today's slot is returned when it has not yet passed")
    void todaysSlotWhenStillAhead() {
        ZoneId zone = ZoneId.of(TASHKENT);
        Instant now = ZonedDateTime.of(LocalDate.of(2026, 6, 15), LocalTime.of(1, 0), zone)
                .toInstant();

        Instant next = ScheduleCadence.nextOccurrenceAfter(now, TASHKENT, FOUR_AM);

        ZonedDateTime nextInZone = next.atZone(zone);
        assertThat(nextInZone.toLocalDate()).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(nextInZone.toLocalTime()).isEqualTo(FOUR_AM);
    }

    @Test
    @DisplayName("tomorrow's slot is returned once today's has passed")
    void tomorrowsSlotWhenTodaysHasPassed() {
        ZoneId zone = ZoneId.of(TASHKENT);
        Instant now = ZonedDateTime.of(LocalDate.of(2026, 6, 15), LocalTime.of(9, 0), zone)
                .toInstant();

        Instant next = ScheduleCadence.nextOccurrenceAfter(now, TASHKENT, FOUR_AM);

        ZonedDateTime nextInZone = next.atZone(zone);
        assertThat(nextInZone.toLocalDate()).isEqualTo(LocalDate.of(2026, 6, 16));
        assertThat(nextInZone.toLocalTime()).isEqualTo(FOUR_AM);
    }

    @Test
    @DisplayName("advancing from an occurrence that just fired never returns the same instant")
    void advancingFromTheJustFiredOccurrenceMovesForward() {
        ZoneId zone = ZoneId.of(TASHKENT);
        Instant occurrence =
                ZonedDateTime.of(LocalDate.of(2026, 6, 15), FOUR_AM, zone).toInstant();

        Instant next = ScheduleCadence.nextOccurrenceAfter(occurrence, TASHKENT, FOUR_AM);

        assertThat(next).isAfter(occurrence);
        assertThat(next.atZone(zone).toLocalDate()).isEqualTo(LocalDate.of(2026, 6, 16));
    }

    @Test
    @DisplayName("no drift in a zone with no daylight saving: the gap between firings is exactly a day")
    void tashkentNeverDrifts() {
        Instant first = ScheduleCadence.nextOccurrenceAfter(
                ZonedDateTime.of(LocalDate.of(2026, 3, 1), LocalTime.MIDNIGHT, ZoneId.of(TASHKENT))
                        .toInstant(),
                TASHKENT,
                FOUR_AM);
        Instant second = ScheduleCadence.nextOccurrenceAfter(first, TASHKENT, FOUR_AM);

        assertThat(Duration.between(first, second)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("the gap across a spring-forward is twenty-three hours, and the wall clock still reads the same time")
    void springForwardGapIsTwentyThreeHours() {
        ZoneId zone = ZoneId.of(NEW_YORK);
        ZoneOffsetTransition springForward = zone.getRules().nextTransition(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(springForward.isGap())
                .as("the first transition of the year in America/New_York must be the spring-forward gap")
                .isTrue();
        LocalDate transitionDate = springForward.getDateTimeBefore().toLocalDate();

        Instant beforeTransition =
                ZonedDateTime.of(transitionDate.minusDays(1), FOUR_AM, zone).toInstant();
        Instant onTransitionDay = ScheduleCadence.nextOccurrenceAfter(beforeTransition, NEW_YORK, FOUR_AM);

        // A naive now.plus(Duration.ofDays(1)) would land one hour into the
        // future of this and silently miss the wall-clock time the schedule
        // was armed for -- that is exactly the bug this class exists to avoid.
        assertThat(Duration.between(beforeTransition, onTransitionDay)).isEqualTo(Duration.ofHours(23));
        assertThat(onTransitionDay.atZone(zone).toLocalDate()).isEqualTo(transitionDate);
        assertThat(onTransitionDay.atZone(zone).toLocalTime()).isEqualTo(FOUR_AM);
    }

    @Test
    @DisplayName("the gap across a fall-back is twenty-five hours, and the wall clock still reads the same time")
    void fallBackGapIsTwentyFiveHours() {
        ZoneId zone = ZoneId.of(NEW_YORK);
        ZoneOffsetTransition springForward = zone.getRules().nextTransition(Instant.parse("2026-01-01T00:00:00Z"));
        ZoneOffsetTransition fallBack = zone.getRules().nextTransition(springForward.getInstant());
        assertThat(fallBack.isOverlap())
                .as("the transition following the spring-forward must be the fall-back overlap")
                .isTrue();
        LocalDate transitionDate = fallBack.getDateTimeBefore().toLocalDate();

        Instant beforeTransition =
                ZonedDateTime.of(transitionDate.minusDays(1), FOUR_AM, zone).toInstant();
        Instant onTransitionDay = ScheduleCadence.nextOccurrenceAfter(beforeTransition, NEW_YORK, FOUR_AM);

        assertThat(Duration.between(beforeTransition, onTransitionDay)).isEqualTo(Duration.ofHours(25));
        assertThat(onTransitionDay.atZone(zone).toLocalDate()).isEqualTo(transitionDate);
        assertThat(onTransitionDay.atZone(zone).toLocalTime()).isEqualTo(FOUR_AM);
    }

    @Test
    @DisplayName(
            "a local time inside a spring-forward gap is pushed forward by the length of the gap, not left unreachable")
    void aLocalTimeInsideTheGapResolvesForward() {
        ZoneId zone = ZoneId.of(NEW_YORK);
        ZoneOffsetTransition springForward = zone.getRules().nextTransition(Instant.parse("2026-01-01T00:00:00Z"));
        LocalDate transitionDate = springForward.getDateTimeBefore().toLocalDate();
        // 02:30 never happens in America/New_York on the day clocks jump from
        // 02:00 straight to 03:00.
        LocalTime insideTheGap = LocalTime.of(2, 30);
        // Exactly yesterday's own occurrence of insideTheGap -- an ordinary,
        // unambiguous time on the day before the transition -- so "the slot
        // that is not after now" is today's and the answer has to be
        // tomorrow's, which is the transition day itself.
        Instant now = ZonedDateTime.of(transitionDate.minusDays(1), insideTheGap, zone)
                .toInstant();

        Instant next = ScheduleCadence.nextOccurrenceAfter(now, NEW_YORK, insideTheGap);

        assertThat(next.atZone(zone).toLocalDate()).isEqualTo(transitionDate);
        // Pushed forward by the one-hour gap length, per java.time's own
        // documented resolution -- this class adds no special-casing of its
        // own and relies on it.
        assertThat(next.atZone(zone).toLocalTime()).isEqualTo(LocalTime.of(3, 30));
    }
}
