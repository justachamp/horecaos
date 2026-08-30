package uz.qoida.platform.integration.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

record NewOutboxEvent(
        UUID eventId,
        String eventType,
        int eventVersion,
        UUID tenantId,
        String aggregateType,
        UUID aggregateId,
        String topic,
        String partitionKey,
        String correlationId,
        String causationId,
        Instant occurredAt,
        String payloadJson,
        String traceContextJson) {

    NewOutboxEvent {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(eventType, "Event type is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(aggregateType, "Aggregate type is required");
        Objects.requireNonNull(aggregateId, "Aggregate ID is required");
        Objects.requireNonNull(topic, "Topic is required");
        Objects.requireNonNull(partitionKey, "Partition key is required");
        Objects.requireNonNull(correlationId, "Correlation ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(payloadJson, "Payload is required");
        Objects.requireNonNull(traceContextJson, "Trace context is required");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("Event version must be positive");
        }
    }
}
