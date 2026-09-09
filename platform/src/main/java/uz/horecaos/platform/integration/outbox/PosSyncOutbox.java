package uz.horecaos.platform.integration.outbox;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.integration.api.pos.PosSyncRequestedPayload;
import uz.horecaos.platform.integration.api.pos.PosSyncRequester;
import uz.horecaos.platform.integration.events.EventCatalog;
import uz.horecaos.platform.integration.events.EventContract;

/**
 * The producer half of ADR 0012's durable scheduler command.
 *
 * <p>In this package for the same reason {@link ShipmentReconciliationOutbox}
 * is: {@code NewOutboxEvent} is package-private, so appending to the outbox is
 * {@code integration}'s own job. {@code pos} depends on this only through
 * {@link PosSyncRequester}, resolved by Spring to this bean.
 *
 * <p>No {@code KafkaTemplate} here, exactly as ADR 0004 requires: {@link
 * #requestSync} only ever inserts a row, in whatever transaction is already
 * open on the caller's thread. The scheduler relies on that — see its own
 * class doc for why the claim and this insert must commit or roll back
 * together.
 */
@Component
public class PosSyncOutbox implements PosSyncRequester {

    /** ADR 0032 catalogue key. Resolved through the catalogue, never hard-coded twice. */
    public static final String COMMAND_EVENT_TYPE = "PosSyncRequested";

    public static final String AGGREGATE_TYPE = "PosBinding";

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcOutboxStore outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PosSyncOutbox(JdbcOutboxStore outbox, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void requestSync(PosSyncRequestedPayload command, String correlationId) {
        EventContract contract = EventCatalog.require(COMMAND_EVENT_TYPE, 1);

        // The partition key is the binding, not the request: two sync requests
        // for one binding must never overtake each other, and the ADR 0004
        // outbox's own refusal of a second PENDING/PUBLISHING/DEAD_LETTER row on
        // one partition key is what turns "must never" into a database fact
        // rather than a hope. It is also the aggregate id as text, because the
        // inbox refuses a record whose key disagrees with its aggregate.
        String key = command.bindingId().toString();

        outbox.append(new NewOutboxEvent(
                Ids.newId(),
                contract.eventType(),
                contract.eventVersion(),
                command.tenantId(),
                AGGREGATE_TYPE,
                command.bindingId(),
                contract.topic(),
                key,
                correlation(correlationId, key),
                null,
                clock.instant(),
                objectMapper.writeValueAsString(command),
                objectMapper.writeValueAsString(traceContext())));
    }

    private static String correlation(String provided, String fallback) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        String fromContext = MDC.get(CORRELATION_ID_MDC_KEY);
        return fromContext == null || fromContext.isBlank() ? fallback : fromContext;
    }

    private static Map<String, String> traceContext() {
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
}
