package uz.horecaos.platform.integration.camel.delivery;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.apache.camel.Exchange;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.delivery.DeliveryCapability;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.DeliveryRequest;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.outbox.ReconciliationRequester;
import uz.horecaos.platform.integration.outbox.ShipmentReconciliationOutbox;

/**
 * The delivery route's steps, as plain Java (ADR 0007).
 *
 * <p>They live here rather than in the route DSL so they can be unit-tested
 * without a Camel context, and so the route reads as policy — retry, circuit,
 * dead-letter — instead of as logic.
 */
@Component
public class DeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);

    static final String OPERATION_HEADER = "HorecaOSDeliveryOperation";

    private final DeliveryGateway gateway;
    private final DeliveryCircuitBreakers breakers;
    private final MeterRegistry meters;
    private final ReconciliationRequester reconciliations;

    /**
     * Depends on {@link ReconciliationRequester} rather than
     * {@link ShipmentReconciliationOutbox} itself: this class only ever asks for a
     * reconciliation, never appends a settlement, and the narrower type is what
     * lets a route test substitute a recording double with no database behind it.
     */
    public DeliveryProcessor(
            DeliveryGateway gateway,
            DeliveryCircuitBreakers breakers,
            MeterRegistry meters,
            ReconciliationRequester reconciliations) {
        this.gateway = gateway;
        this.breakers = breakers;
        this.meters = meters;
        this.reconciliations = reconciliations;
    }

    /**
     * Restores tenant and correlation context onto the MDC.
     *
     * <p>Without this a route log line cannot be tied to the order that caused
     * it, which is the difference between a five-minute and a five-hour incident.
     */
    public void restoreContext(Exchange exchange) {
        DeliveryOperation operation = operation(exchange);
        MDC.put("tenantId", operation.tenantId().toString());
        MDC.put("providerType", operation.binding().providerType());
        if (operation.correlationId() != null) {
            MDC.put("correlationId", operation.correlationId());
        }
    }

    /** Contract validation before mapping, per ADR 0007 route rule 2. */
    public void validate(Exchange exchange) {
        DeliveryOperation operation = operation(exchange);
        if (!gateway.supports(operation.binding(), operation.capability())) {
            // Set as an outcome rather than thrown: an unsupported capability is
            // a sourcing answer for fulfilment, not a route failure for an
            // engineer, and throwing would dead-letter it into a human's queue.
            exchange.getIn()
                    .setHeader(
                            DeliveryRouteBuilder.OUTCOME_HEADER,
                            ProviderOutcome.rejected(
                                    "CAPABILITY_UNSUPPORTED",
                                    "%s does not support %s"
                                            .formatted(operation.binding().providerType(), operation.capability())));
            exchange.setRouteStop(true);
        }
    }

    /**
     * Calls the partner through its own circuit breaker.
     *
     * <p>The breaker is per provider type, so one partner's outage never stops
     * calls to another. It records provider faults and uncertain outcomes as
     * failures and ignores business rejections: a partner declining twenty
     * out-of-zone addresses is a partner working correctly.
     */
    public void invoke(Exchange exchange) {
        DeliveryOperation operation = operation(exchange);
        String providerType = operation.binding().providerType();

        ProviderOutcome outcome;
        try {
            outcome = breakers.forProvider(providerType).executeSupplier(() -> {
                ProviderOutcome result = dispatch(operation);
                if (result.status() == ProviderOutcome.Status.RETRYABLE
                        || result.status() == ProviderOutcome.Status.UNCERTAIN) {
                    // Thrown only so the breaker records it; unwrapped below, so
                    // the classified outcome survives intact.
                    throw new DeliveryCircuitBreakers.ProviderCallFailed(result);
                }
                return result;
            });
        } catch (CallNotPermittedException circuitOpen) {
            // The breaker refused the call, so nothing was sent. That is why this
            // is RETRYABLE and not UNCERTAIN: the provider cannot have acted, and
            // there is nothing to reconcile.
            outcome = ProviderOutcome.retryable(
                    "CIRCUIT_OPEN", "Circuit open for " + providerType, java.time.Duration.ofSeconds(30));
            count("circuit_open", operation, outcome);
            log.warn("Circuit open for {}; {} not attempted", providerType, operation.capability());
        } catch (DeliveryCircuitBreakers.ProviderCallFailed failed) {
            outcome = failed.outcome();
        }

        exchange.getIn().setHeader(DeliveryRouteBuilder.OUTCOME_HEADER, outcome);
    }

    private ProviderOutcome dispatch(DeliveryOperation operation) {
        String key = operation.idempotencyKey();
        // DeliveryOperation's compact constructor already enforces request/
        // externalReference presence per capability (requiresRequest,
        // requiresReference); the requireNonNull calls below only make that
        // existing invariant visible to the checker one switch arm at a time.
        return switch (operation.capability()) {
            case QUOTE_DELIVERY -> gateway.quote(operation.binding(), requireRequest(operation), key);
            case RESERVE_SHIPMENT, CREATE_ON_DEMAND_SHIPMENT, SCHEDULE_SHIPMENT ->
                gateway.createShipment(operation.binding(), requireRequest(operation), key);
            case CONFIRM_SHIPMENT -> gateway.confirmShipment(operation.binding(), requireReference(operation), key);
            case QUERY_CANCELLATION_COST ->
                gateway.cancellationCost(operation.binding(), requireReference(operation), key);
            case CANCEL_SHIPMENT ->
                gateway.cancelShipment(
                        operation.binding(),
                        requireReference(operation),
                        Objects.requireNonNull(operation.reason(), "CANCEL_SHIPMENT requires a reason"),
                        key);
            case QUERY_SHIPMENT, TRACK_SHIPMENT ->
                gateway.queryShipment(operation.binding(), requireReference(operation), key);
            // Neither partner reschedules, and a webhook is inbound rather than
            // a call we make. Both are rejections rather than gaps in the switch.
            case RESCHEDULE_SHIPMENT, VERIFY_DELIVERY_WEBHOOK ->
                ProviderOutcome.rejected(
                        "CAPABILITY_UNSUPPORTED", operation.capability() + " is not an outbound route operation");
        };
    }

    private static DeliveryRequest requireRequest(DeliveryOperation operation) {
        return Objects.requireNonNull(
                operation.request(), () -> operation.capability() + " requires a delivery request");
    }

    private static String requireReference(DeliveryOperation operation) {
        return Objects.requireNonNull(
                operation.externalReference(), () -> operation.capability() + " requires an external reference");
    }

    /**
     * Discovers the truth after an uncertain outcome by querying the provider.
     *
     * <p>This is the single most important behaviour in the route. The command
     * that failed is <em>not</em> repeated: on a partner whose create is
     * immediately live, repeating it books a second courier and bills the
     * merchant twice.
     */
    public void reconcile(Exchange exchange) {
        DeliveryOperation operation = operation(exchange);

        if (operation.externalReference() == null
                || operation.externalReference().isBlank()) {
            // The uncertain call was the one that would have produced the
            // reference, so there is nothing to query by. Only a human comparing
            // our command id against the partner's records can resolve this;
            // guessing either way risks a duplicate booking or a lost order.
            log.error(
                    "Uncertain delivery outcome with no external reference for command {}; "
                            + "manual reconciliation required",
                    operation.commandId());
            count("reconcile_impossible", operation, null);
            exchange.getIn()
                    .setHeader(
                            DeliveryRouteBuilder.OUTCOME_HEADER,
                            ProviderOutcome.uncertain(
                                    "RECONCILE_MANUAL", "No provider reference to reconcile against"));
            return;
        }

        ProviderOutcome actual =
                gateway.queryShipment(operation.binding(), operation.externalReference(), operation.idempotencyKey());
        exchange.getIn().setHeader(DeliveryRouteBuilder.OUTCOME_HEADER, actual);
        count("reconciled", operation, actual);

        if (actual.status() == ProviderOutcome.Status.RETRYABLE
                || actual.status() == ProviderOutcome.Status.UNCERTAIN) {
            defer(operation, actual);
        }
    }

    /**
     * Hands an unsettled outcome to the durable command path (ADR 0007).
     *
     * <p>This is the sentence in ADR 0007's error handling that had no
     * implementation: an uncertain external outcome triggers a provider
     * reconciliation command, not a blind duplicate request. Before this, a query
     * that failed twice left a log line and a caller holding an outcome nobody
     * would ever revisit — a courier possibly booked, possibly not, and no record
     * of the question anywhere durable.
     *
     * <p>It is a command rather than more in-route redelivery because the two
     * differ in what they can survive. Redelivery lives in one exchange on one
     * thread: it costs the caller its seconds, and a restart erases it. A partner
     * that has just lost a reply usually needs minutes before its status endpoint
     * agrees, which is longer than any caller should wait and longer than any
     * process can promise to stay up for.
     *
     * <p>A query is never deferred by a query. Doing so would let one uncertain
     * status check enqueue another forever, and the loop would look exactly like
     * a partner outage while being entirely self-inflicted.
     */
    private void defer(DeliveryOperation operation, ProviderOutcome unsettled) {
        if (operation.capability() == DeliveryCapability.QUERY_SHIPMENT
                || operation.capability() == DeliveryCapability.TRACK_SHIPMENT) {
            return;
        }

        // reconcile() is defer()'s only caller and it has already returned early
        // on a blank or missing reference; this makes that invariant visible here
        // too, since NullAway does not carry a check across method boundaries.
        String externalReference = Objects.requireNonNull(
                operation.externalReference(), "reconcile() only defers after confirming an external reference");

        reconciliations.requestReconciliation(
                operation.tenantId(),
                new ShipmentReconciliationOutbox.Command(
                        operation.commandId(),
                        operation.binding().bindingId(),
                        operation.binding().brandId(),
                        operation.binding().locationId(),
                        operation.binding().providerType(),
                        operation.capability().name(),
                        externalReference,
                        unsettled.errorCode()),
                operation.correlationId());

        count("reconcile_deferred", operation, unsettled);
        log.warn(
                "Delivery operation {} for command {} could not be settled in the route; "
                        + "a reconciliation command has been queued",
                operation.capability(),
                operation.commandId());
    }

    /** A retryable outcome. The route's caller re-sends the same command id. */
    public void scheduleRetry(Exchange exchange) {
        DeliveryOperation operation = operation(exchange);
        ProviderOutcome outcome =
                exchange.getIn().getHeader(DeliveryRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
        count("retry_scheduled", operation, outcome);
    }

    public void recordOutcome(Exchange exchange) {
        DeliveryOperation operation = operation(exchange);
        ProviderOutcome outcome =
                exchange.getIn().getHeader(DeliveryRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
        count("completed", operation, outcome);
        // Bodies are never logged: a delivery payload carries the recipient's
        // name, phone, and address, which ADR 0029 classifies as personal data.
        log.info(
                "Delivery operation {} on {} finished as {}",
                operation.capability(),
                operation.binding().providerType(),
                outcome == null ? "NONE" : outcome.status());
        DeliveryRouteBuilder.clearContext();
    }

    public void deadLetter(Exchange exchange) {
        DeliveryOperation operation = operation(exchange);
        Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);

        ProviderOutcome outcome = cause instanceof DeliveryCircuitBreakers.ProviderCallFailed failed
                ? failed.outcome()
                : ProviderOutcome.uncertain(
                        "UNCLASSIFIED",
                        cause == null
                                ? "Unknown route failure"
                                : cause.getClass().getSimpleName());

        exchange.getIn().setHeader(DeliveryRouteBuilder.OUTCOME_HEADER, outcome);
        count("dead_lettered", operation, outcome);
        log.error(
                "Delivery operation {} for command {} dead-lettered as {}",
                operation.capability(),
                operation.commandId(),
                outcome.errorCode());
        DeliveryRouteBuilder.clearContext();
    }

    private void count(String event, DeliveryOperation operation, @Nullable ProviderOutcome outcome) {
        // Tags are bounded on purpose: provider type, capability, and status are
        // all small closed sets. A tenant or command id here would make the
        // metric cardinality unbounded and eventually take the registry down.
        meters.counter(
                        "horecaos.delivery.route",
                        "event",
                        event,
                        "provider",
                        operation.binding().providerType(),
                        "capability",
                        operation.capability().name(),
                        "status",
                        outcome == null ? "NONE" : outcome.status().name())
                .increment();
    }

    static DeliveryOperation operation(Exchange exchange) {
        Object body = exchange.getIn().getBody();
        if (body instanceof DeliveryOperation operation) {
            return operation;
        }
        // Every step on this route runs after DeliveryRouteBuilder has placed the
        // operation on the exchange as either the body or this header; neither
        // missing is a route wiring defect, not a case a caller can recover from.
        return Objects.requireNonNull(
                exchange.getIn().getHeader(OPERATION_HEADER, DeliveryOperation.class),
                "No delivery operation on the exchange body or " + OPERATION_HEADER + " header");
    }

    /** Carries a classified outcome to the circuit breaker without losing it. */
    static final class ProviderCallFailed extends RuntimeException {

        private final transient ProviderOutcome outcome;

        ProviderCallFailed(ProviderOutcome outcome) {
            super(outcome.errorCode(), null, false, false);
            this.outcome = outcome;
        }

        ProviderOutcome outcome() {
            return outcome;
        }
    }
}
