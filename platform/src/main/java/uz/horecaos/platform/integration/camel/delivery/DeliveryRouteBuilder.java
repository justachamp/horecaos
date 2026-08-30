package uz.horecaos.platform.integration.camel.delivery;

import java.time.Duration;

import org.apache.camel.builder.RouteBuilder;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * The delivery provider route (ADR 0007), described by
 * {@code docs/routes/delivery-operation.md}.
 *
 * <p>Camel earns its place here by holding four policies that would otherwise be
 * copied into every adapter: bounded redelivery, circuit breaking, dead-lettering
 * into the ADR 0006 failure model, and the rule that an uncertain outcome
 * reconciles rather than repeats.
 *
 * <p>The route is deliberately thin. It decides <em>whether to call again</em>;
 * it never decides whether a delivery is acceptable. That second question belongs
 * to fulfilment, and keeping it out of route DSL is the whole point of ADR 0007.
 */
@Component
public class DeliveryRouteBuilder extends RouteBuilder {

    /** Fulfilment sends here. The only entry point to a courier partner. */
    public static final String OPERATION_ENDPOINT = "direct:delivery.operation";

    /** Where an uncertain outcome goes. Queries; never repeats the side effect. */
    public static final String RECONCILE_ENDPOINT = "direct:delivery.reconcile";

    static final String OUTCOME_HEADER = "HorecaOSProviderOutcome";

    private final DeliveryProcessor processor;

    public DeliveryRouteBuilder(DeliveryProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void configure() {
        onException(Exception.class)
                .routeId("delivery.operation.dead-letter")
                .handled(true)
                // Anything reaching here escaped classification, which means we
                // cannot say whether the provider acted. It goes to a human via
                // the failure model rather than being retried into a duplicate.
                .maximumRedeliveries(0)
                .process(processor::deadLetter);

        from(OPERATION_ENDPOINT)
                .routeId("delivery.operation.v1")
                .description("Calls a courier partner for one provider-neutral delivery command")
                .process(processor::restoreContext)
                .process(processor::validate)
                // Circuit breaking is inside the processor rather than in this
                // DSL. Camel's circuitBreaker() is one instance per route, so a
                // Noor outage would have opened the circuit for Yandex too and
                // turned one partner's bad afternoon into a total delivery
                // outage. DeliveryCircuitBreakers keys a breaker per partner.
                .process(processor::invoke)
                .choice()
                    .when(exchange -> outcome(exchange) != null
                            && outcome(exchange).requiresReconciliation())
                        .to(RECONCILE_ENDPOINT)
                    .when(exchange -> outcome(exchange) != null
                            && outcome(exchange).mayRetryDirectly())
                        .process(processor::scheduleRetry)
                .end()
                .process(processor::recordOutcome);

        from(RECONCILE_ENDPOINT)
                .routeId("delivery.reconcile.v1")
                .description("Discovers the true state after an uncertain provider outcome")
                // Redelivery is safe here and nowhere else in this route: a query
                // has no side effect, so repeating it cannot book a second courier.
                .onException(Exception.class)
                    .maximumRedeliveries(3)
                    .redeliveryDelay(Duration.ofSeconds(5).toMillis())
                    .backOffMultiplier(2)
                    .handled(false)
                    .end()
                .process(processor::reconcile);
    }

    private static ProviderOutcome outcome(org.apache.camel.Exchange exchange) {
        return exchange.getIn().getHeader(OUTCOME_HEADER, ProviderOutcome.class);
    }

    /** Kept so the MDC key used by the processor and the route cannot drift apart. */
    static void clearContext() {
        MDC.remove("tenantId");
        MDC.remove("correlationId");
        MDC.remove("providerType");
    }
}
