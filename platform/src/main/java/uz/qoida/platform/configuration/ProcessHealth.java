package uz.qoida.platform.configuration;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * How this platform says "restart me", and the only way it can.
 *
 * <p><b>The belief this class replaces.</b> Several places in this codebase were
 * written believing that letting an {@link Error} escape a background thread ends
 * the process, so that an orchestrator restarts it. It does not. Spring's
 * {@code ThreadPoolTaskScheduler} wraps every {@code @Scheduled} method in
 * {@code DelegatingErrorHandlingRunnable}, which catches {@code Throwable} —
 * {@code Error} included — and hands it to the scheduler's error handler; a
 * {@code @Scheduled} method throwing {@code OutOfMemoryError("Metaspace")} ticks
 * on, every two seconds, for as long as the container lives. The same is true of
 * a Kafka listener (the container's error handler catches it and the consumer
 * keeps polling), of a Camel route (the exchange carries the failure back to
 * whoever sent it), and of any task submitted to an executor. A thrown
 * {@code Error} is a value that travels; it is not a way to end anything.
 *
 * <p><b>What actually restarts this container.</b> {@code Dockerfile}'s
 * {@code HEALTHCHECK} polls {@code /actuator/health/readiness} every fifteen
 * seconds and gives up after four failures; the {@code autoheal} container in
 * {@code compose.production.yaml} watches Docker's health status and restarts
 * anything labelled {@code autoheal=true}, which {@code platform-app} is. That
 * chain — readiness DOWN, unhealthy after about a minute, restarted within
 * another fifteen seconds — is the platform's one automated recovery path, and
 * {@code readinessState} is the only thing {@code application.yml} puts in the
 * readiness group. So the way to be restarted is to publish
 * {@link ReadinessState#REFUSING_TRAFFIC}, and there is no second way worth
 * inventing.
 *
 * <p>{@link LivenessState#BROKEN} is published with it. Nothing in this
 * deployment probes {@code /actuator/health/liveness} today — the comment in
 * {@code application.yml} describes the intent and the {@code HEALTHCHECK} line
 * is what exists — but BROKEN is the accurate statement of the condition
 * ("internal state is unrecoverable, restart the process") and it costs one
 * event. If a probe is ever pointed at the liveness group, this reports
 * correctly to it without another edit here.
 *
 * <p><b>Why not close the context instead.</b> {@code SpringApplication.exit} or
 * {@code context.close()} from a scheduler thread would deadlock against
 * {@link SchedulingConfiguration}'s own
 * {@code setWaitForTasksToCompleteOnShutdown(true)} for the length of the
 * shutdown grace — the shutdown waits for the very thread calling it — and a
 * graceful shutdown on a JVM that has run out of Metaspace has to load classes
 * to do its work, which is the thing that just failed. Refusing traffic needs no
 * class loading, no thread, and no cooperation from the failing component; the
 * restart is then Docker's, which is where it belongs.
 *
 * <p><b>What does not reach here.</b> An ordinary failure — a query that timed
 * out, a provider that returned 500, one tenant's malformed row — is logged and
 * the schedule continues. That policy is right and is unchanged: a failing
 * sweeper must not take the outbox relay out of rotation, and it must certainly
 * not restart the only container on the box. Only the errors
 * {@link #isProcessFatal(Throwable)} names get here.
 */
@Component
public class ProcessHealth {

    private static final Logger log = LoggerFactory.getLogger(ProcessHealth.class);

    /**
     * A cause chain longer than this is a bug in something else, and this walk
     * runs while the process is already unwinding on a failed allocation.
     */
    private static final int MAXIMUM_CAUSE_DEPTH = 16;

    private final ApplicationEventPublisher events;

    /**
     * Published once. The condition is a property of the process rather than of
     * the task that noticed it, so twenty-eight timers all noticing the same
     * exhausted Metaspace should produce one availability change and one log
     * line, not one of each per tick for the minute it takes to be restarted.
     */
    private final AtomicBoolean reported = new AtomicBoolean();

    public ProcessHealth(ApplicationEventPublisher events) {
        this.events = events;
    }

    /**
     * Whether a failure says the process itself is finished.
     *
     * <p>Read through the cause chain, because the failure that reaches a
     * scheduler's error handler has usually been wrapped on the way: a
     * {@code NoClassDefFoundError} arrives inside an
     * {@code UndeclaredThrowableException} from a proxy, an
     * {@code OutOfMemoryError} inside whatever the JDBC driver threw when its
     * buffer allocation failed.
     *
     * <p>The line is the same one {@code media.domain.DecodeError} draws for a
     * decode, and deliberately so: a failed heap allocation is a property of the
     * one thing that asked for it, and unwinding the frame gives the memory
     * back, so the next task is as likely to succeed as if this one had never
     * run. Metaspace exhaustion, a failure to create a native thread,
     * direct-buffer exhaustion and "GC overhead limit exceeded" are process-wide
     * conditions the next task will meet too; a {@code LinkageError} or
     * {@code NoClassDefFoundError} means this build cannot run; an
     * {@code InternalError} or {@code UnknownError} means the VM is unwell.
     *
     * <p>{@code StackOverflowError} is survivable: one thread's stack was
     * exhausted and unwinding restored it exactly. {@code AssertionError} is
     * survivable too, and that is the one place this classifier is wider than
     * {@code DecodeError} — an {@code assert} or a hand-thrown
     * {@code AssertionError} in a sweeper is a defect in that sweeper, and
     * restarting the only container on the box over it would turn one bad row
     * into an outage.
     */
    public static boolean isProcessFatal(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAXIMUM_CAUSE_DEPTH; depth++) {
            if (cause instanceof Error error && !isSurvivable(error)) {
                return true;
            }
            if (cause.getCause() == cause) {
                // A hand-written getCause override may return this; Throwable's
                // own initCause forbids it, so nothing else stops the walk.
                break;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean isSurvivable(Error error) {
        if (error instanceof StackOverflowError || error instanceof AssertionError) {
            return true;
        }
        if (!(error instanceof OutOfMemoryError)) {
            return false;
        }
        String message = error.getMessage();
        if (message == null) {
            // An OutOfMemoryError with no message says nothing about which pool
            // ran out. Treated as the process being in trouble, because that is
            // the assumption whose worst case is a restart rather than a spin.
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("java heap space")
                || normalized.contains("requested array size exceeds vm limit");
    }

    /**
     * Records that this process cannot be trusted with any more work, and asks
     * to be restarted.
     *
     * <p>Returns rather than throwing, and the caller carries on. There is
     * nothing useful for it to do next — the schedule cannot be stopped from
     * inside a task, and stopping it would only hide the condition — so the
     * honest sequence is: leave the work resolvable, say the process is unwell,
     * and let the health probe and {@code autoheal} do the rest. Whatever ticks
     * in the minute before the restart fails the same way and is logged at
     * DEBUG rather than repeating this.
     *
     * @param source a stable, greppable name for where the failure surfaced,
     *               never anything derived from tenant or customer data
     * @return true when this call was the one that changed the process's state
     */
    public boolean reportFatal(String source, Throwable failure) {
        if (!reported.compareAndSet(false, true)) {
            log.debug("{} met a process-fatal error after this process was already marked "
                    + "unhealthy ({})", source, failure.getClass().getName());
            return false;
        }

        // The class name and the stack, which is what an operator reading this
        // after a restart needs. The message is left to the stack trace rather
        // than interpolated: the one at the top is the JVM's own, but a wrapped
        // cause further down may carry an object key or a filename, and this
        // line is the one that will be quoted into an incident note.
        log.error("{} met a process-fatal error ({}); this process is refusing traffic so the "
                        + "readiness probe fails and the container is restarted. In-flight work is "
                        + "settled or released; nothing is lost, and nothing more will be attempted "
                        + "by this process.",
                source, failure.getClass().getName(), failure);

        AvailabilityChangeEvent.publish(events, this, LivenessState.BROKEN);
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        return true;
    }

    /**
     * @return whether this process has already declared itself unwell, so a test
     *         or a component deciding whether to start more work can ask
     */
    public boolean isBroken() {
        return reported.get();
    }
}
