package uz.horecaos.platform.integration.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.integration.events.EventCatalog;
import uz.horecaos.platform.integration.events.EventContract;

/**
 * Both ends of ADR 0007's reconciliation command, on the ADR 0004 outbox.
 *
 * <p>In this package because {@code NewOutboxEvent} is package-private: appending
 * to the outbox is the integration module's own job and every writer lives here,
 * so that "who can put a record on a topic" is answerable by listing one
 * directory rather than by grepping for an insert.
 *
 * <p>Both methods write a row and nothing else. There is no {@code KafkaTemplate}
 * anywhere on this path, which is the rule ADR 0004 exists to state: a send
 * inside a business transaction publishes an event whose cause may still roll
 * back, and a send after the commit loses the event when the process dies in the
 * gap. The relay publishes what committed.
 */
@Component
public class ShipmentReconciliationOutbox {

    /** ADR 0032 catalogue keys. Resolved through the catalogue, never hard-coded twice. */
    public static final String COMMAND_EVENT_TYPE = "ShipmentReconciliationRequested";

    public static final String SETTLEMENT_EVENT_TYPE = "ShipmentOutcomeReconciled";
    public static final String AGGREGATE_TYPE = "DeliveryOperation";

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcOutboxStore outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ShipmentReconciliationOutbox(JdbcOutboxStore outbox, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Asks for a courier call's true outcome to be established later.
     *
     * <p>Called by the route when a status query inside the exchange could not
     * settle an uncertain outcome. Everything about the timing says this belongs
     * on a durable command rather than in the caller's thread: the partner has
     * just failed to answer, so the next query wants minutes rather than seconds,
     * and the caller is a sourcing tick with an order waiting behind it.
     */
    public void requestReconciliation(UUID tenantId, Command command, String correlationId) {
        EventContract contract = EventCatalog.require(COMMAND_EVENT_TYPE, 1);
        append(contract, command.operationCommandId(), tenantId, command, correlationId);
    }

    /** The settled answer, appended inside the inbox handler's transaction. */
    public void appendSettlement(Settlement settlement, UUID tenantId, String correlationId) {
        EventContract contract = EventCatalog.require(SETTLEMENT_EVENT_TYPE, 1);
        append(contract, settlement.operationCommandId(), tenantId, settlement, correlationId);
    }

    private void append(
            EventContract contract, UUID operationCommandId, UUID tenantId, Object payload, String correlationId) {

        // The partition key is the delivery command, not the reconciliation, so
        // two reconciliations for one courier call stay in order on one
        // partition. It is also the aggregate id as text, because the inbox
        // refuses a record whose key disagrees with its aggregate — that
        // agreement is what makes the ordering guarantee real rather than
        // assumed.
        String key = operationCommandId.toString();

        outbox.append(new NewOutboxEvent(
                UUID.randomUUID(),
                contract.eventType(),
                contract.eventVersion(),
                tenantId,
                AGGREGATE_TYPE,
                operationCommandId,
                contract.topic(),
                key,
                correlation(correlationId, key),
                null,
                clock.instant(),
                objectMapper.writeValueAsString(payload),
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

    /**
     * The command payload, and every field in it is an identifier or a code.
     *
     * <p>Deliberately not the delivery request. A courier command carries the
     * recipient's name, phone number and address, and ADR 0029 keeps all three
     * off every topic — which is also why this path reconciles by querying an
     * existing shipment rather than by re-sending the original operation: the
     * original could not be carried here without leaking the customer.
     *
     * @param bindingId a request, not an authority. The consumer re-resolves the
     *                  bindings its envelope's tenant may use and refuses this id
     *                  if it is not among them
     */
    public record Command(
            UUID operationCommandId,
            UUID bindingId,
            UUID brandId,
            UUID locationId,
            String providerType,
            String capability,
            String externalReference,
            String uncertainErrorCode) {}

    /**
     * The settled answer.
     *
     * @param resolution {@code CONFIRMED}, {@code ABSENT}, or {@code UNRESOLVED}.
     *                   The third is not a synonym for the second: "the partner
     *                   says there is no such shipment" and "nobody could find
     *                   out" differ by whether re-issuing the original command is
     *                   safe, which is the difference between one courier and two
     */
    public record Settlement(
            UUID operationCommandId,
            UUID bindingId,
            String providerType,
            String capability,
            String externalReference,
            String resolution,
            String providerStatus,
            String errorCode,
            int attempts,
            String reconciledAt) {

        public static Settlement at(
                Instant instant,
                UUID operationCommandId,
                UUID bindingId,
                String providerType,
                String capability,
                String externalReference,
                String resolution,
                String providerStatus,
                String errorCode,
                int attempts) {

            // ISO-8601 text rather than an Instant field, so the wire format is
            // decided here and not by whichever ObjectMapper configuration the
            // caller happens to hold. The schema says date-time; a mapper writing
            // epoch seconds would satisfy the record and fail the contract, and
            // it would fail it in a consumer rather than here.
            return new Settlement(
                    operationCommandId,
                    bindingId,
                    providerType,
                    capability,
                    externalReference,
                    resolution,
                    providerStatus,
                    errorCode,
                    attempts,
                    instant.toString());
        }
    }
}
