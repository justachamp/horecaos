package uz.horecaos.platform.integration.camel.delivery;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.ExternalEventEnvelope;
import uz.horecaos.platform.integration.api.ExternalWorkInboxHandler;
import uz.horecaos.platform.integration.api.delivery.DeliveryCapability;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.outbox.ShipmentReconciliationOutbox;
import uz.horecaos.platform.integration.outbox.ShipmentReconciliationOutbox.Command;
import uz.horecaos.platform.integration.outbox.ShipmentReconciliationOutbox.Settlement;

/**
 * ADR 0007's production inbox → route → outbox path, for one real command.
 *
 * <p>A courier call came back {@code UNCERTAIN} and the status query inside the
 * exchange did not settle it. The route emitted a durable command instead of
 * only logging; this is what consumes it. It asks the partner what it actually
 * holds, and writes the answer to the outbox in the transaction that also marks
 * the record processed.
 *
 * <h2>Why this command and not the booking itself</h2>
 *
 * <p>Two reasons, and both are constraints rather than preferences. A booking
 * command would have to carry the recipient's name, phone number and address to
 * be executable, and ADR 0029 keeps personal data off every topic; a status query
 * needs only the partner's own reference. And a booking is a decision fulfilment
 * owns — ADR 0007 is explicit that a route never decides whether a delivery is
 * acceptable — while "ask again what already happened" is the route deciding
 * whether to call, which is exactly its job.
 *
 * <h2>Why the effect is never repeated</h2>
 *
 * <p>The original operation is not re-sent, ever. On a partner whose create is
 * immediately live, re-sending books a second courier and bills the merchant
 * twice, which is the entire reason {@code UNCERTAIN} is a separate outcome from
 * {@code RETRYABLE}. This path only queries, and a query has no side effect, so
 * at-least-once delivery of the command costs an extra HTTP call and nothing
 * else. The inbox deduplicates on the event id in any case, so a redelivered
 * record does not reach the partner at all.
 */
@Component
public class ShipmentReconciliationHandler implements ExternalWorkInboxHandler<Command, Settlement> {

    /** Stable across restarts and deployments; it is half the deduplication key. */
    public static final String CONSUMER_NAME = "delivery-reconciliation";

    static final String CONFIRMED = "CONFIRMED";
    static final String ABSENT = "ABSENT";
    static final String UNRESOLVED = "UNRESOLVED";

    /**
     * Long enough for every partner reference either adapter has produced, short
     * enough that the field cannot be used to carry something else.
     */
    private static final int MAXIMUM_REFERENCE_LENGTH = 128;

    private static final Logger log = LoggerFactory.getLogger(ShipmentReconciliationHandler.class);

    private final ProducerTemplate producer;
    private final ProviderInstallationLookup installations;
    private final ShipmentReconciliationOutbox outbox;
    private final MeterRegistry meters;
    private final Clock clock;

