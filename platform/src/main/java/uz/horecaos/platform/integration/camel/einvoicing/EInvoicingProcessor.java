package uz.horecaos.platform.integration.camel.einvoicing;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import org.apache.camel.Exchange;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * The e-invoicing route's steps, as plain Java (ADR 0007): here rather than in the route DSL
 * so they are unit-testable without a Camel context, and so the route reads as policy.
 *
 * <p>There is no redelivery and no reconcile branch, for the reason the payment route has
 * none: no operator documents an idempotency key on a create, so a bounded redelivery of one
 * would be a bounded number of extra invoices. The route classifies, records, and hands the
 * outcome straight back; the adapter above it resolves an uncertain create by asking the
 * operator for the document, never by sending it again.
 */
@Component
public class EInvoicingProcessor {

    private static final Logger log = LoggerFactory.getLogger(EInvoicingProcessor.class);

    private final EInvoicingGateway gateway;
    private final EInvoicingCircuitBreakers breakers;
    private final MeterRegistry meters;

    public EInvoicingProcessor(EInvoicingGateway gateway, EInvoicingCircuitBreakers breakers, MeterRegistry meters) {
        this.gateway = gateway;
        this.breakers = breakers;
        this.meters = meters;
    }

    /** Provider type and correlation only on the MDC: there is no tenant on a platform account, and nothing that names a person. */
    public void restoreContext(Exchange exchange) {
        EInvoicingApiCall call = call(exchange);
        MDC.put("providerType", call.providerType());
        if (call.correlationId() != null) {
            MDC.put("correlationId", call.correlationId());
        }
    }

    public void invoke(Exchange exchange) {
        EInvoicingApiCall call = call(exchange);

        ProviderOutcome outcome;
        try {
            outcome = breakers.forInstallation(call.installationId()).executeSupplier(() -> {
                ProviderOutcome result = gateway.invoke(call);
                if (result.status() == ProviderOutcome.Status.RETRYABLE) {
                    // Thrown only so the breaker records it; unwrapped below, so the
                    // classified outcome survives intact.
                    throw new EInvoicingCircuitBreakers.EInvoicingCallFailed(result);
                }
                return result;
            });
        } catch (CallNotPermittedException circuitOpen) {
            // The breaker refused, so nothing was sent: retryable, and provably not applied.
            outcome = ProviderOutcome.retryable(
                    "CIRCUIT_OPEN", "Circuit open for an e-invoicing account", Duration.ofSeconds(30));
            count("circuit_open", call, outcome);
            log.warn("Circuit open for a {} account; {} not attempted", call.providerType(), call.operation());
        } catch (EInvoicingCircuitBreakers.EInvoicingCallFailed failed) {
            outcome = failed.outcome();
        }

        exchange.getIn().setHeader(EInvoicingRouteBuilder.OUTCOME_HEADER, outcome);
    }

    public void recordOutcome(Exchange exchange) {
        EInvoicingApiCall call = call(exchange);
        ProviderOutcome outcome =
                exchange.getIn().getHeader(EInvoicingRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
        count("completed", call, outcome);
        EInvoicingRouteBuilder.clearContext();
    }

    /**
     * Anything that escaped classification. Uncertain, not retryable: there is no evidence the
     * operator was untouched, and an invoice create is the call where that matters.
     */
    public void deadLetter(Exchange exchange) {
        EInvoicingApiCall call = call(exchange);
        Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        String detail =
                cause == null ? "Unknown route failure" : cause.getClass().getSimpleName();

        ProviderOutcome outcome = cause instanceof EInvoicingCircuitBreakers.EInvoicingCallFailed failed
                ? failed.outcome()
                : ProviderOutcome.uncertain("UNCLASSIFIED", detail);

        exchange.getIn().setHeader(EInvoicingRouteBuilder.OUTCOME_HEADER, outcome);
        count("dead_lettered", call, outcome);
        log.error(
                "E-invoicing call {} on {} dead-lettered as {}",
                call.operation(),
                call.providerType(),
                outcome.errorCode());
        EInvoicingRouteBuilder.clearContext();
    }

    private void count(String event, EInvoicingApiCall call, @Nullable ProviderOutcome outcome) {
        // Bounded tags only: provider type and operation are closed sets. Never an installation
        // id or a document number (ADR 0029) -- an unbounded tag eventually takes the registry down.
        meters.counter(
                        "horecaos.einvoicing.route",
                        "event",
                        event,
                        "provider",
                        call.providerType(),
                        "operation",
                        call.operation(),
                        "status",
                        outcome == null ? "NONE" : outcome.status().name())
                .increment();
    }

    static EInvoicingApiCall call(Exchange exchange) {
        return Objects.requireNonNull(
                exchange.getIn().getBody(EInvoicingApiCall.class), "No e-invoicing call on the exchange body");
    }
}
