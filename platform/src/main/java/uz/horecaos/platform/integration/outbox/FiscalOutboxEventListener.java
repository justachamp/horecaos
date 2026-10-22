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
import uz.horecaos.platform.fiscal.api.FiscalEvent;
import uz.horecaos.platform.integration.events.EventCatalog;
import uz.horecaos.platform.integration.events.EventContract;

/**
 * Appends ADR 0166 fiscal facts to the outbox (ADR 0004, ADR 0032).
 *
 * <p>Mirrors {@link CustomersOutboxEventListener}: {@code BEFORE_COMMIT}, so the request and the fact that it
 * was raised commit together and a request that rolled back publishes nothing, and catalogue-first, so an
 * event with no ADR 0032 entry fails here, in the transaction that produced it, instead of reaching a topic as
 * an undocumented contract.
 *
 * <p>There is no import suppression: a correction request is raised by a person or by an amendment of a live
 * order, never by a legacy import.
 *
 * <p>The partition key is the document's id, matching {@link FiscalEvent#aggregateId()}: one receipt's
 * request and attachment stay in order on their topic.
 */
@Component
public class FiscalOutboxEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcOutboxStore outbox;
    private final ObjectMapper objectMapper;
    private final String fiscalTopic;

    public FiscalOutboxEventListener(
            JdbcOutboxStore outbox,
            ObjectMapper objectMapper,
            @Value("${horecaos.messaging.topics.fiscal-events:fiscal.events}") String fiscalTopic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.fiscalTopic = fiscalTopic.strip();
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void append(FiscalEvent event) {
        // ADR 0032: an event with no catalogue entry must not reach a topic.
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
                fiscalTopic.isBlank() ? contract.topic() : fiscalTopic,
                event.aggregateId().toString(),
                correlationId,
                null,
                event.occurredAt(),
                toJson(event.payload()),
                toJson(traceContext())));
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
            throw new IllegalArgumentException("The fiscal event cannot be serialized", exception);
        }
    }
}
