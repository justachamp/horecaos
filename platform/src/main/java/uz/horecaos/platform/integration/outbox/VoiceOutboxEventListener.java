package uz.horecaos.platform.integration.outbox;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.integration.events.EventCatalog;
import uz.horecaos.platform.integration.events.EventContract;
import uz.horecaos.platform.voice.api.VoiceEvent;

/**
 * Appends ADR 0064 call-event facts to the outbox (ADR 0004, ADR 0032),
 * mirroring {@link OrderingOutboxEventListener} exactly — same phase, same
 * catalogue-first discipline, same reason for {@code BEFORE_COMMIT}.
 *
 * <p>The partition key is {@link VoiceEvent#callCorrelationId()}, not {@link
 * VoiceEvent#callEventId()}: see that method's own doc for why a call's own
 * OFFERED/ANSWERED/ENDED events would otherwise race each other on the topic.
 */
@Component
public class VoiceOutboxEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcOutboxStore outbox;
    private final ObjectMapper objectMapper;
    private final String voiceTopic;

    public VoiceOutboxEventListener(
            JdbcOutboxStore outbox,
            ObjectMapper objectMapper,
            @Value("${horecaos.messaging.topics.voice-events:voice.events}") String voiceTopic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.voiceTopic = voiceTopic.strip();
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void append(VoiceEvent event) {
        EventContract contract = EventCatalog.require(event.eventType(), event.eventVersion());

        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = event.eventId().toString();
        }

        outbox.append(new NewOutboxEvent(
                event.eventId(),
                event.eventType(),
                event.eventVersion(),
                event.tenantId(),
                event.aggregateType(),
                event.aggregateId(),
                topicFor(contract),
                event.aggregateId().toString(),
                correlationId,
                null,
                event.occurredAt(),
                toJson(event.payload()),
                toJson(traceContext())));
    }

    private String topicFor(EventContract contract) {
        return voiceTopic.isBlank() ? contract.topic() : voiceTopic;
    }

    private Map<String, String> traceContext() {
        Map<String, String> trace = new LinkedHashMap<>();
        addIfPresent(trace, "traceId", MDC.get("traceId"));
        addIfPresent(trace, "spanId", MDC.get("spanId"));
        return trace;
    }

    private static void addIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("The voice event cannot be serialized", exception);
        }
    }
}
