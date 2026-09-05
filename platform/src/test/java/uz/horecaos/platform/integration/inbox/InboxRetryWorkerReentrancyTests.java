package uz.horecaos.platform.integration.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.integration.api.ExternalEventEnvelope;
import uz.horecaos.platform.integration.api.InboxHandler;

/**
 * {@link InboxRetryWorker} is one of the few scheduled jobs on this platform that is
 * single-instance-only <em>within one process</em>: {@link
 * InboxRetryWorker#redriveOnce()}'s own Javadoc says "overlapping passes would select
 * the same due rows and lose the race on every one of them", and guards against exactly
 * that with an in-process {@code AtomicBoolean} — a reentrancy guard, not a
 * cross-container lease, because the race it closes is between two calls inside one
 * JVM (the scheduled tick racing an operator-triggered on-demand pass, say), not
 * between two containers. Two {@code worker} replicas each running their own
 * scheduled tick are already safe on the database side, on {@code JdbcInboxStore}'s
 * own claim; this guard is the second, narrower property.
 *
 * <p>Proves the existing guard rather than adding one: if {@code
 * running.compareAndSet(false, true)} were ever deleted from {@link
 * InboxRetryWorker#redriveOnce()}, this test would fail by observing the store queried
 * twice while the first pass is still in flight.
 */
class InboxRetryWorkerReentrancyTests {

    @Test
    void aSecondConcurrentPassDoesNothingWhileTheFirstIsStillRunning() throws Exception {
        JdbcInboxStore store = mock(JdbcInboxStore.class);
        InboxExecutor executor = mock(InboxExecutor.class);
        InboxHandlerRegistry registry = new InboxHandlerRegistry(List.of(new NoOpHandler()));

        CountDownLatch enteredFirstPass = new CountDownLatch(1);
        CountDownLatch releaseFirstPass = new CountDownLatch(1);

        when(store.due(anyString(), anyInt())).thenAnswer(invocation -> {
            enteredFirstPass.countDown();
            assertThat(releaseFirstPass.await(5, TimeUnit.SECONDS))
                    .as("the first pass was released before the test's own timeout")
                    .isTrue();
            return List.of();
        });

        InboxRetryWorker worker = new InboxRetryWorker(store, executor, registry, 20);

        ExecutorService threads = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> firstPass = threads.submit(worker::redriveOnce);

            assertThat(enteredFirstPass.await(5, TimeUnit.SECONDS))
                    .as("the first pass reached the store before the second was attempted, so "
                            + "the guard was already closed")
                    .isTrue();

            // Attempted from this thread while the first pass sits inside store.due.
            // The guard must refuse it outright: no second query, no work claimed.
            assertThat(worker.redriveOnce())
                    .as("a pass attempted while another is running must be a pure no-op")
                    .isZero();

            releaseFirstPass.countDown();
            assertThat(firstPass.get(5, TimeUnit.SECONDS)).isZero();
        } finally {
            threads.shutdownNow();
        }

        verify(store, times(1)).due(anyString(), anyInt());
    }

    @Test
    void theGuardReleasesAfterAPassFinishesSoTheNextOneRunsNormally() {
        JdbcInboxStore store = mock(JdbcInboxStore.class);
        InboxExecutor executor = mock(InboxExecutor.class);
        InboxHandlerRegistry registry = new InboxHandlerRegistry(List.of(new NoOpHandler()));
        when(store.due(anyString(), anyInt())).thenReturn(List.of());

        InboxRetryWorker worker = new InboxRetryWorker(store, executor, registry, 20);

        assertThat(worker.redriveOnce()).isZero();
        assertThat(worker.redriveOnce())
                .as("the guard must not stay closed forever after a pass completes normally")
                .isZero();

        verify(store, times(2)).due(anyString(), anyInt());
    }

    /** Never invoked: {@code store.due} is stubbed to return no rows in every test here. */
    private static final class NoOpHandler implements InboxHandler<Map<String, Object>> {

        @Override
        public String consumerName() {
            return "reentrancy-test-consumer";
        }

        @Override
        public String eventType() {
            return "Noop";
        }

        @Override
        public int eventVersion() {
            return 1;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Class<Map<String, Object>> payloadType() {
            return (Class<Map<String, Object>>) (Class<?>) Map.class;
        }

        @Override
        public void handle(ExternalEventEnvelope<Map<String, Object>> event) {
            throw new AssertionError("not reachable in this test");
        }
    }
}
