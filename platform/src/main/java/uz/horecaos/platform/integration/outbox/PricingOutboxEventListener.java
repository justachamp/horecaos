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
import uz.horecaos.platform.pricing.api.PricingEvent;

/**
 * Appends ADR 0018 pricing facts to the outbox (ADR 0004, ADR 0032).
 *
 * <p>Mirrors {@link InventoryOutboxEventListener} exactly — same {@code
 * BEFORE_COMMIT} phase so the activation and the fact that it happened commit
 * together, same catalogue-first discipline, same import suppression. Without
 * {@code BEFORE_COMMIT} there would be a window in which a price book is live
 * and nothing downstream — provisioning, analytics, a future cache invalidator
 * — will ever hear about it.
 *
 * <p>The partition key is {@code priceBookId}, matching {@link
 * PricingEvent#aggregateId()}: every fact for one price book stays in order on
 * its topic.
 */
@Component
public class PricingOutboxEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcOutboxStore outbox;
    private final ObjectMapper objectMapper;
    private final String pricingTopic;

    public PricingOutboxEventListener(
            JdbcOutboxStore outbox,
            ObjectMapper objectMapper,
            @Value("${horecaos.messaging.topics.pricing-events:pricing.events}") String pricingTopic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.pricingTopic = pricingTopic.strip();
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void append(PricingEvent event) {
        // ADR 0032: an event with no catalogue entry must not reach a topic.
        EventContract contract = EventCatalog.require(event.eventType(), event.eventVersion());

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

    /**
     * The catalogue names the topic; the property stays an environment-level
     * override so a deployment can redirect one without a code change.
     */
    private String topicFor(EventContract contract) {
        return pricingTopic.isBlank() ? contract.topic() : pricingTopic;
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
            throw new IllegalArgumentException("The pricing event cannot be serialized", exception);
        }
    }
}
