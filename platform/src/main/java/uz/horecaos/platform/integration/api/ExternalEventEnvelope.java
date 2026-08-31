package uz.horecaos.platform.integration.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A validated inbound event (ADR 0005).
 *
 * <p>Every field a consumer is allowed to trust comes from here. A handler must
 * use the envelope tenant as its verified context and never a tenant taken from
 * the payload or a header, because those are producer-controlled and a payload
 * that disagrees with its envelope is exactly what a cross-tenant attack looks
 * like.
 *
 * @param <T> the version-specific payload type
 */
public record ExternalEventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        UUID tenantId,
        String aggregateType,
        UUID aggregateId,
        String correlationId,
        @Nullable String causationId,
        Instant occurredAt,
        T payload,
        String payloadSha256,
        TransportContext transport) {

    public ExternalEventEnvelope {
        Objects.requireNonNull(eventId, "An event id is required");
        Objects.requireNonNull(eventType, "An event type is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(aggregateType, "An aggregate type is required");
        Objects.requireNonNull(aggregateId, "An aggregate id is required");
        Objects.requireNonNull(correlationId, "A correlation id is required");
        Objects.requireNonNull(occurredAt, "An occurrence time is required");
        Objects.requireNonNull(payloadSha256, "A payload hash is required");
        Objects.requireNonNull(transport, "Transport context is required");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("An event version must be positive");
        }
    }

    /** Where the event physically arrived from, for diagnostics and replay. */
    public record TransportContext(String topic, int partition, long offset, String recordKey) {

        public TransportContext {
            Objects.requireNonNull(topic, "A topic is required");
            if (partition < 0) {
                throw new IllegalArgumentException("A partition must not be negative");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("An offset must not be negative");
            }
        }
    }
}
