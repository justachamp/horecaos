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
import uz.horecaos.platform.migration.api.ExternalEffect;
import uz.horecaos.platform.migration.api.ImportSuppression;
import uz.horecaos.platform.ordering.api.OrderingEvent;

/**
 * Appends ADR 0019 order facts to the outbox (ADR 0004, ADR 0032).
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}, so the order and the fact that it
 * happened commit together. An after-commit append would leave a window in which
 * an order exists and nothing downstream will ever hear about it, which is the
 * failure the outbox pattern exists to remove.
 *
 * <p>The partition key is the order id, so every event for one order stays in
 * order on its topic. A key of the location or the tenant would let a
 * confirmation overtake the received event for the same order.
 */
@Component
public class OrderingOutboxEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcOutboxStore outbox;
    private final ObjectMapper objectMapper;
    private final String orderingTopic;

    public OrderingOutboxEventListener(
            JdbcOutboxStore outbox,
            ObjectMapper objectMapper,
            @Value("${horecaos.messaging.topics.ordering-events:ordering.events}") String orderingTopic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.orderingTopic = orderingTopic.strip();
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void append(OrderingEvent event) {
        // ADR 0032: an event with no catalogue entry must not reach a topic. This
        // fails the checkout that produced it, which is the correct direction —
        // an undocumented contract in production is harder to withdraw than an
        // order that was never taken.
        EventContract contract = EventCatalog.require(event.eventType(), event.eventVersion());

        // ADR 0024. The catalogue check above runs first and on purpose: an import
        // must not be a way to smuggle an uncatalogued contract past ADR 0032, and
        // the flag suppresses external effects rather than validation.
        //
        // Suppressed here, at the append, and not in OutboxRelay. The relay runs on
        // a scheduler thread where the ScopedValue binding does not exist, so by
        // the time a row reaches it there is nothing left to tell it apart from a
        // real one. A historical order that lands in the outbox is published.
        if (ImportSuppression.suppress(ExternalEffect.OUTBOX_PUBLICATION, event.aggregateType(), event.aggregateId())) {
            return;
        }

        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = event.eventId().toString();
        }

        outbox.append(new NewOutboxEvent(
                event.eventId(),
                event.eventType(),
                event.eventVersion(),
                event.tenantId().value(),
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

    /**
     * The catalogue names the topic; the property stays an environment-level
     * override so a deployment can redirect one without a code change.
     */
    private String topicFor(EventContract contract) {
        return orderingTopic.isBlank() ? contract.topic() : orderingTopic;
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
            throw new IllegalArgumentException("The ordering event cannot be serialized", exception);
        }
    }
}
