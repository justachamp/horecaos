package uz.horecaos.platform.integration.events;

import java.util.Objects;

/**
 * One registered external event contract, as required by ADR 0032.
 *
 * <p>Every event HorecaOS publishes must have an entry here before its producer
 * ships. The entry records what the catalogue in {@code docs/domains/events.md}
 * documents, so the two cannot drift without a test failing.
 */
public record EventContract(
        String eventType,
        int eventVersion,
        String producingModule,
        String topic,
        String partitionKey,
        String schemaPath,
        Retention retention,
        Classification classification,
        String description) {

    /**
     * Kafka retention class. Business facts are retained for replay and
     * reconciliation; commands and diagnostics are short lived because
     * PostgreSQL, not Kafka, is the durable work store (ADR 0004, ADR 0006).
     */
    public enum Retention {
        BUSINESS_FACT,
        COMMAND,
        DIAGNOSTIC,

        /**
         * ADR 0045's fourth class: {@code {domain}.signals}.
         *
         * <p>Seconds of retention, no replay, no business meaning, and never
         * catalogued as a fact. A signal says that something in a scope changed
         * and the reader re-reads it through the ordinary authorized API, so a
         * lost one heals at the next resync and a replayed one would push stale
         * hints into a live screen. Consumers on this class use {@code assign()}
         * with seek-to-end and no consumer group, which is the opposite of every
         * other class here and is why the class exists rather than the topic
         * being labelled {@code DIAGNOSTIC} and treated as one.
         */
        SIGNAL
    }

    /**
     * The highest data class reachable from the payload. ADR 0032 forbids
     * anything above {@link #INTERNAL} on any topic; the value is declared so
     * the intent is explicit and so a future change is visible in review.
     */
    public enum Classification {
        PUBLIC,
        INTERNAL
    }

    public EventContract {
        Objects.requireNonNull(eventType, "Event type is required");
        Objects.requireNonNull(producingModule, "Producing module is required");
        Objects.requireNonNull(topic, "Topic is required");
        Objects.requireNonNull(partitionKey, "Partition key is required");
        Objects.requireNonNull(schemaPath, "Schema path is required");
        Objects.requireNonNull(retention, "Retention is required");
        Objects.requireNonNull(classification, "Classification is required");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("Event version must be positive");
        }
    }

    public String key() {
        return eventType + ".v" + eventVersion;
    }
}
