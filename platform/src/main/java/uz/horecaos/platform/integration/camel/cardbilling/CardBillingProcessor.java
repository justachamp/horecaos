package uz.horecaos.platform.integration.camel.cardbilling;

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
 * The card-billing route's steps, as plain Java (ADR 0007): here rather than in the route DSL so
 * they are unit-testable without a Camel context, and so the route reads as policy.
 *
 * <p>There is no redelivery and no reconcile branch. {@code card_token/payment} carries no idempotency
 * key, so a bounded redelivery of it would be a bounded number of extra charges to a cardholder. The
 * route classifies, records, and hands the outcome straight back; the adapter above it resolves an
 * uncertain charge by asking the provider what it holds, never by sending it again.
 */
@Component
public class CardBillingProcessor {

    private static final Logger log = LoggerFactory.getLogger(CardBillingProcessor.class);

    private final CardBillingGateway gateway;
    private final CardBillingCircuitBreakers breakers;
    private final MeterRegistry meters;

    public CardBillingProcessor(CardBillingGateway gateway, CardBillingCircuitBreakers breakers, MeterRegistry meters) {
        this.gateway = gateway;
        this.breakers = breakers;
        this.meters = meters;
    }

    /** Provider type and correlation only on the MDC: there is no tenant on a platform account, and nothing names a person. */
    public void restoreContext(Exchange exchange) {
        CardBillingApiCall call = call(exchange);
        MDC.put("providerType", call.providerType());
        if (call.correlationId() != null) {
            MDC.put("correlationId", call.correlationId());
        }
    }

    public void invoke(Exchange exchange) {
        CardBillingApiCall call = call(exchange);

        ProviderOutcome outcome;
        try {
            outcome = breakers.forInstallation(call.installationId()).executeSupplier(() -> {
                ProviderOutcome result = gateway.invoke(call);
                if (result.status() == ProviderOutcome.Status.RETRYABLE) {
                    // Thrown only so the breaker records it; unwrapped below, so the classified
                    // outcome survives intact.
                    throw new CardBillingCircuitBreakers.CardBillingCallFailed(result);
                }
                return result;
            });
        } catch (CallNotPermittedException circuitOpen) {
            // The breaker refused, so nothing was sent: retryable, and provably not applied.
            outcome = ProviderOutcome.retryable(
                    "CIRCUIT_OPEN", "Circuit open for a card-billing account", Duration.ofSeconds(30));
            count("circuit_open", call, outcome);
            log.warn("Circuit open for a {} account; {} not attempted", call.providerType(), call.operation());
        } catch (CardBillingCircuitBreakers.CardBillingCallFailed failed) {
            outcome = failed.outcome();
        }

        exchange.getIn().setHeader(CardBillingRouteBuilder.OUTCOME_HEADER, outcome);
    }

    public void recordOutcome(Exchange exchange) {
        CardBillingApiCall call = call(exchange);
        ProviderOutcome outcome =
                exchange.getIn().getHeader(CardBillingRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
        count("completed", call, outcome);
        CardBillingRouteBuilder.clearContext();
    }

    /**
     * Anything that escaped classification. Uncertain, not retryable: there is no evidence the provider
     * was untouched, and a charge is the call where that matters.
     */
    public void deadLetter(Exchange exchange) {
        CardBillingApiCall call = call(exchange);
        Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        String detail =
                cause == null ? "Unknown route failure" : cause.getClass().getSimpleName();

        ProviderOutcome outcome = cause instanceof CardBillingCircuitBreakers.CardBillingCallFailed failed
                ? failed.outcome()
                : ProviderOutcome.uncertain("UNCLASSIFIED", detail);

        exchange.getIn().setHeader(CardBillingRouteBuilder.OUTCOME_HEADER, outcome);
        count("dead_lettered", call, outcome);
        log.error(
                "Card-billing call {} on {} dead-lettered as {}",
                call.operation(),
                call.providerType(),
                outcome.errorCode());
        CardBillingRouteBuilder.clearContext();
    }

    private void count(String event, CardBillingApiCall call, @Nullable ProviderOutcome outcome) {
        // Bounded tags only: provider type and operation are closed sets. Never an installation id,
        // a card reference or a payment id (ADR 0029).
        meters.counter(
                        "horecaos.cardbilling.route",
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

    static CardBillingApiCall call(Exchange exchange) {
        return Objects.requireNonNull(
                exchange.getIn().getBody(CardBillingApiCall.class), "No card-billing call on the exchange body");
    }
}
