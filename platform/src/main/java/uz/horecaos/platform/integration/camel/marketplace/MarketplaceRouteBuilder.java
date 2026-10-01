package uz.horecaos.platform.integration.camel.marketplace;

import org.apache.camel.builder.RouteBuilder;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * The marketplace availability route (ADR 0007, ADR 0141), described by {@code
 * docs/routes/marketplace-availability.md}.
 *
 * <p>The shortest kind of provider route, and what is missing is the statement. <strong>There
 * is no redelivery anywhere on it</strong>, for a reason that is the opposite of the POS
 * route's: an availability push <em>could</em> safely be repeated, but repeating the value the
 * route happened to carry would be the bug. By the time a retry fires the dish may have been
 * stopped again, and the route would put the old truth back on sale. The reconciler above it
 * is level-triggered: it sends whatever is true at the next tick.
 */
@Component
public class MarketplaceRouteBuilder extends RouteBuilder {

    /** The only entry point to an aggregator's availability API. */
    public static final String MARKETPLACE_API_ENDPOINT = "direct:marketplace.availability";

    static final String OUTCOME_HEADER = "HorecaOSProviderOutcome";

    private final MarketplaceProcessor processor;

    public MarketplaceRouteBuilder(MarketplaceProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void configure() {
        onException(Exception.class)
                .routeId("marketplace.availability.dead-letter")
                .handled(true)
                .maximumRedeliveries(0)
                .process(processor::deadLetter);

        from(MARKETPLACE_API_ENDPOINT)
                .routeId("marketplace.availability.v1")
                .description("Tells one aggregator that one mapped item is available or not")
                .process(processor::restoreContext)
                // Circuit breaking is inside the processor, per binding: Camel's circuitBreaker()
                // is one instance per route, so one venue's lapsed contract would stop every
                // other venue's stop list.
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
