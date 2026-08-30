package uz.horecaos.platform.integration.camel.payment;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.integration.api.payment.MerchantApiCall;
import uz.horecaos.platform.integration.api.payment.MerchantApiTransport;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * {@link MerchantApiTransport} over the ADR 0007 payment route.
 *
 * <p>This class is why the payments module compiles without Camel on its
 * classpath, which {@code ModularArchitectureTests} enforces: an adapter names a
 * call, and the translation into an exchange, a route, and a classified outcome
 * happens here.
 *
 * <p>Nothing is thrown for a provider failure. A route that dead-letters produces
 * an uncertain outcome, and the adapter decides what to do about it — the
 * distinction between "did not happen" and "may have happened" is the whole
 * decision, and an exception would erase it.
 */
@Component
public class CamelMerchantApiTransport implements MerchantApiTransport {

    private final ProducerTemplate producer;

    public CamelMerchantApiTransport(ProducerTemplate producer) {
        this.producer = producer;
    }

    @Override
    public ProviderOutcome exchange(MerchantApiCall call) {
        Exchange result = producer.request(PaymentRouteBuilder.MERCHANT_API_ENDPOINT,
                exchange -> exchange.getIn().setBody(call));

        ProviderOutcome outcome = result.getIn()
                .getHeader(PaymentRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
        if (outcome != null) {
            return outcome;
        }
        // The route produced no outcome at all, which means it failed before the
        // dead-letter handler could classify. Uncertain rather than retryable:
        // there is no evidence the request did not reach the provider.
        return ProviderOutcome.uncertain("ROUTE_PRODUCED_NO_OUTCOME",
                "The payment route returned without classifying the call");
    }
}
