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
import uz.horecaos.platform.catalog.api.CatalogEvent;
import uz.horecaos.platform.integration.events.EventCatalog;
import uz.horecaos.platform.integration.events.EventContract;
import uz.horecaos.platform.migration.api.ExternalEffect;
import uz.horecaos.platform.migration.api.ImportSuppression;

/**
 * Appends ADR 0016 catalog facts to the outbox (ADR 0004, ADR 0032).
 *
 * <p>Mirrors {@link ContentOutboxEventListener} exactly -- the same {@code BEFORE_COMMIT} phase so a draft write
 * and the fact that it happened commit together, the same catalogue-first discipline, the same import
 * suppression (a catalog import is a bulk write that must not announce itself row by row). The partition key is
 * the brand, so every change to one brand's draft stays in order on its topic. Nothing on the topic carries a
 * name, a price or a URL.
 */
@Component
public class CatalogOutboxEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcOutboxStore outbox;
    private final ObjectMapper objectMapper;
    private final String catalogTopic;

    public CatalogOutboxEventListener(
            JdbcOutboxStore outbox,
            ObjectMapper objectMapper,
            @Value("${horecaos.messaging.topics.catalog-events:catalog.events}") String catalogTopic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.catalogTopic = catalogTopic.strip();
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void append(CatalogEvent event) {
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

    /** The catalogue names the topic; the property stays an environment-level override. */
    private String topicFor(EventContract contract) {
        return catalogTopic.isBlank() ? contract.topic() : catalogTopic;
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
            throw new IllegalArgumentException("The catalog event cannot be serialized", exception);
        }
    }
}
