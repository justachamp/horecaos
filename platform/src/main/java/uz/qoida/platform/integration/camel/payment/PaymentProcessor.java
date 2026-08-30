package uz.qoida.platform.integration.camel.payment;

import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;

import uz.qoida.platform.integration.api.payment.MerchantApiCall;
import uz.qoida.platform.integration.api.provider.ProviderOutcome;

/**
 * The payment route's steps, as plain Java (ADR 0007).
 *
 * <p>Here rather than in the route DSL so they are unit-testable without a Camel
 * context, and so the route reads as policy rather than as logic.
 *
 * <p>There is no reconcile step on this route, and its absence is the design. On
 * delivery, the route can reconcile because every partner exposes one canonical
 * "read this shipment" call the route can name. Payments has no such call: Click
 * resolves an uncertain outcome by {@code status_by_mti} keyed on the merchant
 * transaction id and a business date, then {@code payment/status} on whatever
 * payment that names, and only the adapter holds both. So an uncertain outcome is
 * returned to the adapter, which owns the resolution — and the route's whole
 * contribution is to make sure the original call is never simply repeated.
 */
@Component
public class PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);

    private final PaymentGateway gateway;
    private final PaymentCircuitBreakers breakers;
    private final MeterRegistry meters;

    public PaymentProcessor(PaymentGateway gateway, PaymentCircuitBreakers breakers,
            MeterRegistry meters) {
        this.gateway = gateway;
        this.breakers = breakers;
        this.meters = meters;
    }

    /**
     * Restores tenant and correlation context onto the MDC.
     *
     * <p>The tenant and the correlation id only. No merchant transaction id, no
     * amount, no provider reference: an MDC value ends up on every log line the
     * call produces, and ADR 0029 keeps a payment payload out of all of them.
     */
    public void restoreContext(Exchange exchange) {
        MerchantApiCall call = call(exchange);
        MDC.put("tenantId", call.tenantId().toString());
        MDC.put("providerType", call.providerType());
        if (call.correlationId() != null) {
            MDC.put("correlationId", call.correlationId());
        }
    }

    public void invoke(Exchange exchange) {
        MerchantApiCall call = call(exchange);

        ProviderOutcome outcome;
        try {
            outcome = breakers.forProvider(call.providerType()).executeSupplier(() -> {
                ProviderOutcome result = gateway.invoke(call);
                if (result.status() == ProviderOutcome.Status.RETRYABLE) {
                    // Thrown only so the breaker records it; unwrapped below, so
                    // the classified outcome survives intact.
                    throw new PaymentCircuitBreakers.PaymentCallFailed(result);
                }
                return result;
            });
        } catch (CallNotPermittedException circuitOpen) {
            // The breaker refused, so nothing was sent. That is why this is
            // retryable and not uncertain even on a mutating call: the provider
            // provably did not act, and there is nothing to reconcile.
            outcome = ProviderOutcome.retryable("CIRCUIT_OPEN",
                    "Circuit open for " + call.providerType(), java.time.Duration.ofSeconds(30));
            count("circuit_open", call, outcome);
            log.warn("Circuit open for {}; {} not attempted", call.providerType(), call.operation());
        } catch (PaymentCircuitBreakers.PaymentCallFailed failed) {
            outcome = failed.outcome();
        }

        exchange.getIn().setHeader(PaymentRouteBuilder.OUTCOME_HEADER, outcome);
    }

    public void recordOutcome(Exchange exchange) {
        MerchantApiCall call = call(exchange);
        ProviderOutcome outcome = exchange.getIn()
                .getHeader(PaymentRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
        count("completed", call, outcome);
        // The operation label and the status, and nothing else. A payment body is
        // personal data and a provider error note has been known to echo request
        // content back.
        log.info("Payment call {} on {} finished as {}", call.operation(), call.providerType(),
                outcome == null ? "NONE" : outcome.status());
        PaymentRouteBuilder.clearContext();
    }

    /**
     * Anything that escaped classification.
     *
     * <p>Uncertain, never retryable. Something reached this point without the
     * gateway deciding what it was, which means nobody can say whether the
     * provider acted — and on a mutating payment call that is precisely the state
     * in which a retry is a second charge.
     */
    public void deadLetter(Exchange exchange) {
        MerchantApiCall call = call(exchange);
        Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);

        ProviderOutcome outcome = cause instanceof PaymentCircuitBreakers.PaymentCallFailed failed
                ? failed.outcome()
                : ProviderOutcome.uncertain("UNCLASSIFIED",
                        cause == null ? "Unknown route failure" : cause.getClass().getSimpleName());

        exchange.getIn().setHeader(PaymentRouteBuilder.OUTCOME_HEADER, outcome);
        count("dead_lettered", call, outcome);
        log.error("Payment call {} on {} dead-lettered as {}",
                call.operation(), call.providerType(), outcome.errorCode());
        PaymentRouteBuilder.clearContext();
    }

    private void count(String event, MerchantApiCall call, ProviderOutcome outcome) {
        // Bounded tags only. A tenant id or a merchant transaction id here would
        // make the cardinality unbounded and eventually take the registry down.
        meters.counter("qoida.payment.route",
                "event", event,
                "provider", call.providerType(),
                "operation", call.operation(),
                "status", outcome == null ? "NONE" : outcome.status().name())
                .increment();
    }

    static MerchantApiCall call(Exchange exchange) {
        return exchange.getIn().getBody(MerchantApiCall.class);
    }
}
