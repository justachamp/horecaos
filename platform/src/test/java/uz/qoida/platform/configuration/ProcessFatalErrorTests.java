package uz.qoida.platform.configuration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.AvailabilityState;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import uz.qoida.platform.media.domain.DecodeError;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What actually happens when a background task throws, pinned so nobody writes
 * the old belief again.
 *
 * <p>The belief: that letting an {@code Error} out of a {@code @Scheduled}
 * method, a Kafka listener or a Camel route ends the process, so an orchestrator
 * restarts it. Code across this repository was written to it — a worker rethrew
 * a process-fatal {@code Error} with the comment "lets the JVM die so something
 * restarts it", and a whole class existed to sort the errors worth dying on from
 * the ones worth surviving. The restart never came. Spring wraps every scheduled
 * method in {@code DelegatingErrorHandlingRunnable}, which catches
 * {@code Throwable} and hands it to the scheduler's error handler, and there is
 * no rethrow behind that. The process kept ticking and the code kept believing.
 *
 * <p>These tests are the belief's replacement, stated as executable facts: the
 * schedule survives an {@code Error}, and the restart happens because
 * {@link ProcessHealth} asks for it through the readiness probe, not because
 * anything was thrown.
 *
 * <p>Driven against the real {@link SchedulingConfiguration} bean rather than a
 * hand-built scheduler. A test that built its own {@code ThreadPoolTaskScheduler}
 * would prove a property of Spring and nothing about this platform's wiring —
 * and the wiring is exactly what was wrong.
 */
class ProcessFatalErrorTests {

