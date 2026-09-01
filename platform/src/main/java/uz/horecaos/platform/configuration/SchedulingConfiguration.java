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
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
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
     * single sweeper a flow's delay block needs to resume.
     */
    static final int DEFAULT_POOL_SIZE = 38;

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
