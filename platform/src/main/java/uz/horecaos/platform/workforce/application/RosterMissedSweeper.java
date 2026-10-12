package uz.horecaos.platform.workforce.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore;

/**
 * Marks a published roster entry {@code MISSED} once its planned end has passed with no shift (ADR
 * 0156 Decision 3), and keeps the module's two gauges honest.
 *
 * <p>{@code MISSED} is a coverage fact and the console words it as one, never as a disciplinary one:
 * nothing is deducted, refused or reported to anyone by this job. It touches only the entry's status,
 * and only {@code PUBLISHED} entries whose window is over -- an entry with a shift against it is
 * {@code CONSUMED} already and is not selected. It runs for every tenant, including one that has
 * switched attendance off (the rows stay; "rollback is setting the flag false").
 *
 * <p>The gauges are counts, never people: shifts awaiting approval, and open shifts older than the
 * longest configurable limit (sixteen hours). A shift open longer than the maximum is <em>flagged</em>
 * on the manager's screen and never closed by a job -- nothing is closed with invented hours.
 */
@Component
public class RosterMissedSweeper {

    private static final Logger log = LoggerFactory.getLogger(RosterMissedSweeper.class);

    private final JdbcStaffRosterStore roster;
    private final JdbcStaffShiftStore shifts;
    private final Clock clock;
    private final int batchSize;
    private final Counter marked;

    public RosterMissedSweeper(
            JdbcStaffRosterStore roster,
            JdbcStaffShiftStore shifts,
            Clock clock,
            MeterRegistry meters,
            @Value("${horecaos.workforce.roster-sweep.batch-size:500}") int batchSize) {
        this.roster = roster;
        this.shifts = shifts;
        this.clock = clock;
        this.batchSize = batchSize;
        this.marked = Counter.builder("horecaos.workforce.roster_entries.missed")
                .description("Published roster entries marked missed after their end passed with no shift")
                .register(meters);
        Gauge.builder("horecaos.workforce.shifts.awaiting_approval", shifts, JdbcStaffShiftStore::countAwaitingApproval)
                .description("Staff shifts waiting for an approver")
                .register(meters);
        Gauge.builder("horecaos.workforce.shifts.open_too_long", this, RosterMissedSweeper::openTooLong)
                .description("Staff shifts still open past the longest configurable limit")
                .register(meters);
    }

    @Scheduled(
            initialDelayString = "${horecaos.workforce.roster-sweep.initial-delay:PT2M}",
            fixedDelayString = "${horecaos.workforce.roster-sweep.interval:PT5M}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The roster missed-entry sweep could not run", failure);
        }
    }

    /** @return how many entries this pass marked missed, for a deterministic test */
    public int runOnce() {
        int count = roster.markMissed(clock.instant(), batchSize);
        if (count > 0) {
            marked.increment(count);
            log.info("Roster sweep: {} published entries ended with no shift and were marked missed", count);
        }
        return count;
    }

    private double openTooLong() {
        try {
            Instant cutoff = clock.instant().minus(Duration.ofHours(16));
            return shifts.countOpenBefore(cutoff);
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }
}
