package uz.horecaos.platform.integration.camel.geo;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * The geocoding route (ADR 0007, ADR 0145), described by {@code docs/routes/geo-lookup.md}.
 *
 * <p>One route for the three operations, because every policy that matters is the same for all
 * of them: they are reads, so nothing here can create a duplicate; none is retried on a timer,
 * because a person is watching a type-ahead list or a pin and a retry after two seconds of
 * silence is an answer for nobody; and each degrades the same way, to an
 * {@code Unavailable} the screen turns into plain text.
 *
 * <p><strong>No redelivery, anywhere on this route.</strong> Not because a retry would be
 * unsafe, as on the SMS send, but because it would be useless: the circuit breaker in the
 * gateway is what keeps a failing provider from costing every keystroke a full timeout, and
 * the person asking again is the retry.
 *
 * <p>{@code block=false} is load-bearing for the reason {@code SmsRouteBuilder} gives: a
 * {@code direct:} producer whose consumer never started blocks for thirty seconds by default,
 * and the thread it blocks is the one serving the operator.
 */
@Component
public class GeoRouteBuilder extends RouteBuilder {

    /** The only entry to a map provider. */
    public static final String LOOKUP_ENDPOINT = "direct:geo.lookup?block=false";

    static final String OUTCOME_HEADER = "HorecaOSProviderOutcome";

    private final GeoProcessor processor;

    public GeoRouteBuilder(GeoProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void configure() {
        onException(Exception.class)
                .routeId("geo.lookup.dead-letter")
                .handled(true)
                // Zero, explicitly. Anything reaching here escaped classification; a read has
                // nothing to reconcile, so it becomes an unavailable answer and never a retry.
                .maximumRedeliveries(0)
                .process(processor::deadLetter);

        from(LOOKUP_ENDPOINT)
                .routeId("geo.lookup.v1")
                .description("Looks up an address, a suggestion list or a point through the configured map provider")
                .process(processor::lookup);
    }
}
