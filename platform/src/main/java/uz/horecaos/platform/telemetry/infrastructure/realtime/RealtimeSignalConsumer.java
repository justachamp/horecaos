package uz.horecaos.platform.telemetry.infrastructure.realtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;

/**
 * One consumer per replica, with {@code assign()} and seek-to-end (ADR 0045).
 *
 * <p><strong>No consumer group, no offsets, no rebalancing.</strong> Every
 * consumer-group property this codebase relies on elsewhere is wrong here, and
 * each for the same reason: a signal is ephemeral. A group would give each record
 * to <em>one</em> replica, and every replica needs every signal because each
 * holds different connections. Committed offsets would make a restarting replica
 * replay minutes of stale hints into a live map. A rebalance would pause delivery
 * to buy an ordering guarantee nothing here needs.
 *
 * <p>Seeking to the end at startup is therefore the correct behaviour and also a
 * named cost: a restarting replica loses the signals for the gap's duration, so a
 * missed stop-list signal can leave an item sellable on one operator's screen
 * until that client's next resync. ADR 0045 records that in its negative
 * consequences rather than hiding it, and the mitigation is the polling path that
 * every surface keeps.
 *
 * <p>At the pilot's scale the API is a single container and this hop is a
 * loopback — the consumer reads a record the same process published a moment ago.
 * It exists anyway, because {@code --scale api=2} is ADR 0034's stated scaling
 * move and a fan-out design that breaks at the second container is a design with
 * a hidden cliff.
 */
