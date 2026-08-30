package uz.horecaos.platform.telemetry.infrastructure.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR 0045's fan-out has one thread and one chance to start: {@code start()}
 * fires on a single {@code ContextRefreshedEvent}. A consumer that gave up after
 * its first failure would therefore leave every SSE push in the process dead
 * until the container was replaced, and the likeliest trigger is the ordinary
 * cold start, where the broker is not accepting connections yet.
 *
 * <p>The polling fallback means nothing raises an alarm when that happens, which
 * is what makes it worth a test rather than an assumption.
 */
class RealtimeSignalConsumerRestartTests {

    private static final String TOPIC = "realtime.signals";

    private RestartableConsumer consumer;

    @AfterEach
    void stop() {
        if (consumer != null) {
            consumer.destroy();
            awaitUntil(() -> !consumer.isRunning());
        }
    }

    @Test
    void aColdStartTimeoutIsRetriedRatherThanEndingTheFanOut() {
        consumer = new RestartableConsumer(1);

        consumer.start();

        awaitUntil(() -> consumer.attempts.get() >= 2);
        assertThat(consumer.isRunning())
                .as("a transient broker failure must not disable realtime push for the life of " + "the process")
                .isTrue();
    }

    @Test
    void aFailureThatNeverClearsKeepsRetryingWithoutSpinning() {
        consumer = new RestartableConsumer(Integer.MAX_VALUE);

        consumer.start();
        awaitUntil(() -> consumer.attempts.get() >= 2);
        int afterFirstBackoff = consumer.attempts.get();

        // The delay doubles, so a second window of the same length cannot produce
        // as many attempts as the first. Without a backoff this would be a thread
        // reconnecting to a refusing broker as fast as the socket allows.
        sleep(Duration.ofMillis(1500));
        assertThat(consumer.attempts.get() - afterFirstBackoff).isLessThanOrEqualTo(2);
    }

    @Test
    void aTopicWithNoPartitionsIsRetriedRatherThanPolledForever() {
        consumer = new RestartableConsumer(0) {
            @Override
            Consumer<String, String> createConsumer() {
                attempts.incrementAndGet();
                // partitionsFor answers empty until the topic is created, and an
                // unassigned consumer polls empty forever without complaining.
                return new SlowPollingMockConsumer();
            }
        };

        consumer.start();

        awaitUntil(() -> consumer.attempts.get() >= 2);
    }

    @Test
    void shutdownDuringBackoffStopsTheThread() {
        consumer = new RestartableConsumer(Integer.MAX_VALUE);
        consumer.start();
        awaitUntil(() -> consumer.attempts.get() >= 1);

        Instant before = Instant.now();
        consumer.destroy();
        awaitUntil(() -> !consumer.isRunning());

        assertThat(Duration.between(before, Instant.now()))
                .as("the retry must never create a thread that outlives the context")
                .isLessThan(Duration.ofSeconds(5));
    }

    /** Fails {@code failures} times, then hands back a working consumer. */
    private static class RestartableConsumer extends RealtimeSignalConsumer {

        final AtomicInteger attempts = new AtomicInteger();
        private final int failures;

        RestartableConsumer(int failures) {
            super(
                    new DefaultKafkaConsumerFactory<>(Map.of()),
                    new SseStreamRegistry(
                            JsonMapper.builder().build(), Clock.systemUTC(), new SimpleMeterRegistry(), List.of()),
                    JsonMapper.builder().build(),
                    TOPIC);
            this.failures = failures;
        }

        @Override
        Consumer<String, String> createConsumer() {
            if (attempts.getAndIncrement() < failures) {
                throw new TimeoutException("no broker is listening yet");
            }
            SlowPollingMockConsumer mock = new SlowPollingMockConsumer();
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            mock.updatePartitions(
                    TOPIC,
                    List.of(new PartitionInfo(
                            TOPIC, 0, Node.noNode(), new Node[] {Node.noNode()}, new Node[] {Node.noNode()})));
            mock.updateBeginningOffsets(Map.of(partition, 0L));
            mock.updateEndOffsets(Map.of(partition, 0L));
            return mock;
        }
    }

    /**
     * {@link MockConsumer#poll} returns instantly, which would turn the poll loop
     * into a spin for the duration of a test. Honouring the timeout keeps the
     * test's CPU cost in proportion to what it is asserting.
     */
    private static class SlowPollingMockConsumer extends MockConsumer<String, String> {

        SlowPollingMockConsumer() {
            super("latest");
        }

        @Override
        public ConsumerRecords<String, String> poll(Duration timeout) {
            // Outside the monitor MockConsumer holds, so a wakeup() from the
            // shutdown path is not made to wait for a sleeping poll.
            try {
                Thread.sleep(timeout);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new WakeupException();
            }
            return super.poll(timeout);
        }
    }

    private static void awaitUntil(BooleanSupplier condition) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(25));
        }
        throw new AssertionError("Condition was not met within 15 seconds");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