    /** Short enough that a handful of ticks fit in a test, long enough not to spin. */
    private static final Duration TICK = Duration.ofMillis(20);

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    @Test
    @DisplayName("a scheduled task throwing an Error keeps ticking; the throw ends nothing")
    void anErrorThrownFromAScheduledMethodDoesNotStopTheSchedule() throws InterruptedException {
        RecordingPublisher published = new RecordingPublisher();
        ProcessHealth health = new ProcessHealth(published);
        ThreadPoolTaskScheduler scheduler = schedulerWith(health);

        AtomicInteger ticks = new AtomicInteger();
        CountDownLatch ticked = new CountDownLatch(5);

        try {
            scheduler.scheduleWithFixedDelay(() -> {
                ticks.incrementAndGet();
                ticked.countDown();
                // Metaspace, not heap space: the one an image decoder's failed
                // allocation is not, and the one that means the next task will
                // meet the same wall.
                throw new OutOfMemoryError("Metaspace");
            }, TICK);

            assertThat(ticked.await(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                    .as("a task that throws an Error on every run went on running; that is the "
                            + "runtime fact, and any code that assumed otherwise is wrong")
                    .isTrue();
            assertThat(ticks.get()).isGreaterThanOrEqualTo(5);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("a process-fatal Error makes the process refuse traffic, which is what restarts it")
    void aProcessFatalErrorRefusesTrafficSoTheContainerIsRestarted() throws InterruptedException {
        RecordingPublisher published = new RecordingPublisher();
        ProcessHealth health = new ProcessHealth(published);
        ThreadPoolTaskScheduler scheduler = schedulerWith(health);

        CountDownLatch ticked = new CountDownLatch(3);

        try {
            scheduler.scheduleWithFixedDelay(() -> {
                ticked.countDown();
                throw new OutOfMemoryError("Metaspace");
            }, TICK);
            assertThat(ticked.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();

            // REFUSING_TRAFFIC is the whole mechanism: application.yml puts
            // readinessState alone in the readiness group, the Dockerfile's
            // HEALTHCHECK polls /actuator/health/readiness, and the autoheal
            // container in compose.production.yaml restarts anything Docker
            // calls unhealthy. This event is where that chain starts.
            assertThat(published.states())
                    .as("nothing else on this platform can ask to be restarted")
                    .contains(ReadinessState.REFUSING_TRAFFIC, LivenessState.BROKEN);
            assertThat(health.isBroken()).isTrue();

            // Once, however many timers meet the same exhausted Metaspace in the
            // minute before the container goes. The condition belongs to the
            // process, not to whichever task noticed it first.
            assertThat(published.states())
                    .filteredOn(ReadinessState.REFUSING_TRAFFIC::equals)
                    .as("three or more ticks fired; the availability change is published once")
                    .hasSize(1);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("an ordinary failure is logged and changes nothing about the process")
    void anOrdinaryFailureLeavesTheProcessAcceptingTraffic() throws InterruptedException {
        RecordingPublisher published = new RecordingPublisher();
        ProcessHealth health = new ProcessHealth(published);
        ThreadPoolTaskScheduler scheduler = schedulerWith(health);

        CountDownLatch ticked = new CountDownLatch(3);

        try {
            scheduler.scheduleWithFixedDelay(() -> {
                ticked.countDown();
                // One tenant's malformed row, a query that timed out, a provider
                // that answered 500. The schedule continuing is the correct
                // policy for all of them.
                throw new IllegalStateException("one tenant's row is bad");
            }, TICK);
            assertThat(ticked.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();

            assertThat(published.states())
                    .as("a sweeper failing must never take the only container out of rotation")
                    .isEmpty();
            assertThat(health.isBroken()).isFalse();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("a heap allocation failure is survivable; the rest of Error is not")
    void theClassifierDrawsTheLineWhereTheProcessIsActuallyFinished() {
        // Survivable: the allocation did not happen, nothing was retained, and
        // unwinding the frame gave the memory back.
        assertThat(ProcessHealth.isProcessFatal(new OutOfMemoryError("Java heap space"))).isFalse();
        assertThat(ProcessHealth.isProcessFatal(
                new OutOfMemoryError("Requested array size exceeds VM limit"))).isFalse();
        assertThat(ProcessHealth.isProcessFatal(new StackOverflowError())).isFalse();
        // A defect in a sweeper, not a reason to recreate the container.
        assertThat(ProcessHealth.isProcessFatal(new AssertionError("a sweeper's own bug"))).isFalse();
        assertThat(ProcessHealth.isProcessFatal(new IllegalStateException("a bad row"))).isFalse();

        // Process-wide: the next task meets the same wall.
        assertThat(ProcessHealth.isProcessFatal(new OutOfMemoryError("Metaspace"))).isTrue();
        assertThat(ProcessHealth.isProcessFatal(
                new OutOfMemoryError("unable to create native thread"))).isTrue();
        assertThat(ProcessHealth.isProcessFatal(
                new OutOfMemoryError("GC overhead limit exceeded"))).isTrue();
        assertThat(ProcessHealth.isProcessFatal(new OutOfMemoryError())).isTrue();
        assertThat(ProcessHealth.isProcessFatal(new NoClassDefFoundError("a codec"))).isTrue();
        assertThat(ProcessHealth.isProcessFatal(new InternalError("the VM is unwell"))).isTrue();
    }

    @Test
    @DisplayName("a fatal Error wrapped by a proxy or a driver is still fatal")
    void theCauseChainIsReadRatherThanTheTopFrame() {
        // What actually reaches a scheduler's error handler. A Spring proxy
        // wraps a NoClassDefFoundError in an UndeclaredThrowableException; a
        // driver wraps a failed direct-buffer allocation in whatever it throws.
        // Classifying on the top frame alone would let both through as ordinary.
        assertThat(ProcessHealth.isProcessFatal(
                new IllegalStateException("could not run the sweep",
                        new NoClassDefFoundError("a codec")))).isTrue();
        assertThat(ProcessHealth.isProcessFatal(
                new RuntimeException("wrapped", new IllegalStateException("also wrapped",
                        new OutOfMemoryError("Java heap space"))))).isFalse();
    }

    @Test
    @DisplayName("a cause chain that loops back on itself is not walked forever")
    void aSelfReferentialCauseTerminates() {
        // Throwable.initCause forbids self-reference; a hand-written getCause
        // override does not. This walk runs while the process may already be
        // unwinding on a failed allocation, so it must terminate on its own.
        Throwable looping = new IllegalStateException("wedged") {
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(ProcessHealth.isProcessFatal(looping)).isFalse();
    }

    @Test
    @DisplayName("the media decode classifier and the platform one draw the same line")
    void decodeErrorAgreesWithProcessHealth() {
        // Two classifiers exist because media.domain may not reach into
        // configuration, and drifting apart would mean a render that swallows
        // what the scheduler would restart for — or the reverse. Asserted over
        // the errors both are about; AssertionError is deliberately not among
        // them, because a decoder does not throw one and the platform-wide
        // classifier treats it as a sweeper's own bug.
        List<Error> errors = List.of(
                new OutOfMemoryError("Java heap space"),
                new OutOfMemoryError("Requested array size exceeds VM limit"),
                new OutOfMemoryError("Metaspace"),
                new OutOfMemoryError("unable to create native thread"),
                new OutOfMemoryError("GC overhead limit exceeded"),
                new OutOfMemoryError(),
                new StackOverflowError(),
                new NoClassDefFoundError("a codec"),
                new InternalError("the VM is unwell"));

        for (Error error : errors) {
            assertThat(ProcessHealth.isProcessFatal(error))
                    .as("%s: DecodeError says recoverable=%s", error,
                            DecodeError.isRecoverable(error))
                    .isEqualTo(!DecodeError.isRecoverable(error));
        }
    }

    private static ThreadPoolTaskScheduler schedulerWith(ProcessHealth health) {
        // Two threads rather than the platform's twenty-nine: this fixture runs
        // one task, and the pool size is SchedulerPoolSizeTests' subject.
        ThreadPoolTaskScheduler scheduler = new SchedulingConfiguration()
                .taskScheduler(health, 2, Duration.ofSeconds(1));
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Stands in for the context, and records what was published to it.
     *
     * <p>In production {@code ApplicationAvailabilityBean} listens for these and
     * {@code ReadinessStateHealthIndicator} reads the result; both are Boot's,
     * and re-testing them here would test Spring. What this fixture pins is that
     * the platform publishes the right state at the right moment.
     */
    private static final class RecordingPublisher implements ApplicationEventPublisher {

        private final List<AvailabilityState> states = new CopyOnWriteArrayList<>();

        @Override
        public void publishEvent(Object event) {
            if (event instanceof AvailabilityChangeEvent<?> change) {
                states.add(change.getState());
            }
        }

        List<AvailabilityState> states() {
            return List.copyOf(states);
        }
    }
}
