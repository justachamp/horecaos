package uz.horecaos.platform.pos.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosScheduleStore;

/**
 * ADR 0012's durable scheduler: the PostgreSQL timer that decides a catalog
 * import is due, in the branch's own zone, and asks for one.
 *
 * <p>PostgreSQL and not Kafka, by the ADR's own words: "Kafka carries the
 * resulting command; a topic is not a clock, and a retained message is not a
 * schedule." {@code integration.pos_sync_schedules} is the durable state —
 * {@code next_run_at}, computed by {@link uz.horecaos.platform.pos.domain.ScheduleCadence}
 * from the row's own {@code timezone} and {@code local_time}, survives a
 * restart of every replica because it lives in the database, not in this
 * process's memory or in a Kafka delayed message.
 *
 * <p>Every replica polls, and {@code FOR UPDATE SKIP LOCKED} — taken inside
 * {@link PosSyncSchedulingService#claimAndDispatch}, never here; see that
 * class's own doc for why the claim cannot live on this bean — is what lets
 * them cooperate instead of contend: two replicas racing the same due schedule
 * settle to one claiming it and one finding nothing left to do, never two
 * commands for one occurrence.
 *
 * <p>Nothing transactional lives on this class, deliberately, for the same
 * reason {@code OnboardingScheduler} states for itself.
 */
@Component
@ConditionalOnProperty(name = "horecaos.pos.sync.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class PosSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(PosSyncScheduler.class);

    private final JdbcPosScheduleStore schedules;
    private final PosSyncSchedulingService scheduling;
    private final Clock clock;
    private final int batchSize;

    public PosSyncScheduler(
            JdbcPosScheduleStore schedules,
            PosSyncSchedulingService scheduling,
            Clock clock,
            @Value("${horecaos.pos.sync.scheduler.batch-size:20}") int batchSize) {
        this.schedules = schedules;
        this.scheduling = scheduling;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * One pass over the schedules due right now.
     *
     * <p>The candidate list is read once, up front, with no lock — see {@link
     * JdbcPosScheduleStore#dueScheduleIds}'s own doc for why: a schedule that
     * fails on every attempt must cost itself, one slot in this tick's bounded
     * batch, and not the other due schedules behind it. Each id is then
     * attempted exactly once, independently, exactly the granularity {@code
     * DeliverySourcingScheduler} and {@code PosApplyService} use for the same
     * reason.
     */
    @Scheduled(
            initialDelayString = "${horecaos.pos.sync.scheduler.initial-delay:PT15S}",
            fixedDelayString = "${horecaos.pos.sync.scheduler.interval:PT30S}")
    public void pollDueSchedules() {
        Instant now = clock.instant();
        List<UUID> due = schedules.dueScheduleIds(now, batchSize);
        for (UUID scheduleId : due) {
            try {
                scheduling
                        .claimAndDispatch(scheduleId, now)
                        .ifPresentOrElse(
                                claimed -> log.info("POS sync schedule {} came due and was requested", claimed),
                                () -> log.debug("POS sync schedule {} was already claimed elsewhere", scheduleId));
            } catch (RuntimeException failure) {
                // One schedule's failure -- a malformed timezone saved before a
                // validation rule tightened, an outbox insert racing a database
                // hiccup -- must not stop the rest of this tick's batch. The
                // schedule stays due (nothing committed on failure; see
                // PosSyncSchedulingService's own doc) and the next tick tries
                // it again.
                log.warn("POS sync schedule {} failed to claim or dispatch", scheduleId, failure);
            }
        }
    }
}
