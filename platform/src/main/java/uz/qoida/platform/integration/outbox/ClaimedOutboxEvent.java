package uz.qoida.platform.integration.outbox;

import java.time.Instant;
import java.util.UUID;

record ClaimedOutboxEvent(
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
        String traceContextJson,
        int attemptCount,
        UUID claimToken) { }
