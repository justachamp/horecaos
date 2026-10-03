package uz.horecaos.platform.integration.camel.marketplace;

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
import uz.horecaos.platform.integration.api.marketplace.MarketplaceApiCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * The marketplace route's steps, as plain Java (ADR 0007): here rather than in the route DSL
 * so they are unit-testable without a Camel context, and so the route reads as policy.
 *
 * <p>There is no redelivery and no reconcile branch. An availability push is a value-setting
 * write, so it is <em>safe</em> to send again — but whether to, and with which value, is the
 * reconciler's decision from the platform's current truth, not the route's from the value it
 * happened to be carrying when it failed. Retrying here would resend an old value after the
 * dish had been stopped again; the route returns the classified outcome and the level-
 * triggered reconciler sends whatever is true by then.
 */
@Component
public class MarketplaceProcessor {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceProcessor.class);

    private final MarketplaceGateway gateway;
    private final MarketplaceCircuitBreakers breakers;
    private final MeterRegistry meters;

    public MarketplaceProcessor(MarketplaceGateway gateway, MarketplaceCircuitBreakers breakers, MeterRegistry meters) {
        this.gateway = gateway;
        this.breakers = breakers;
        this.meters = meters;
    }

    /** Tenant and correlation only on the MDC — nothing that names a customer. */
    public void restoreContext(Exchange exchange) {
        MarketplaceApiCall call = call(exchange);
        MDC.put("tenantId", call.tenantId().toString());
        MDC.put("providerType", call.providerType());
        if (call.correlationId() != null) {
            MDC.put("correlationId", call.correlationId());
        }
    }

    public void invoke(Exchange exchange) {
        MarketplaceApiCall call = call(exchange);

        ProviderOutcome outcome;
        try {
            outcome = breakers.forBinding(call.bindingId()).executeSupplier(() -> {
                ProviderOutcome result = gateway.invoke(call);
                if (result.status() == ProviderOutcome.Status.RETRYABLE) {
                    // Thrown only so the breaker records it; unwrapped below, so the
                    // classified outcome survives intact.
                    throw new MarketplaceCircuitBreakers.MarketplaceCallFailed(result);
                }
                return result;
            });
        } catch (CallNotPermittedException circuitOpen) {
            // The breaker refused, so nothing was sent: retryable, and provably not applied.
            outcome = ProviderOutcome.retryable(
                    "CIRCUIT_OPEN", "Circuit open for a marketplace binding", Duration.ofSeconds(30));
            count("circuit_open", call, outcome);
            log.warn("Circuit open for a {} binding; {} not attempted", call.providerType(), call.operation());
        } catch (MarketplaceCircuitBreakers.MarketplaceCallFailed failed) {
            outcome = failed.outcome();
        }

        exchange.getIn().setHeader(MarketplaceRouteBuilder.OUTCOME_HEADER, outcome);
    }

    public void recordOutcome(Exchange exchange) {
        MarketplaceApiCall call = call(exchange);
        ProviderOutcome outcome =
                exchange.getIn().getHeader(MarketplaceRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
        count("completed", call, outcome);
        MarketplaceRouteBuilder.clearContext();
    }

    /**
     * Anything that escaped classification. Retryable with no conclusion that the partner was
     * untouched: {@code UNCLASSIFIED} is not in {@code PushConclusion}'s "nothing was written"
     * set, so the reconciler treats it as unknown and resends the current truth.
     */
    public void deadLetter(Exchange exchange) {
        MarketplaceApiCall call = call(exchange);
        Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        String detail =
                cause == null ? "Unknown route failure" : cause.getClass().getSimpleName();

        ProviderOutcome outcome = cause instanceof MarketplaceCircuitBreakers.MarketplaceCallFailed failed
                ? failed.outcome()
                : ProviderOutcome.retryable("UNCLASSIFIED", detail, null);

        exchange.getIn().setHeader(MarketplaceRouteBuilder.OUTCOME_HEADER, outcome);
        count("dead_lettered", call, outcome);
        log.error(
                "Marketplace call {} on {} dead-lettered as {}",
                call.operation(),
                call.providerType(),
                outcome.errorCode());
        MarketplaceRouteBuilder.clearContext();
    }

    private void count(String event, MarketplaceApiCall call, @Nullable ProviderOutcome outcome) {
        // Bounded tags only: provider type and operation are closed sets. Never a tenant, a
        // binding or an item id (ADR 0029) -- an unbounded tag eventually takes the registry down.
        meters.counter(
                        "horecaos.marketplace.route",
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

    static MarketplaceApiCall call(Exchange exchange) {
        return Objects.requireNonNull(
                exchange.getIn().getBody(MarketplaceApiCall.class), "No marketplace call on the exchange body");
    }
}
