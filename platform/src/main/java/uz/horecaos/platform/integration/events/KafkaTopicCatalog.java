package uz.horecaos.platform.integration.events;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The deliberately provisioned Kafka topics required by ADR 0032.
 *
 * <p>This is intentionally separate from the event catalogue: many event
 * contracts share one topic, while the broker needs one set of physical
 * properties for that topic. Keeping those properties here gives deployment,
 * tests, and documentation one code-owned answer rather than three lists that
 * can drift.
 */
public final class KafkaTopicCatalog {

    /** Business facts are retained for the seven-day replay window in ADR 0006. */
    private static final Duration BUSINESS_FACT_RETENTION = Duration.ofDays(7);

    /** Commands are durable in PostgreSQL; Kafka only has to survive a restart. */
    private static final Duration COMMAND_RETENTION = Duration.ofDays(1);

    /** Signals are live hints, never replayable state (ADR 0045). */
    private static final Duration SIGNAL_RETENTION = Duration.ofMinutes(1);

    public static final String TENANCY_EVENTS = "tenancy.events";
    public static final String ORDERING_EVENTS = "ordering.events";
    public static final String MEDIA_EVENTS = "media.events";
    public static final String FULFILLMENT_COMMANDS = "fulfillment.commands";
    public static final String FULFILLMENT_EVENTS = "fulfillment.events";
    public static final String REALTIME_SIGNALS = "realtime.signals";

    private static final Map<String, TopicSpecification> TOPICS = index(List.of(
            // The production topology has one broker (ADR 0034), so replication
            // factor one is explicit rather than an accidental broker default.
            new TopicSpecification(TENANCY_EVENTS, 3, (short) 1, BUSINESS_FACT_RETENTION),
            new TopicSpecification(ORDERING_EVENTS, 12, (short) 1, BUSINESS_FACT_RETENTION),
            new TopicSpecification(MEDIA_EVENTS, 6, (short) 1, BUSINESS_FACT_RETENTION),
            new TopicSpecification(FULFILLMENT_COMMANDS, 3, (short) 1, COMMAND_RETENTION),
            new TopicSpecification(FULFILLMENT_EVENTS, 6, (short) 1, BUSINESS_FACT_RETENTION),
            new TopicSpecification(REALTIME_SIGNALS, 3, (short) 1, SIGNAL_RETENTION)));

    private KafkaTopicCatalog() {}

    public static Collection<TopicSpecification> all() {
        return TOPICS.values();
    }

    public static Optional<TopicSpecification> find(String name) {
        return Optional.ofNullable(TOPICS.get(name));
    }

    public static TopicSpecification require(String name) {
        return find(name)
                .orElseThrow(() ->
                        new IllegalArgumentException("No ADR 0032 topic specification is registered for " + name));
    }

    private static Map<String, TopicSpecification> index(List<TopicSpecification> topics) {
        Map<String, TopicSpecification> byName = new LinkedHashMap<>();
        for (TopicSpecification topic : topics) {
            TopicSpecification previous = byName.put(topic.name(), topic);
            if (previous != null) {
                throw new IllegalStateException("Duplicate Kafka topic specification: " + topic.name());
            }
        }
        return Map.copyOf(byName);
    }

    /** One broker-level topic policy, including its destructive-retention boundary. */
    public record TopicSpecification(String name, int partitions, short replicationFactor, Duration retention) {

        public TopicSpecification {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Topic name is required");
            }
            if (partitions < 1) {
                throw new IllegalArgumentException("Topic needs at least one partition");
            }
            if (replicationFactor < 1) {
                throw new IllegalArgumentException("Topic needs at least one replica");
            }
            if (retention == null || retention.isNegative() || retention.isZero()) {
                throw new IllegalArgumentException("Topic retention must be positive");
            }
        }

        /** Every ADR 0032 topic expires records; compacted state belongs in PostgreSQL. */
        public String cleanupPolicy() {
            return "delete";
        }
    }
}
