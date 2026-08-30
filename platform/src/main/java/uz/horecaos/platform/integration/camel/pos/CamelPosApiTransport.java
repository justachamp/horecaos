package uz.horecaos.platform.integration.camel.pos;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.integration.api.pos.PosApiCall;
import uz.horecaos.platform.integration.api.pos.PosApiTransport;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * {@link PosApiTransport} over the ADR 0007 POS route.
 *
 * <p>This class is why the {@code pos} module compiles without Camel on its
 * classpath, which {@code PosModuleBoundaryTests} enforces: an adapter names a
 * call, and the translation into an exchange, a route, and a classified outcome
 * happens here.
 *
 * <p>Nothing is thrown for a provider failure. The distinction between "did not
 * happen" and "may have happened" is the entire decision on this integration, and
 * an exception would erase it.
 */
@Component
public class CamelPosApiTransport implements PosApiTransport {

    private final ProducerTemplate producer;

    public CamelPosApiTransport(ProducerTemplate producer) {
        this.producer = producer;
    }

    @Override
    public ProviderOutcome exchange(PosApiCall call) {
        Exchange result = producer.request(PosRouteBuilder.POS_API_ENDPOINT,
                exchange -> exchange.getIn().setBody(call));

        ProviderOutcome outcome = result.getIn()
                .getHeader(PosRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
        if (outcome != null) {
            return outcome;
        }
        // The route produced no outcome at all, which means it failed before the
        // dead-letter handler could classify. On a create that is uncertain:
        // there is no evidence the request did not reach the till, and inventing
        // some would be the one mistake this integration cannot afford.
        return call.effect() == PosApiCall.Effect.UNKEYED_CREATE
                ? ProviderOutcome.uncertain("ROUTE_PRODUCED_NO_OUTCOME",
                        "The POS route returned without classifying the call")
                : ProviderOutcome.retryable("ROUTE_PRODUCED_NO_OUTCOME",
                        "The POS route returned without classifying the call", null);
    }
}
