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
import uz.horecaos.platform.inventory.api.InventoryEvent;
import uz.horecaos.platform.migration.api.ExternalEffect;
import uz.horecaos.platform.migration.api.ImportSuppression;

/**
 * Appends ADR 0017 inventory facts to the outbox (ADR 0004, ADR 0032).
 *
 * <p>Mirrors {@link MediaOutboxEventListener} exactly — same {@code
 * BEFORE_COMMIT} phase so the toggle and the fact that it happened commit
 * together, same catalogue-first discipline, same import suppression. The
 * import suppression matters here specifically because {@code
 * InventoryService.setAvailability} is <strong>not</strong> guarded by {@code
 * ImportSuppression.refuse(ExternalEffect.INVENTORY_MOVEMENT, ...)} the way the
 * order-driven reservation calls are: ADR 0024 makes the legacy stock baseline
 * an explicit opening movement, so an import setting availability while
 * establishing that baseline is the import doing its job (see that method's own
 * comment). Left unsuppressed at this layer, an estate of thousands of
 * historical stock items would each publish an availability fact for a toggle
 * that happened years ago — the identical problem {@link MediaOutboxEventListener}
 * solves for a copied photograph, solved the same way.
 *
 * <p>The partition key is {@code variantId}, matching {@link
 * InventoryEvent#aggregateId()}: every availability fact for one variant stays
 * in order on its topic, which is what lets a cache invalidator trust the last
 * one it saw.
 */
@Component
public class InventoryOutboxEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcOutboxStore outbox;
    private final ObjectMapper objectMapper;
    private final String inventoryTopic;

    public InventoryOutboxEventListener(
            JdbcOutboxStore outbox,
            ObjectMapper objectMapper,
            @Value("${horecaos.messaging.topics.inventory-events:inventory.events}") String inventoryTopic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.inventoryTopic = inventoryTopic.strip();
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void append(InventoryEvent event) {
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
        return inventoryTopic.isBlank() ? contract.topic() : inventoryTopic;
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
            throw new IllegalArgumentException("The inventory event cannot be serialized", exception);
        }
    }
}
