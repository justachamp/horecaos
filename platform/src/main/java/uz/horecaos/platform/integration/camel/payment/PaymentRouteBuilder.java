package uz.horecaos.platform.integration.camel.payment;

import org.apache.camel.builder.RouteBuilder;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * The payment merchant-API route (ADR 0007), described by
 * {@code docs/routes/payment-merchant-api.md}.
 *
 * <p>Shorter than {@code delivery.operation.v1} by one branch, and the missing
 * branch is the point. Delivery retries a retryable outcome and reconciles an
 * uncertain one inside the route. This route does neither: it classifies, records,
 * and hands the outcome straight back.
 *
 * <p><strong>There is no redelivery anywhere on this route.</strong> Camel's
 * redelivery is safe only for an operation proven safe under one idempotency key,
 * which is ADR 0007's own rule 7, and no payment provider in this build offers an
 * idempotency key on any call. A bounded redelivery on {@code invoice/create} or
 * {@code payment/reversal} would therefore be a bounded number of extra charges.
 * A caller that wants to try again re-sends the call itself, having first decided
 * from the classification that trying again is safe.
 */
@Component
public class PaymentRouteBuilder extends RouteBuilder {

    /** The only entry point to a payment provider's merchant API. */
    public static final String MERCHANT_API_ENDPOINT = "direct:payment.merchant-api";

    static final String OUTCOME_HEADER = "HorecaOSProviderOutcome";

    private final PaymentProcessor processor;

    public PaymentRouteBuilder(PaymentProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void configure() {
        onException(Exception.class)
                .routeId("payment.merchant-api.dead-letter")
                .handled(true)
                // Anything reaching here escaped classification, so nobody can say
                // whether the provider acted. It becomes an uncertain outcome for
                // the adapter to resolve by query, never a redelivery.
                .maximumRedeliveries(0)
                .process(processor::deadLetter);

        from(MERCHANT_API_ENDPOINT)
                .routeId("payment.merchant-api.v1")
                .description("Calls one payment provider merchant-API endpoint for one adapter")
                .process(processor::restoreContext)
                // Circuit breaking is inside the processor rather than in this DSL,
                // for the same reason as on the delivery route: Camel's
                // circuitBreaker() is one instance per route, so a Click outage
                // would have stopped Payme too.
                .process(processor::invoke)
                .process(processor::recordOutcome);
    }

    /** Kept so the MDC keys used by the processor and the route cannot drift apart. */
    static void clearContext() {
        MDC.remove("tenantId");
        MDC.remove("correlationId");
        MDC.remove("providerType");
    }
}
