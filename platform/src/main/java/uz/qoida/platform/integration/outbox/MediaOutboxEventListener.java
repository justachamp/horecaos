package uz.qoida.platform.integration.outbox;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import uz.qoida.platform.integration.events.EventCatalog;
import uz.qoida.platform.integration.events.EventContract;
import uz.qoida.platform.media.api.MediaEvent;
import uz.qoida.platform.migration.api.ExternalEffect;
import uz.qoida.platform.migration.api.ImportSuppression;

/**
 * Appends ADR 0010 media facts to the outbox (ADR 0004, ADR 0032).
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}, so the asset becoming displayable
 * and the fact that it did commit together. An after-commit append would leave a
 * window in which an image is live and nothing downstream will ever hear about
 * it, which is the failure the outbox pattern exists to remove.
 *
 * <p>The partition key is the asset id, so every event for one asset stays in
 * order on its topic. Keying on the tenant would let one asset's availability
 * overtake another's deletion within the same tenant, which is exactly the kind
 * of reorder a cache invalidator cannot recover from.
 */
@Component
public class MediaOutboxEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcOutboxStore outbox;
    private final ObjectMapper objectMapper;
    private final String mediaTopic;

    public MediaOutboxEventListener(
            JdbcOutboxStore outbox,
            ObjectMapper objectMapper,
            @Value("${qoida.messaging.topics.media-events:media.events}") String mediaTopic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.mediaTopic = mediaTopic.strip();
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void append(MediaEvent event) {
        // ADR 0032: an event with no catalogue entry must not reach a topic.
        // Checked before the import suppression below, so an import cannot be a
        // way to smuggle an uncatalogued contract past the registry.
        EventContract contract = EventCatalog.require(event.eventType(), event.eventVersion());

        // ADR 0024. A legacy image copied into the object store finalizes through
        // the same path a real upload does, so an estate of forty thousand
        // photographs would otherwise publish forty thousand availability facts
        // for images that have been on menus for years. The derivative job is
        // written regardless — the renditions genuinely are owed — and only the
        // external announcement is suppressed.
        if (ImportSuppression.suppress(ExternalEffect.OUTBOX_PUBLICATION,
                event.aggregateType(), event.aggregateId())) {
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
        return mediaTopic.isBlank() ? contract.topic() : mediaTopic;
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
            throw new IllegalArgumentException("The media event cannot be serialized", exception);
        }
    }
}
