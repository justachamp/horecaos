package uz.horecaos.platform.integration.camel.pos;

import org.apache.camel.builder.RouteBuilder;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * The POS route (ADR 0007), described by {@code docs/routes/pos-api.md}.
 *
 * <p>The shortest of the three provider routes in this build, and what is missing
 * is the whole statement.
 *
 * <p><strong>There is no redelivery anywhere on this route.</strong> ADR 0007's
 * own rule is that Camel redelivery is safe only for an operation proven safe
 * under one idempotency key. The POS implemented here has no idempotency
 * mechanism of any kind — its documentation does not contain the word — so a
 * bounded redelivery on an order export would be a bounded number of extra
 * dinners. A caller that wants to try again re-sends the call itself, having
 * first established from the classification, and usually from a person, that
 * trying again is safe.
 *
 * <p><strong>There is no reconcile branch either</strong>, unlike the delivery
 * route. Reconciling a POS export means searching the day's orders and comparing
 * line composition, which is business judgement about whether two baskets are the
 * same basket. Putting that in route DSL would be exactly the coupling ADR 0007
 * exists to prevent, so the uncertain outcome goes back to the {@code pos} module
 * and the decision is made there, where it can be shown to a human.
 */
@Component
public class PosRouteBuilder extends RouteBuilder {

    /** The only entry point to a point-of-sale API. */
    public static final String POS_API_ENDPOINT = "direct:pos.api";

    static final String OUTCOME_HEADER = "HorecaOSProviderOutcome";

    private final PosProcessor processor;

    public PosRouteBuilder(PosProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void configure() {
        onException(Exception.class)
                .routeId("pos.api.dead-letter")
                .handled(true)
                // Zero, explicitly. Anything reaching here escaped classification,
                // so nobody can say whether the till acted.
                .maximumRedeliveries(0)
                .process(processor::deadLetter);

        from(POS_API_ENDPOINT)
                .routeId("pos.api.v1")
                .description("Calls one point-of-sale endpoint for one adapter")
                .process(processor::restoreContext)
                // Circuit breaking is inside the processor rather than in this
                // DSL, for the reason the delivery and payment routes give:
                // Camel's circuitBreaker() is one instance per route, so one
                // vendor's outage would stop every other vendor's restaurants.
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