    public ShipmentReconciliationHandler(
            ProducerTemplate producer,
            ProviderInstallationLookup installations,
            ShipmentReconciliationOutbox outbox,
            MeterRegistry meters,
            Clock clock) {
        this.producer = producer;
        this.installations = installations;
        this.outbox = outbox;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    @Override
    public String eventType() {
        return ShipmentReconciliationOutbox.COMMAND_EVENT_TYPE;
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Class<Command> payloadType() {
        return Command.class;
    }

    /**
     * The provider call. No transaction is open here, by contract with
     * {@link ExternalWorkInboxHandler} — a courier that has stopped answering
     * must not also hold one of ten pooled connections for the length of its
     * timeout.
     *
     * @return the settlement to record, or {@code null} when there is nothing
     *         honest to say and the record is simply done
     */
    @Override
    public Settlement perform(ExternalEventEnvelope<Command> event, Attempt attempt) {
        Command command = event.payload();

        if (!isWellFormed(command)) {
            // ADR 0007 rule 2: validate the contract before mapping. The schema
            // constrains this shape on the way out, and nothing constrains it on
            // the way in — a topic is not a trusted caller, and a reference of
            // twenty thousand characters would be handed straight to a partner's
            // URL builder. Retrying a malformed record never makes it well
            // formed, so it is recorded and finished.
            count("malformed", "unresolved", "REFUSED");
            // The aggregate id rather than the payload's own field, because the
            // payload is what is in doubt and the envelope is what was validated.
            log.error("Reconciliation command {} is malformed and was not attempted", event.aggregateId());
            return null;
        }

        Optional<BindingRef> authorized = resolve(event.tenantId(), command);
        if (authorized.isEmpty()) {
            // The envelope's tenant may not use this binding. The tenant on a
            // record is producer-controlled and the binding id is a request, not
            // an authority, so this is where a command for tenant A carrying
            // tenant B's binding stops. Recorded and finished rather than
            // retried: a command that is not authorized now will not become
            // authorized in five minutes, and no event is emitted because there
            // is no shipment of this tenant's to report on.
            // The tag is a constant, not the command's provider type. Every
            // field on a refused command is attacker-controlled, and a metric
            // tag taken from one is an unbounded cardinality hole that a
            // stream of junk provider names would use to take the registry down.
            count("binding_refused", "unresolved", "REFUSED");
            log.error(
                    "Reconciliation command {} names binding {} which tenant {} may not use",
                    command.operationCommandId(),
                    command.bindingId(),
                    event.tenantId());
            return null;
        }
        BindingRef binding = authorized.get();

        ProviderOutcome outcome = query(new DeliveryOperation(
                command.operationCommandId(),
                event.tenantId(),
                binding,
                DeliveryCapability.QUERY_SHIPMENT,
                null,
                command.externalReference(),
                null,
                event.correlationId()));

        return switch (outcome.status()) {
            // The partner holds it, so the uncertain call did take effect and
            // nothing may be re-issued.
            case SUCCESS -> settlement(command, binding, CONFIRMED, outcome, attempt);
            // The partner has no such shipment. That is a settled answer, and the
            // only one that makes re-issuing the original command safe.
            case REJECTED -> settlement(command, binding, ABSENT, outcome, attempt);
            // Still unknown. The inbox owns the backoff — a second retry policy
            // here would multiply against it — and only the last attempt settles,
            // so that an unanswerable case becomes a fact somebody can act on
            // rather than a dead letter nobody reads.
            case RETRYABLE, UNCERTAIN -> {
                if (!attempt.isLast()) {
                    count("unsettled", binding.providerType(), outcome.status().name());
                    throw new UnsettledReconciliation(outcome.errorCode());
                }
                yield settlement(command, binding, UNRESOLVED, outcome, attempt);
            }
        };
    }

    /**
     * The write. Inside the transaction that commits the inbox transition, so
     * the answer and the evidence that it was produced arrive together or not at
     * all — and through the outbox, so nothing publishes to Kafka from inside a
     * transaction (ADR 0004).
     */
    @Override
    public void record(ExternalEventEnvelope<Command> event, Settlement settlement) {
        if (settlement == null) {
            return;
        }
        outbox.appendSettlement(settlement, event.tenantId(), event.correlationId());
        count("settled", settlement.providerType(), settlement.resolution());
        log.info(
                "Shipment {} for operation {} reconciled as {}",
                settlement.externalReference(),
                settlement.operationCommandId(),
                settlement.resolution());
    }

    /**
     * Re-resolves the binding against the scope the envelope's tenant actually
     * has, mirroring {@link CamelShipmentBookingPort}.
     *
     * <p>Constrained on tenant, brand and location rather than looked up by id,
     * which is the difference between querying this tenant's partner account and
     * querying whichever account the id happened to name.
     */
    /**
     * What the command must look like before any of it is used.
     *
     * <p>Bounds rather than business rules: an identifier that is present, a
     * reference short enough to be an identifier rather than a payload, and a
     * capability that names one this platform knows. Anything failing these is a
     * record no version of this consumer was meant to handle.
     */
    private static boolean isWellFormed(Command command) {
        if (command == null
                || command.operationCommandId() == null
                || command.bindingId() == null
                || command.brandId() == null) {
            return false;
        }
        String reference = command.externalReference();
        if (reference == null || reference.isBlank() || reference.length() > MAXIMUM_REFERENCE_LENGTH) {
            return false;
        }
        return capabilityOf(command) != null;
    }

    /**
     * The command's capability as one this platform declares, or null.
     *
     * <p>It is echoed into the settlement event, whose schema closes the field to
     * an enum. Echoing the arriving string unchecked would let a record on a topic
     * decide what appears on another topic, and the failure would surface in a
     * consumer rather than here.
     */
    private static DeliveryCapability capabilityOf(Command command) {
        try {
            return DeliveryCapability.valueOf(command.capability());
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return null;
        }
    }

    private Optional<BindingRef> resolve(UUID tenantId, Command command) {
        List<BindingRef> candidates = new ArrayList<>();
        for (String code : CamelShipmentBookingPort.BOOKING_CAPABILITY_CODES) {
            candidates.addAll(installations.candidateBindings(tenantId, command.brandId(), command.locationId(), code));
        }
        return candidates.stream()
                .filter(candidate -> candidate.bindingId().equals(command.bindingId()))
                .findFirst();
    }

    private ProviderOutcome query(DeliveryOperation operation) {
        // The whole exchange rather than a body: the outcome travels as a header
        // and the dead-letter path replaces the body, so reading the body would
        // erase the classification this handler decides on.
        Exchange result = producer.request(DeliveryRouteBuilder.OPERATION_ENDPOINT, exchange -> {
            exchange.getIn().setBody(operation);
            exchange.getIn().setHeader(DeliveryProcessor.OPERATION_HEADER, operation);
        });

        ProviderOutcome outcome =
                result.getMessage().getHeader(DeliveryRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);

        return outcome == null
                ? ProviderOutcome.uncertain(
                        "ROUTE_PRODUCED_NO_OUTCOME", "The route returned without classifying the query")
                : outcome;
    }

    private Settlement settlement(
            Command command, BindingRef binding, String resolution, ProviderOutcome outcome, Attempt attempt) {

        return Settlement.at(
                clock.instant(),
                command.operationCommandId(),
                binding.bindingId(),
                binding.providerType(),
                capabilityOf(command).name(),
                command.externalReference(),
                resolution,
                outcome.status().name(),
                outcome.errorCode(),
                attempt.number());
    }

    private void count(String event, String providerType, String resolution) {
        // Bounded tags on purpose: provider type, event and resolution are small
        // closed sets. A tenant or command id here would make the cardinality
        // unbounded and eventually take the registry down.
        meters.counter(
                        "horecaos.delivery.reconciliation",
                        "event",
                        event,
                        "provider",
                        providerType == null ? "unknown" : providerType,
                        "resolution",
                        resolution)
                .increment();
    }

    /**
     * Carries an unsettled query back to the inbox so it schedules the retry.
     *
     * <p>A dedicated type because the message reaches the inbox row's
     * {@code last_error}, which operators read. It carries a classification code
     * and never a provider message: ADR 0029 keeps provider detail out of a
     * stored error as firmly as out of an event.
     */
    static final class UnsettledReconciliation extends RuntimeException {

        UnsettledReconciliation(String errorCode) {
            super(errorCode == null ? "UNCLASSIFIED" : errorCode, null, false, false);
        }
    }
}
