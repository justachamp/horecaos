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
import uz.horecaos.platform.tenancy.api.TenancyEvent;

/**
 * Appends tenancy facts — tenant, brand, and location lifecycle — to the outbox
 * (ADR 0004, ADR 0032).
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}, so the tenancy change and the fact
 * that it happened commit together. An after-commit append would leave a window
 * in which a tenant or brand exists and nothing downstream will ever hear about
 * it, which is the failure the outbox pattern exists to remove.
 *
 * <p>The partition key is the aggregate id, so every event for one tenant, brand,
 * or location stays in order on its topic.
 */
@Component
public class TenancyOutboxEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcOutboxStore outbox;
    private final ObjectMapper objectMapper;
    private final String tenancyTopic;

    public TenancyOutboxEventListener(
            JdbcOutboxStore outbox,
            ObjectMapper objectMapper,
            @Value("${horecaos.messaging.topics.tenancy-events:tenancy.events}") String tenancyTopic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.tenancyTopic = tenancyTopic.strip();
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void append(TenancyEvent event) {
        EventContract contract = EventCatalog.require(event.eventType(), event.eventVersion());

        // ADR 0024, and this is the listener the brand import actually trips. Each
        // legacy company becomes a brand through the tenancy control-plane service,
        // so importing an estate publishes one BrandCreated per company to whatever
        // consumes tenancy.events — provisioning, storefront seeding, analytics —
        // for brands that have existed for years. See the ordering listener for why
        // the suppression is here rather than in the relay.
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
     * The catalogue names the topic; the property remains an environment-level
     * override so a deployment can redirect a topic without a code change.
     */
    private String topicFor(EventContract contract) {
        return tenancyTopic.isBlank() ? contract.topic() : tenancyTopic;
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
            throw new IllegalArgumentException("The tenancy event cannot be serialized", exception);
        }
    }
}
