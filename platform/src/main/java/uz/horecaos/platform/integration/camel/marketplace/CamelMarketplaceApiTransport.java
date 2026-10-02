package uz.horecaos.platform.integration.camel.marketplace;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceApiCall;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceApiTransport;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * {@link MarketplaceApiTransport} over the ADR 0007 marketplace route: an adapter names a
 * call, and the translation into an exchange, a route and a classified outcome happens here.
 *
 * <p>Nothing is thrown for a provider failure. The distinction between "did not happen" and
 * "may have happened" is the entire decision on this integration, and an exception would
 * erase it.
 */
@Component
public class CamelMarketplaceApiTransport implements MarketplaceApiTransport {

    private final ProducerTemplate producer;

    public CamelMarketplaceApiTransport(ProducerTemplate producer) {
        this.producer = producer;
    }

    @Override
    public ProviderOutcome exchange(MarketplaceApiCall call) {
        Exchange result = producer.request(
                MarketplaceRouteBuilder.MARKETPLACE_API_ENDPOINT,
                exchange -> exchange.getIn().setBody(call));

        ProviderOutcome outcome =
                result.getIn().getHeader(MarketplaceRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
        if (outcome != null) {
            return outcome;
        }
        // The route produced no outcome at all, so it failed before the dead-letter handler
        // could classify. "ROUTE_PRODUCED_NO_OUTCOME" is outside the set of failures that
        // provably wrote nothing, so the reconciler concludes UNKNOWN and sends the current
        // truth again -- safe, because an availability push sets a value.
        return ProviderOutcome.retryable(
                "ROUTE_PRODUCED_NO_OUTCOME", "The marketplace route returned without classifying the call", null);
    }
}
