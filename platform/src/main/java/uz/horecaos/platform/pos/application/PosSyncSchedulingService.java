package uz.horecaos.platform.pos.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.integration.api.pos.PosSyncRequestedPayload;
import uz.horecaos.platform.integration.api.pos.PosSyncRequester;
import uz.horecaos.platform.pos.domain.ScheduleCadence;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosScheduleStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosScheduleStore.ClaimedSchedule;

/**
 * The transactional half of ADR 0012's durable scheduler: claim one due
 * occurrence, arm the next one, and enqueue the command — all three, or none.
 *
 * <p>Its own bean rather than a method on {@link PosSyncScheduler}, and that is
 * load-bearing rather than a style choice. {@code OnboardingScheduler} used to
 * declare its claiming queries {@code @Transactional} on itself and call them on
 * {@code this} from its own {@code @Scheduled} method — which bypasses the Spring
 * proxy entirely, so the annotation described a transaction that never opened
 * and {@code FOR UPDATE SKIP LOCKED} released its lock before the caller had
 * read the row. Every claim in this codebase since has lived on a bean the
 * scheduler calls into, never on the scheduler itself — see {@code
 * OnboardingService.dueRuns}'s own doc for the incident. {@link
 * #claimAndDispatch} is this module's copy of that rule.
 *
 * <h2>Why the claim and the enqueue commit together</h2>
 *
 * <p>{@link JdbcPosScheduleStore#claimDue} takes the row lock and this method
 * advances {@code next_run_at} past "due" in the same transaction that appends
 * {@code PosSyncRequested} to the outbox ({@link JdbcPosScheduleStore} itself
 * opens no transaction of its own — see its class doc). A second replica's
 * concurrent attempt at the same row either finds it locked (skips, no work
 * done) or finds {@code next_run_at} already moved (nothing due, same silent
 * no-op) — either way, at most one command is ever produced for one due
 * occurrence, whatever else is running at the same time. If this method throws
 * after the claim but before the outbox append, both roll back together: the
 * schedule is still due, and the next tick — this one's or another replica's —
 * tries again.
 */
@Service
public class PosSyncSchedulingService {

    private final JdbcPosScheduleStore schedules;
    private final PosSyncRequester syncRequester;

    public PosSyncSchedulingService(JdbcPosScheduleStore schedules, PosSyncRequester syncRequester) {
        this.schedules = schedules;
        this.syncRequester = syncRequester;
    }

    /**
     * @return the schedule id this call actually claimed, or empty when there
     *         was nothing to do — the row was already claimed by somebody else,
     *         or stopped being due between the caller's listing and this call
     */
    @Transactional
    public Optional<UUID> claimAndDispatch(UUID scheduleId, Instant now) {
        Optional<ClaimedSchedule> claimed = schedules.claimDue(scheduleId, now);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ClaimedSchedule schedule = claimed.get();

        Instant next = ScheduleCadence.nextOccurrenceAfter(now, schedule.timezone(), schedule.localTime());
        schedules.advance(schedule.tenantId(), schedule.id(), now, next);

        UUID requestId = Ids.newId();
        syncRequester.requestSync(
                PosSyncRequestedPayload.scheduled(
                        requestId, schedule.tenantId(), schedule.bindingId(), schedule.id(), now),
                "pos-sync-schedule:" + schedule.id());

        return Optional.of(schedule.id());
    }
}
