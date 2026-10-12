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
import uz.horecaos.platform.reviews.api.ReviewsEvent;

/**
 * Appends ADR 0159 review and service-recovery facts to the outbox (ADR 0004, ADR 0032).
 *
 * <p>Mirrors {@link CustomersOutboxEventListener} and {@link OrderingOutboxEventListener}: {@code
 * BEFORE_COMMIT}, so the review, the case or the moderation decision and the fact that it happened commit
 * together and one that rolled back publishes nothing, and catalogue-first, so an event with no ADR 0032
 * entry fails here, in the transaction that produced it, instead of reaching a topic as an undocumented
 * contract.
 *
 * <p>The partition key is the order's id, matching {@link ReviewsEvent#aggregateId()}: one order's
 * review, moderation and case facts stay in order on their topic. There is no import suppression: a
 * review is a customer's act and a case is staff work, neither of which a legacy import produces.
 */
@Component
public class ReviewsOutboxEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcOutboxStore outbox;
    private final ObjectMapper objectMapper;
    private final String reviewsTopic;

    public ReviewsOutboxEventListener(
            JdbcOutboxStore outbox,
            ObjectMapper objectMapper,
            @Value("${horecaos.messaging.topics.reviews-events:reviews.events}") String reviewsTopic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.reviewsTopic = reviewsTopic.strip();
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void append(ReviewsEvent event) {
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
                reviewsTopic.isBlank() ? contract.topic() : reviewsTopic,
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
            throw new IllegalArgumentException("The reviews event cannot be serialized", exception);
        }
    }
}
