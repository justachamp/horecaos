package uz.qoida.platform.integration.camel.pos;

import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;

import uz.qoida.platform.integration.api.pos.PosApiCall;
import uz.qoida.platform.integration.api.provider.ProviderOutcome;

/**
 * The POS route's steps, as plain Java (ADR 0007).
 *
 * <p>Here rather than in the route DSL so they are unit-testable without a Camel
 * context, and so the route reads as policy rather than as logic.
 *
 * <p>There is no reconcile step on this route, and its absence is the design. On
 * delivery, the route can reconcile because every courier exposes one canonical
 * "read this shipment" call keyed on a reference the create returned. A POS
 * export that failed uncertainly has no reference to read by — the call that
 * would have produced one is the call that failed — so the recovery is a paged
 * search over the day's orders matched on phone, time and line composition, and
 * that is a piece of business judgement rather than a transport step. It belongs
 * to the module that owns the export, and this route's whole contribution is to
 * make sure the original call is never simply repeated.
 */
@Component
public class PosProcessor {

    private static final Logger log = LoggerFactory.getLogger(PosProcessor.class);

    private final PosGateway gateway;
    private final PosCircuitBreakers breakers;
    private final MeterRegistry meters;

    public PosProcessor(PosGateway gateway, PosCircuitBreakers breakers, MeterRegistry meters) {
        this.gateway = gateway;
        this.breakers = breakers;
        this.meters = meters;
    }

    /**
     * Restores tenant and correlation context onto the MDC.
     *
     * <p>The tenant and the correlation id only. No customer phone, no address,
     * no order total: an MDC value reaches every log line the call produces, and
     * an order export body is personal data under ADR 0029.
     */
    public void restoreContext(Exchange exchange) {
        PosApiCall call = call(exchange);
        MDC.put("tenantId", call.tenantId().toString());
        MDC.put("providerType", call.providerType());
        if (call.correlationId() != null) {
            MDC.put("correlationId", call.correlationId());
        }
    }

    public void invoke(Exchange exchange) {
        PosApiCall call = call(exchange);

        ProviderOutcome outcome;
        try {
            outcome = breakers.forProvider(call.providerType()).executeSupplier(() -> {
                ProviderOutcome result = gateway.invoke(call);
                if (result.status() == ProviderOutcome.Status.RETRYABLE) {
                    // Thrown only so the breaker records it; unwrapped below, so
                    // the classified outcome survives intact.
                    throw new PosCircuitBreakers.PosCallFailed(result);
                }
                return result;
            });
        } catch (CallNotPermittedException circuitOpen) {
            // The breaker refused, so nothing was sent. Retryable rather than
            // uncertain even on an unkeyed create: the provider provably did not
            // act, and there is nothing to discover.
            outcome = ProviderOutcome.retryable("CIRCUIT_OPEN",
                    "Circuit open for " + call.providerType(), java.time.Duration.ofSeconds(30));
            count("circuit_open", call, outcome);
            log.warn("Circuit open for {}; {} not attempted", call.providerType(), call.operation());
        } catch (PosCircuitBreakers.PosCallFailed failed) {
            outcome = failed.outcome();
        }

        exchange.getIn().setHeader(PosRouteBuilder.OUTCOME_HEADER, outcome);
    }

    public void recordOutcome(Exchange exchange) {
        PosApiCall call = call(exchange);
        ProviderOutcome outcome = exchange.getIn()
                .getHeader(PosRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
        count("completed", call, outcome);
        log.info("POS call {} on {} finished as {}", call.operation(), call.providerType(),
                outcome == null ? "NONE" : outcome.status());
        PosRouteBuilder.clearContext();
    }

    /**
     * Anything that escaped classification.
     *
     * <p>Uncertain on a create, retryable otherwise — and the split matters. A
     * blanket uncertain would send every unclassified failure on a read to a
     * human queue, and a blanket retryable would let one escape to a second
     * kitchen ticket. The effect classification is the only evidence available at
     * this point, so it is what decides.
     */
    public void deadLetter(Exchange exchange) {
        PosApiCall call = call(exchange);
        Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        String detail = cause == null ? "Unknown route failure" : cause.getClass().getSimpleName();

        ProviderOutcome outcome;
        if (cause instanceof PosCircuitBreakers.PosCallFailed failed) {
            outcome = failed.outcome();
        } else if (call.effect() == PosApiCall.Effect.UNKEYED_CREATE) {
            outcome = ProviderOutcome.uncertain("UNCLASSIFIED", detail);
        } else {
            outcome = ProviderOutcome.retryable("UNCLASSIFIED", detail, null);
        }

        exchange.getIn().setHeader(PosRouteBuilder.OUTCOME_HEADER, outcome);
        count("dead_lettered", call, outcome);
        log.error("POS call {} on {} dead-lettered as {}",
                call.operation(), call.providerType(), outcome.errorCode());
        PosRouteBuilder.clearContext();
    }

    private void count(String event, PosApiCall call, ProviderOutcome outcome) {
        // Bounded tags only. A tenant id or an order id here would make the
        // cardinality unbounded and eventually take the registry down.
        meters.counter("qoida.pos.route",
                "event", event,
                "provider", call.providerType(),
                "operation", call.operation(),
                "effect", call.effect().name(),
                "status", outcome == null ? "NONE" : outcome.status().name())
                .increment();
    }

    static PosApiCall call(Exchange exchange) {
        return exchange.getIn().getBody(PosApiCall.class);
    }
}
