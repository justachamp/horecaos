package uz.horecaos.platform.integration.camel.cardbilling;

import org.apache.camel.builder.RouteBuilder;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * The card-billing provider route (ADR 0007, ADR 0095), described by {@code
 * docs/routes/card-billing-provider-api.md}.
 *
 * <p>The shortest kind of provider route, and what is missing is the statement. <strong>There is no
 * redelivery anywhere on it</strong>: Camel's redelivery is safe only for an operation proven safe
 * under one idempotency key (ADR 0007 rule 7), and Click documents none on {@code card_token/payment},
 * so a bounded redelivery of a charge would be a bounded number of extra charges. A caller that wants to
 * try again decides from the classification that trying again is safe, and for the one outcome where it
 * is not, an uncertain charge, it asks the provider what it holds instead.
 */
@Component
public class CardBillingRouteBuilder extends RouteBuilder {

    /** The only entry point to a card provider's API for HorecaOS's own billing. */
    public static final String CARD_BILLING_API_ENDPOINT = "direct:cardbilling.provider-api";

    static final String OUTCOME_HEADER = "HorecaOSProviderOutcome";

    private final CardBillingProcessor processor;

    public CardBillingRouteBuilder(CardBillingProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void configure() {
        onException(Exception.class)
                .routeId("cardbilling.provider-api.dead-letter")
                .handled(true)
                // Anything reaching here escaped classification, so nobody can say whether the provider
                // acted. It becomes an uncertain outcome for the adapter to resolve by asking, never a
                // redelivery.
                .maximumRedeliveries(0)
                .process(processor::deadLetter);

        from(CARD_BILLING_API_ENDPOINT)
                .routeId("cardbilling.provider-api.v1")
                .description("Calls one card provider API endpoint for HorecaOS's own billing account")
                .process(processor::restoreContext)
                // Circuit breaking is inside the processor, per installation: Camel's circuitBreaker() is one
                // instance per route, so one provider's outage would stop every other.
                .process(processor::invoke)
                .process(processor::recordOutcome);
    }

    /** Kept so the MDC keys used by the processor and the route cannot drift apart. */
    static void clearContext() {
        MDC.remove("correlationId");
        MDC.remove("providerType");
    }
}
