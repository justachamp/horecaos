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
import uz.horecaos.platform.customers.api.CustomersEvent;
import uz.horecaos.platform.integration.events.EventCatalog;
import uz.horecaos.platform.integration.events.EventContract;

/**
 * Appends ADR 0111 lead facts to the outbox (ADR 0004, ADR 0032).
 *
 * <p>Mirrors {@link PricingOutboxEventListener} and {@link InventoryOutboxEventListener}: {@code
 * BEFORE_COMMIT}, so the lead and the fact that it changed commit together and a lead that rolled
 * back publishes nothing, and catalogue-first, so an event with no ADR 0032 entry fails here, in the
 * transaction that produced it, instead of reaching a topic as an undocumented contract.
 *
 * <p>There is no import suppression: leads are not something a legacy import produces, and a lead
 * the migration program creates would be a lead somebody should be told about.
 *
 * <p>The partition key is the lead's id, matching {@link CustomersEvent#aggregateId()}: one lead's
 * registration, hand-off and conversion stay in order on their topic.
 */
@Component
public class CustomersOutboxEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcOutboxStore outbox;
    private final ObjectMapper objectMapper;
    private final String customersTopic;

    public CustomersOutboxEventListener(
            JdbcOutboxStore outbox,
            ObjectMapper objectMapper,
            @Value("${horecaos.messaging.topics.customers-events:customers.events}") String customersTopic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.customersTopic = customersTopic.strip();
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void append(CustomersEvent event) {
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
                customersTopic.isBlank() ? contract.topic() : customersTopic,
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
            throw new IllegalArgumentException("The customers event cannot be serialized", exception);
        }
    }
}
