package uz.horecaos.platform.configuration;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Enables scheduling for the whole platform.
 *
 * <p>Lives here rather than inside a module because several modules now schedule
 * work — the outbox relay, the idempotency purge, approval expiry — and having
 * one module's configuration silently decide whether another module's job ever
 * runs is the kind of coupling that is only discovered when the job stops.
 *
 * <p><b>ADR 0023's {@code app}/{@code worker} split gates the whole class.</b> This is
 * the one place {@code @EnableScheduling} is declared, so a role of {@code app} skips
 * this configuration entirely — no {@code ScheduledAnnotationBeanPostProcessor}, no
 * {@link ThreadPoolTaskScheduler} bean, and therefore no {@code @Scheduled} method on
 * any module runs, regardless of that method's own {@code @ConditionalOnProperty}. A
 * per-job switch answers "should this job run at all"; this answers "does this process
 * run scheduled jobs", and the second question has to be answered in exactly one place
 * or a job added after this record could reintroduce the coupling ADR 0023 named. Role
 * {@code worker} or {@code both}, or the property left unset, changes nothing here —
 * every job's own switch still decides its own fate exactly as before this class was
 * touched.
 *
 * <p><b>Wave 61 closed one of two named exceptions to "every {@code @Scheduled}
 * method is worker-shaped".</b> {@code PosOrderExportTrigger.dispatchPending}
 * still drains an in-process queue that only the process which served the
 * confirming HTTP request ever populates, but {@code
 * PosOrderExportTrigger.sweepStale} — a second {@code @Scheduled} method on the
 * same class, counted separately in {@link #DEFAULT_POOL_SIZE} — now reads
 * {@code integration.pos_order_exports} directly, so any process running this
 * configuration eventually dispatches any tenant's confirmed order, whether or
 * not it is the one that confirmed it. A strict {@code app}/{@code worker}
 * split is therefore safe for POS today: {@code worker} alone dispatches
 * every order, {@code app} never has to.
 *
 * <p>{@code RealtimeStreamMaintenance.tick}/{@code onGrantChanged} remain the
 * one exception, and for a different reason than POS: they drive an SSE
 * registry ({@code SseStreamRegistry}) that is deliberately process-local — see
 * that class's own doc — so there is no row for a second process to read, ever.
 * The only process that can ever usefully run this tick is the one holding the
 * socket, which under ADR 0023's runtime shape is {@code app}, never {@code
 * worker}. Because this class's gate is all-or-nothing, a container running
 * strict role {@code app} runs no {@code @Scheduled} method at all, this tick
 * included, and nothing else ever runs it in that container's place — see ADR
 * 0023's Runtime shape and ADR 0045 for why that is accepted as a documented
 * deployment constraint (the {@code app} container keeps role {@code both})
 * rather than answered with a shared subscriber registry ADR 0045 explicitly
 * defers. This class cannot express a per-job exception on its own; it is
 * documented here because this is where the blanket switch lives that would
 * otherwise silently turn every job off, this one included.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnWorkerRole
