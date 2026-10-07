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
import uz.horecaos.platform.marketing.api.MarketingEvent;
import uz.horecaos.platform.migration.api.ExternalEffect;
import uz.horecaos.platform.migration.api.ImportSuppression;

/**
 * Appends ADR 0112 marketing facts to the outbox (ADR 0004, ADR 0032).
 *
 * <p>Mirrors {@link PricingOutboxEventListener} exactly — same {@code BEFORE_COMMIT}
 * phase so a decision and the fact that it was made commit together, same
 * catalogue-first discipline, same import suppression.
 *
 * <p>The partition key is the aggregate id, matching {@link
 * MarketingEvent#aggregateId()}: every fact for one scenario (or one offer) stays in
 * order on its topic.
 */
@Component
public class MarketingOutboxEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcOutboxStore outbox;
    private final ObjectMapper objectMapper;
    private final String marketingTopic;

    public MarketingOutboxEventListener(
            JdbcOutboxStore outbox,
            ObjectMapper objectMapper,
            @Value("${horecaos.messaging.topics.marketing-events:marketing.events}") String marketingTopic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.marketingTopic = marketingTopic.strip();
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void append(MarketingEvent event) {
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
        return marketingTopic.isBlank() ? contract.topic() : marketingTopic;
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
            throw new IllegalArgumentException("The marketing event cannot be serialized", exception);
        }
    }
}
