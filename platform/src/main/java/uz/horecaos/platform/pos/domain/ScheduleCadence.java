package uz.horecaos.platform.pos.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * When a daily {@code integration.pos_sync_schedules} row next fires (ADR 0012).
 *
 * <p>Pure, like {@link DifferenceEngine} and {@link ApplyPlanner}: no database,
 * no Spring, and {@code PosModuleBoundaryTests} enforces it here too. The
 * question this answers — "what is the next moment, strictly after {@code now},
 * that this branch's clock reads {@code localTime}" — has to be provably correct
 * on its own, because the answer is what gets written back as {@code
 * next_run_at} and read by every replica's next poll.
 *
 * <h2>Local, not platform time</h2>
 *
 * <p>{@code integration.pos_sync_schedules} stores an IANA {@code timezone} and a
 * {@code local_time} rather than a UTC instant directly, because "run the
 * catalog import at four in the morning" means four in the morning where the
 * restaurant is. A schedule computed once in UTC and left alone drifts an hour
 * against that clock twice a year, on every zone that observes daylight time —
 * quietly, since nothing about a UTC timestamp column says it is wrong.
 *
 * <h2>Why {@link ZonedDateTime}, not arithmetic on {@link Instant}</h2>
 *
 * <p>{@code now.plus(Duration.ofDays(1))} adds exactly twenty-four hours of
 * elapsed time, which is correct for a duration and wrong for a calendar day
 * whenever the zone's offset changes between {@code now} and then. {@link
 * ZonedDateTime#plusDays} instead re-resolves the same local date-time against
 * the zone's rules for the new date, so a schedule armed for 04:00 fires at
 * 04:00 local both before and after a transition — the gap between the two
 * firings is twenty-three or twenty-five hours precisely because the wall clock
 * that matters did not move.
 *
 * <p>{@link ZonedDateTime#of(java.time.LocalDate, LocalTime, ZoneId)} resolves
 * the two cases a calendar day can produce on a transition date on its own:
 * a spring-forward gap (a {@code local_time} that never occurs, such as 02:30 on
 * the day clocks jump from 02:00 to 03:00) is pushed forward by the length of the
 * gap, and a fall-back overlap (a {@code local_time} that occurs twice) resolves
 * to the earlier of the two offsets. Both are java.time's own documented
 * behaviour, not a rule this class adds.
 */
public final class ScheduleCadence {

    private ScheduleCadence() {}

    /**
     * The next instant, strictly after {@code now}, at which this zone's wall
     * clock reads {@code localTime}.
     *
     * <p>Used both to arm a freshly enabled schedule (the next occurrence of
     * {@code localTime}, today if it has not yet passed, tomorrow if it has) and
     * to advance one that just fired ({@code now} is at or after the occurrence
     * that was just claimed, so today's slot is never after it and the result is
     * always tomorrow's) — one function answers both because the rule is the
     * same question asked at a different moment.
     */
    public static Instant nextOccurrenceAfter(Instant now, String timezone, LocalTime localTime) {
        ZoneId zone = ZoneId.of(timezone);
        ZonedDateTime nowInZone = now.atZone(zone);
        ZonedDateTime todaysSlot = ZonedDateTime.of(nowInZone.toLocalDate(), localTime, zone);
        ZonedDateTime next = todaysSlot.isAfter(nowInZone) ? todaysSlot : todaysSlot.plusDays(1);
        return next.toInstant();
    }
}
