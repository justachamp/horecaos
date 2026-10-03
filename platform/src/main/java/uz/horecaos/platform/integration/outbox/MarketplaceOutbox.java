package uz.horecaos.platform.integration.outbox;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceAvailabilityPushedPayload;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceChannelWentStalePayload;
import uz.horecaos.platform.integration.events.EventCatalog;
import uz.horecaos.platform.integration.events.EventContract;

/**
 * The producer half of ADR 0040's marketplace facts on {@code integration.events} (ADR 0004,
 * ADR 0032): {@code MarketplaceAvailabilityPushed} and {@code MarketplaceChannelWentStale}.
 *
 * <p>In this package for the reason {@link PosSyncOutbox} is: {@code NewOutboxEvent} is
 * package-private, so appending to the outbox is this package's job. There is no
 * {@code KafkaTemplate} here, as ADR 0004 requires — each method only inserts a row, in
 * whatever transaction is already open on the caller's thread. The reconciler relies on that:
 * the row that records a confirmation and the event that announces it commit or roll back
 * together, and a worker that lost its lease writes neither.
 *
 * <p>Both events are keyed and ordered by binding ({@code MarketplaceBinding}), so one venue's
 * facts never overtake one another and one venue's poison record never holds another's up.
 */
@Component
public class MarketplaceOutbox {

    public static final String AVAILABILITY_PUSHED = "MarketplaceAvailabilityPushed";
    public static final String CHANNEL_WENT_STALE = "MarketplaceChannelWentStale";
    public static final String AGGREGATE_TYPE = "MarketplaceBinding";

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcOutboxStore outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MarketplaceOutbox(JdbcOutboxStore outbox, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** The partner is now known to hold {@code payload.available()} for one dish. */
    public void availabilityPushed(UUID tenantId, MarketplaceAvailabilityPushedPayload payload) {
        append(tenantId, payload.bindingId(), AVAILABILITY_PUSHED, payload);
    }

    /** A binding has had a dish unconfirmed for longer than its bound. */
    public void channelWentStale(UUID tenantId, MarketplaceChannelWentStalePayload payload) {
        append(tenantId, payload.bindingId(), CHANNEL_WENT_STALE, payload);
    }

    private void append(UUID tenantId, UUID bindingId, String eventType, Object payload) {
        // ADR 0032: an event with no catalogue entry must not reach a topic.
        EventContract contract = EventCatalog.require(eventType, 1);
        UUID eventId = Ids.newId();
        String key = bindingId.toString();
        outbox.append(new NewOutboxEvent(
                eventId,
                contract.eventType(),
                contract.eventVersion(),
                tenantId,
                AGGREGATE_TYPE,
                bindingId,
                contract.topic(),
                key,
                correlation(eventId),
                null,
                clock.instant(),
                objectMapper.writeValueAsString(payload),
                objectMapper.writeValueAsString(traceContext())));
    }

    private static String correlation(UUID fallback) {
        String fromContext = MDC.get(CORRELATION_ID_MDC_KEY);
        return fromContext == null || fromContext.isBlank() ? fallback.toString() : fromContext;
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