public class SchedulingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfiguration.class);

    /**
     * One thread per {@code @Scheduled} method on the platform, rounded up.
     *
     * <p>Sizing by job count rather than by load is deliberate. Spring Boot's
     * default is a pool of <em>one</em>, and on one thread the jobs here are not
     * independent: the realtime tick fires every fifty milliseconds, the outbox
     * relay every second, and the sweepers and partition managers hold a
     * connection for as long as their statement takes. A sweeper that runs for a
     * minute therefore stops event publishing for a minute, and a job that hangs
     * on a stuck query stops every timer on the platform for the life of the
     * process — a total outage of the asynchronous half of the system with no
     * failing health check to show for it.
     *
     * <p>A pool sized to expected concurrency rather than to job count would only
     * make that rarer, not impossible, and rare is the worse failure: the jobs
     * cluster on the same minute and hour boundaries, so their expected
     * concurrency is not what a steady-state average suggests. Threads that spend
     * their lives parked cost a stack reservation each, which is the cheapest
     * insurance available against head-of-line blocking between unrelated
     * modules.
     *
     * <p>{@link SchedulerPoolSizeTests} fails if the number of {@code @Scheduled}
     * methods ever climbs past this, so the constant cannot go quietly stale.
     * ADR 0058 added two in its first stage: {@code
     * ApprovalDeadlineWarningSweeper.sweepOnce} and the {@code local}-profile
     * {@code TelegramLongPollingConsumer.pollOnce} — counted here too, since the
     * scan reads class metadata rather than a running context and does not know
     * a bean will be conditionally absent. ADR 0043 and ADR 0058's second stage
     * added six more: {@code DayCloseScheduler.closeDueDays} and {@code
     * .recutSettledDays} (the day-close heartbeat ADR 0043 never had a
     * production caller for), {@code DigestScheduler}'s three cadences
     * ({@code emitFifteenMinuteDigests}, {@code emitHalfDayDigests}, {@code
     * emitDayCloseDigests}), and {@code OnboardingStuckRunAlertSweeper.sweepOnce},
     * the control-plane sibling of the onboarding stuck-run alert. ADR 0059
     * stage 1 added one more: {@code FlowRunResumeSweeper.sweepOnce}, the
     * single sweeper a flow's delay block needs to resume. ADR 0059 stage 4
     * added one more still: {@code CampaignExpansionScheduler.sweepOnce}, the
     * caller {@code CampaignSendService#expandNextBatch} names but never had —
     * without it a started campaign expanded nothing, forever. Wave 13 added
     * the last one so far: {@code ConversationRetentionSweeper.sweepOnce},
     * enforcing {@code conversations.conversations.retention_months} — a
     * column that had existed since ADR 0059 stage 1 with nothing behind it,
     * recorded then as a named ADR 0029 gap rather than built. ADR 0064 added
     * one more: {@code AsteriskAmiConnectionSupervisor.ensureConnections},
     * which only decides whether a connection thread needs (re)starting —
     * the AMI session itself runs on its own dedicated thread, not this pool,
     * for the reason that class's own doc gives. Wave 61 added one more:
     * {@code PosOrderExportTrigger.sweepStale}, the durable backstop that
     * makes the in-process dispatch queue beside it safe on more than one
     * replica — see that class's doc and ADR 0023's Runtime shape. Wave 64
     * added the last one so far: {@code InventoryReservationSweeper
     * .expireStaleReservations}, the durable sweep ADR 0017 named and
     * {@code InventoryService.expireStaleReservations} implemented but that
     * nothing had ever called — a hold on abandoned stock used to outlive its
     * own TTL until an unrelated request happened to re-reserve the same
     * quote, which for an abandoned cart is never. Wave 68 added two more,
     * closing the same gap ADR 0044 named for its own module: {@code
     * CustomerMetricProjectionSweeper.sweepOnce}, the runner the five-minute
     * projection staleness budget never had, and {@code
     * MarketingRetentionSweeper.sweepOnce}, enforcing the twenty-four-month
     * audience-snapshot-membership window {@code JdbcAudienceStore.purgeMembers}
     * could already perform but nothing had ever called outside a test. Wave 73
     * added one more: {@code PosApprovalPoll.pollForDecisions}, which
     * reads the till's answer for an export parked in {@code AWAITING_APPROVAL}.
     * The export reached that state from the moment the adapter said the till
     * wanted a manager, and nothing ever asked the till again — an order waited
     * on a decision that had already been taken at the terminal. ADR
     * 0044's third named gap, the ADR 0029 erasure path, is deliberately
     * <b>not</b> among these: {@code CustomerMetricProjectionService.erase} and
     * {@code JdbcAudienceStore.eraseMembership} exist and are tested, but
     * nothing in the platform ever produces the fact "this customer asked to be
     * erased" — no request table, no endpoint, no status transition sets {@code
     * customer.accounts.status = 'ANONYMIZED'} — so there is no worklist for a
     * sweep to read. ADR 0029 says as much itself: "no data-subject export,
     * correction, anonymisation, retention, legal-hold or proof operation
     * exists anywhere — there is no privacy endpoint, service or table."
     * Scheduling a sweep with nothing to find would look like the erasure
     * obligation was met; it would not be, so it stays unscheduled and is
     * recorded as a gap instead. Wave 77 added one more: {@code
     * MediaVerificationWorker.verifyScheduledBatch}, the leased worker that
     * drains {@code media.verification_jobs} (ADR 0010, V0180) — the separate
     * asynchronous validation worker the record always asked for, replacing the
     * synchronous {@code HeadObject}/header-probe check that used to run inline
     * inside {@code finalizeUpload} on the request thread. Waves 73 and 77 each
     * raised this number from 45 to 46 for their own job, on branches that could
     * not see each other; git merged the two identical edits into one and left
     * the pool one thread short of the job count. Wave 90 added the last two so
     * far, closing ADR 0019's own named gap that only {@code ORDER_INVENTORY}
     * among its process managers had durable resumable state: {@code
     * OrderProcessWorker.runFulfillmentProcess}, which reflects fulfillment's
     * own sourcing outcome onto the shared {@code ordering.order_process_states}
     * row {@code DeliveryPlanTrigger} now opens beside the plan, and {@code
     * .sweepStalePayments}, the safety net and stuck list for {@code
     * ORDER_PAYMENT} — a row that used, in the record's own words, to be
     * "driven by nothing at all". Neither drives a provider effect; both only
     * ever read a signal that already exists elsewhere and reflect it, which is
     * why closing two more of ADR 0019's four missing rows cost two jobs and not
     * two new failure modes. Wave 92 added the last one so far: {@code
     * AuditPartitionArchiver.archiveClosedPartitions}, ADR 0027's own named gap —
     * the two retention keys V0030 registered on 2026-09-08 had a value and no
     * reader, and {@code AuditPartitionManager} only ever rolled partitions
     * forward, never archived or dropped one. It holds a connection for as long
     * as one closed year's rows take to export and a protected-storage round
     * trip takes to verify, which on the daily cadence this job runs at is the
     * same shape as every other partition sweeper on this pool, not a reason to
     * exempt it. Anything adding a {@code @Scheduled} method here should expect
     * the same off-by-one wave 73/77 hit, and trust {@code
     * SchedulerPoolSizeTests}, which counts them, over the number written here.
     */
    static final int DEFAULT_POOL_SIZE = 50;

    /**
     * The platform's scheduler, replacing Boot's single-threaded default.
     *
     * @param health   consulted about every failure, because this handler is the
     *                 only place that sees them all — see the error handler below
     * @param poolSize overridable for a deployment that adds jobs, never to
     *                 shrink below the job count — see {@link #DEFAULT_POOL_SIZE}
     */
    @Bean
    ThreadPoolTaskScheduler taskScheduler(
            ProcessHealth health,
            @Value("${horecaos.scheduling.pool-size:" + DEFAULT_POOL_SIZE + "}") int poolSize,
            @Value("${horecaos.scheduling.shutdown-grace:PT20S}") Duration shutdownGrace) {

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, poolSize));
        scheduler.setThreadNamePrefix("horecaos-scheduler-");
        // Named so a thread dump attributes a stuck job to the platform's
        // scheduler rather than to an anonymous pool-N-thread-1.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds((int) shutdownGrace.toSeconds());
        // Bounded rather than indefinite: a sweeper mid-statement should be given
        // the chance to finish its transaction, and a job that will not finish
        // must not be able to hold the container open past the orchestrator's own
        // kill timeout, which would turn a rolling deploy into a hard kill.
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(failure -> handle(health, failure));
        return scheduler;
    }

    /**
     * Every failure of every {@code @Scheduled} method on the platform.
     *
     * <p><b>Continuing is the policy, and it is not a choice this handler
     * makes.</b> Spring wraps each scheduled method in
     * {@code DelegatingErrorHandlingRunnable}, which catches {@code Throwable} —
     * {@code Error} included — and calls this. There is no rethrow behind it and
     * no path from here that cancels a future execution: whatever happens in
     * this lambda, the next tick runs. That is the right policy for an ordinary
     * failure, because a sweeper wedged on one tenant's bad row must not stop the
     * outbox relay. It is also simply the truth about the runtime, and code that
     * assumed otherwise — that throwing an {@code Error} out of a scheduled
     * method would end the process and get it restarted — was writing to a
     * runtime that does not exist. {@link ProcessHealth} is what that code should
     * have been calling, and now is.
     *
     * <p>So there are two outcomes here and no third. An ordinary failure is
     * logged and the schedule continues. A process-fatal one is logged and the
     * process asks to be restarted through the readiness probe, and the schedule
     * <em>still</em> continues, because it cannot do anything else — every task
     * that ticks in the minute before {@code autoheal} recreates the container
     * fails the same way, which is why {@code ProcessHealth} reports once.
     *
     * <p>Logged under this class rather than under each job so the scheduler's
     * own failures are greppable as one thing.
     */
    private static void handle(ProcessHealth health, Throwable failure) {
        if (ProcessHealth.isProcessFatal(failure)) {
            health.reportFatal("A scheduled task", failure);
            return;
        }
        log.error("Scheduled task failed; the schedule continues", failure);
    }
}
