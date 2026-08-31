package uz.horecaos.platform.integration.outbox;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
        @Nullable String causationId,
        Instant occurredAt,
        String payloadJson,
        String traceContextJson,
        int attemptCount,
        UUID claimToken) {}
