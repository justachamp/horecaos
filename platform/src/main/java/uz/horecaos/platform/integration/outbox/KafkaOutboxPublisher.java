package uz.horecaos.platform.integration.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class KafkaOutboxPublisher implements OutboxPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final Duration publishTimeout;

    public KafkaOutboxPublisher(
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper,
            @Value("${horecaos.messaging.outbox.publish-timeout:10s}") Duration publishTimeout) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.publishTimeout = publishTimeout;
    }

    @Override
    public void publish(ClaimedOutboxEvent event) throws Exception {
        ExternalEventEnvelope envelope = new ExternalEventEnvelope(
                event.eventId(),
                event.eventType(),
                event.eventVersion(),
                event.tenantId(),
                event.aggregateType(),
                event.aggregateId(),
                event.correlationId(),
                event.causationId(),
                event.occurredAt(),
                objectMapper.readTree(event.traceContextJson()),
                objectMapper.readTree(event.payloadJson()));

        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.topic(),
                null,
                event.occurredAt().toEpochMilli(),
                event.partitionKey(),
                objectMapper.writeValueAsString(envelope));
        addHeader(record, "horecaos-event-id", event.eventId().toString());
        addHeader(record, "horecaos-event-type", event.eventType());
        addHeader(record, "horecaos-event-version", Integer.toString(event.eventVersion()));
        addHeader(record, "horecaos-tenant-id", event.tenantId().toString());
        addHeader(record, "horecaos-correlation-id", event.correlationId());
        addHeader(record, "content-type", "application/json");

        kafka.send(record).get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
    }

    private record ExternalEventEnvelope(
            UUID eventId,
            String eventType,
            int eventVersion,
            UUID tenantId,
            String aggregateType,
            UUID aggregateId,
            String correlationId,
            @Nullable String causationId,
            Instant occurredAt,
            JsonNode trace,
            JsonNode payload) {}
}
