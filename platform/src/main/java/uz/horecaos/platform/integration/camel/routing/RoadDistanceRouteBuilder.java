package uz.horecaos.platform.integration.camel.routing;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.provider.routing.RoadRouteMeasurer;

/**
 * The road-distance route (ADR 0007, ADR 0147), described by
 * {@code docs/routes/osrm-road-distance.md}.
 *
 * <p>Thin on purpose. What it decides is what happens when something escapes the
 * measurer, and the answer is the only one a checkout can live with: an empty distance,
 * which the resolver turns into a straight-line fee that says {@code RADIUS_FALLBACK}.
 * There is no redelivery: ADR 0147 gives the checkout path one attempt under a 500 ms
 * deadline, and a second attempt would spend the customer's wait on a question the first
 * already answered "no". There is no failure record either, because a distance that was
 * not measured is not work to do later; the quote has already been priced without it.
 *
 * <p>Whether to ask at all, the cache, the circuit breaker and the HTTP call are the
 * measurer's. The route is the seam ADR 0007 requires every provider call to cross, with
 * an owner, a descriptor and a health indicator, and nothing about a delivery's price is
 * decided in this DSL.
 */
@Component
public class RoadDistanceRouteBuilder extends RouteBuilder {

    private static final Logger log = LoggerFactory.getLogger(RoadDistanceRouteBuilder.class);

    /** The only entry point to a routing engine. */
    public static final String ENDPOINT = "direct:routing.road-distance";

    private final RoadRouteMeasurer measurer;
    private final MeterRegistry meters;

    public RoadDistanceRouteBuilder(RoadRouteMeasurer measurer, MeterRegistry meters) {
        this.measurer = measurer;
        this.meters = meters;
    }

    @Override
    public void configure() {
        onException(Exception.class)
                .routeId("routing.road-distance.dead-letter")
                .handled(true)
                .maximumRedeliveries(0)
                .process(this::answerNothing);

        from(ENDPOINT)
                .routeId("routing.road-distance.v1")
                .description("Measures one driving route between two points with the platform's routing engine")
                .process(this::measure);
    }

    private void measure(Exchange exchange) {
        RoadDistanceCommand command = exchange.getMessage().getBody(RoadDistanceCommand.class);
        if (command == null) {
            throw new IllegalArgumentException("The road-distance route takes a RoadDistanceCommand");
        }
        exchange.getMessage()
                .setBody(measurer.measure(command.origin(), command.destination(), command.installationId()));
    }

    /**
     * Anything that reaches here escaped the measurer, which is built not to throw, so it
     * is a defect or a resource failure and is counted as an engine error. The caller sees
     * "no answer" and the fee falls back, as it does for every other way of not knowing.
     * The exception's class is logged and nothing else: its message could quote the body.
     */
    private void answerNothing(Exchange exchange) {
        Exception failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        meters.counter("horecaos.routing.calls", "outcome", "error").increment();
        log.warn(
                "The road-distance route failed: {}",
                failure == null ? "unknown" : failure.getClass().getSimpleName());
        exchange.getMessage().setBody(Optional.empty());
    }
}
