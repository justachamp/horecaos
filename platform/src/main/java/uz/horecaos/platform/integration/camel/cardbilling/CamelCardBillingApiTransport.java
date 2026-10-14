package uz.horecaos.platform.integration.camel.cardbilling;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * {@link CardBillingApiTransport} over the ADR 0007 card-billing route.
 *
 * <p>Nothing is thrown for a provider failure. A route that dead-letters produces an uncertain
 * outcome, and the adapter decides what to do about it: "did not happen" and "may have happened"
 * are the whole decision, and an exception would erase the difference.
 */
@Component
public class CamelCardBillingApiTransport implements CardBillingApiTransport {

    private final ProducerTemplate producer;

    public CamelCardBillingApiTransport(ProducerTemplate producer) {
        this.producer = producer;
    }

    @Override
    public ProviderOutcome exchange(CardBillingApiCall call) {
        Exchange result = producer.request(
                CardBillingRouteBuilder.CARD_BILLING_API_ENDPOINT,
                exchange -> exchange.getIn().setBody(call));

        ProviderOutcome outcome =
                result.getIn().getHeader(CardBillingRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
        if (outcome != null) {
            return outcome;
        }
        // The route produced no outcome at all, so it failed before the dead-letter handler could
        // classify. Uncertain rather than retryable: nothing says the request did not reach the provider.
        return ProviderOutcome.uncertain(
                "ROUTE_PRODUCED_NO_OUTCOME", "The card-billing route returned without classifying the call");
    }
}