@Component
@ConditionalOnProperty(name = "horecaos.realtime.signals.consume", havingValue = "true", matchIfMissing = true)
public class RealtimeSignalConsumer implements DisposableBean, Runnable {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSignalConsumer.class);

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);

    /**
     * The first retry delay, and the ceiling it doubles towards.
     *
     * <p>Bounded delay rather than a bounded number of attempts. The common
     * failure is the cold-start race — {@code partitionsFor} times out because
     * the broker is not listening yet — and the second most common is a broker
     * restart, which is minutes not milliseconds. A retry budget that ran out
     * would leave the process alive with the fan-out permanently dead, which is
     * exactly the state this backoff exists to make impossible.
     */
    private static final Duration RETRY_BACKOFF_INITIAL = Duration.ofSeconds(1);

    private static final Duration RETRY_BACKOFF_MAX = Duration.ofSeconds(60);

    private final ConsumerFactory<String, String> consumers;
    private final SseStreamRegistry registry;
    private final ObjectMapper json;
    private final String topic;
    private final AtomicBoolean running = new AtomicBoolean();

    // Both null before start() and, for consumer, in the gap between one
    // connection attempt's failure and the next: there is no real Kafka
    // Consumer or worker Thread to hold before the background thread creates
    // one, and closeConsumer() explicitly nulls this out on every teardown.
    private volatile @Nullable Consumer<String, String> consumer;
    private volatile @Nullable Thread worker;

    public RealtimeSignalConsumer(
            ConsumerFactory<String, String> consumers,
            SseStreamRegistry registry,
            ObjectMapper json,
            @Value("${horecaos.messaging.topics.realtime-signals:realtime.signals}") String topic) {
        this.consumers = consumers;
        this.registry = registry;
        this.json = json;
        this.topic = topic;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this, "realtime-signals");
        // A daemon thread, because this holds nothing durable. A shutdown that
        // waited for it would wait for a poll timeout to deliver hints to sockets
        // that are already closing.
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    /**
     * Polls until shutdown, re-establishing the consumer after any failure.
     *
     * <p>The retry loop is the point. A single attempt that logged and returned
     * would be a permanent outage of every SSE push in the process, because
     * {@link #start()} fires on one {@code ContextRefreshedEvent} and would never
     * fire again — and the most likely trigger is the ordinary cold-start race
     * where the broker is not accepting connections yet. Losing the fan-out is
     * survivable for a few seconds because every surface also polls; losing it
     * for the life of the container is not, and nothing would say so.
     *
     * <p>The retry counter resets only after a successful assignment, so a
     * consumer that establishes and dies immediately still backs off rather than
     * reconnecting in a tight loop against a broker that is refusing work.
     */
    @Override
    public void run() {
        Duration backoff = RETRY_BACKOFF_INITIAL;
        try {
            while (running.get()) {
                try {
                    // Captured locally so the rest of this attempt reads a
                    // definitely-non-null consumer: the field itself stays
                    // @Nullable because closeConsumer() and the gap before the
                    // first successful connection are both real null states,
                    // observed from other threads via isRunning() and destroy().
                    Consumer<String, String> activeConsumer = createConsumer();
                    consumer = activeConsumer;
                    if (assignAndSeekToEnd(activeConsumer)) {
                        backoff = RETRY_BACKOFF_INITIAL;

                        while (running.get()) {
                            ConsumerRecords<String, String> records = activeConsumer.poll(POLL_TIMEOUT);
                            for (ConsumerRecord<String, String> record : records) {
                                parse(record.value()).ifPresent(registry::onSignal);
                            }
                        }
                        return;
                    }
                } catch (WakeupException expected) {
                    log.debug("Realtime signal consumer woken for shutdown");
                    return;
                } catch (RuntimeException failure) {
                    // Never fatal. Without Kafka every operational surface polls,
                    // which is the fallback ADR 0045 requires to keep working —
                    // but the fallback is a degraded mode, not a destination, so
                    // the connection is rebuilt rather than abandoned.
                    log.warn(
                            "Realtime signal consumption failed; operational surfaces fall back "
                                    + "to polling and this replica retries in {}",
                            backoff,
                            failure);
                } finally {
                    closeConsumer();
                }

                if (!sleepBeforeRetry(backoff)) {
                    return;
                }
                backoff = min(backoff.multipliedBy(2), RETRY_BACKOFF_MAX);
            }
        } finally {
            // Whatever ended this thread — shutdown, or an interrupt that broke
            // the backoff — the flag must say so, or start()'s guard would refuse
            // to hand a later context refresh a working consumer.
            running.set(false);
        }
    }

    /**
     * @return whether the wait completed and another attempt should be made; a
     *         shutdown or an interrupt answers false, so the thread stays
     *         stoppable and never outlives the context
     */
    private boolean sleepBeforeRetry(Duration backoff) {
        if (!running.get()) {
            return false;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return running.get();
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    /**
     * The one line that needs a broker, so the retry loop above can be exercised
     * against a failure that no test can otherwise arrange.
     */
    Consumer<String, String> createConsumer() {
        return new KafkaConsumer<>(consumerProperties(), new StringDeserializer(), new StringDeserializer());
    }

    /**
     * @return whether an assignment was made. A topic with no partitions is
     *         treated as retryable rather than accepted: polling an unassigned
     *         consumer returns empty forever, which looks identical to a quiet
     *         evening and is the state a cold start lands in when the topic has
     *         not been created yet.
     */
    private boolean assignAndSeekToEnd(Consumer<String, String> activeConsumer) {
        List<PartitionInfo> partitions = activeConsumer.partitionsFor(topic);
        if (partitions == null || partitions.isEmpty()) {
            log.warn("Topic {} has no partitions yet; retrying rather than polling an unassigned " + "consumer", topic);
            return false;
        }
        List<TopicPartition> assigned = new ArrayList<>(partitions.size());
        partitions.forEach(partition -> assigned.add(new TopicPartition(topic, partition.partition())));

        activeConsumer.assign(assigned);
        activeConsumer.seekToEnd(assigned);
        log.info(
                "Assigned {} partitions of {} and sought to the end; this replica receives only "
                        + "signals produced from now on",
                assigned.size(),
                topic);
        return true;
    }

    /**
     * A malformed or unknown record is dropped rather than retried.
     *
     * <p>There is nothing to retry into: the topic has seconds of retention and
     * the record is a hint. A channel this build does not know about is the
     * expected shape of a rolling change, and refusing to start over it would
     * make adding a channel a coordinated deploy.
     */
    java.util.Optional<RealtimeSignal> parse(String payload) {
        try {
            JsonNode node = json.readTree(payload);
            java.util.Optional<StreamChannel> channel =
                    StreamChannel.find(node.path("channel").asString());
            if (channel.isEmpty()) {
                return java.util.Optional.empty();
            }
            String resourceId = node.path("resourceId").isNull()
                    ? null
                    : node.path("resourceId").asString(null);
            Long version = node.path("version").isNull() || node.path("version").isMissingNode()
                    ? null
                    : node.path("version").asLong();

            return java.util.Optional.of(new RealtimeSignal(
                    UUID.fromString(node.path("signalId").asString()),
                    UUID.fromString(node.path("tenantId").asString()),
                    channel.get(),
                    ScopeKey.parse(node.path("scope").asString()),
                    node.path("resourceType").asString(),
                    resourceId == null ? null : UUID.fromString(resourceId),
                    version,
                    Instant.parse(node.path("occurredAt").asString())));
        } catch (RuntimeException malformed) {
            log.debug("Dropped a malformed realtime signal", malformed);
            return java.util.Optional.empty();
        }
    }

    @Override
    public void destroy() {
        running.set(false);
        Consumer<String, String> current = consumer;
        if (current != null) {
            current.wakeup();
        }
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
        }
    }

    private void closeConsumer() {
        Consumer<String, String> current = consumer;
        consumer = null;
        if (current != null) {
            try {
                current.close(Duration.ofSeconds(2));
            } catch (RuntimeException ignored) {
                // Shutting down; a consumer that will not close cleanly is not
                // worth failing a shutdown for.
            }
        }
    }

    /** Exposed for the operations dashboard: whether the fan-out hop is alive. */
    public boolean isRunning() {
        return running.get() && consumer != null;
    }

    /**
     * The broker connection, with the two group properties deliberately removed.
     *
     * <p>Built from the shared factory's configuration rather than from scratch,
     * so bootstrap servers, security, and timeouts stay in one place — and then
     * stripped, because {@code group.id} would give each record to one replica
     * and every replica needs every signal, and {@code enable.auto.commit} would
     * make a restart replay stale hints into a live map.
     */
    Map<String, Object> consumerProperties() {
        Map<String, Object> properties = new HashMap<>(consumers.getConfigurationProperties());
        properties.remove(ConsumerConfig.GROUP_ID_CONFIG);
        properties.remove(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "realtime-signals-" + UUID.randomUUID());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return properties;
    }
}
