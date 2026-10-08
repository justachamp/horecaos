package uz.horecaos.platform.integration.camel.einvoicing;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * {@link EInvoicingApiTransport} over the ADR 0007 e-invoicing route.
 *
 * <p>Nothing is thrown for an operator failure. A route that dead-letters produces an
 * uncertain outcome, and the adapter decides what to do about it: "did not happen" and "may
 * have happened" are the whole decision, and an exception would erase the difference.
 */
@Component
public class CamelEInvoicingApiTransport implements EInvoicingApiTransport {

    private final ProducerTemplate producer;

    public CamelEInvoicingApiTransport(ProducerTemplate producer) {
        this.producer = producer;
    }

    @Override
    public ProviderOutcome exchange(EInvoicingApiCall call) {
        Exchange result = producer.request(
                EInvoicingRouteBuilder.EINVOICING_API_ENDPOINT,
                exchange -> exchange.getIn().setBody(call));

        ProviderOutcome outcome =
                result.getIn().getHeader(EInvoicingRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
        if (outcome != null) {
            return outcome;
        }
        // The route produced no outcome at all, which means it failed before the dead-letter
        // handler could classify. Uncertain rather than retryable: there is no evidence the
        // request did not reach the operator.
        return ProviderOutcome.uncertain(
                "ROUTE_PRODUCED_NO_OUTCOME", "The e-invoicing route returned without classifying the call");
    }
}
