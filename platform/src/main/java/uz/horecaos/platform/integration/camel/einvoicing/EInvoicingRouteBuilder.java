package uz.horecaos.platform.integration.camel.einvoicing;

import org.apache.camel.builder.RouteBuilder;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * The e-invoicing operator route (ADR 0007, ADR 0096), described by {@code
 * docs/routes/einvoicing-operator-api.md}.
 *
 * <p>The shortest kind of provider route, and what is missing is the statement. <strong>There
 * is no redelivery anywhere on it</strong>: Camel's redelivery is safe only for an operation
 * proven safe under one idempotency key (ADR 0007 rule 7), and neither Didox nor Faktura.uz
 * documents a key on creating a document, so a bounded redelivery of a create would be a
 * bounded number of extra invoices to a tenant. A caller that wants to try again does so
 * itself, having decided from the classification that trying again is safe -- and for the one
 * outcome where it is not, an uncertain create, it asks the operator instead.
 */
@Component
public class EInvoicingRouteBuilder extends RouteBuilder {

    /** The only entry point to an e-invoicing operator's API. */
    public static final String EINVOICING_API_ENDPOINT = "direct:einvoicing.operator-api";

    static final String OUTCOME_HEADER = "HorecaOSProviderOutcome";

    private final EInvoicingProcessor processor;

    public EInvoicingRouteBuilder(EInvoicingProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void configure() {
        onException(Exception.class)
                .routeId("einvoicing.operator-api.dead-letter")
                .handled(true)
                // Anything reaching here escaped classification, so nobody can say whether the
                // operator acted. It becomes an uncertain outcome for the adapter to resolve by
                // asking, never a redelivery.
                .maximumRedeliveries(0)
                .process(processor::deadLetter);

        from(EINVOICING_API_ENDPOINT)
                .routeId("einvoicing.operator-api.v1")
                .description("Calls one e-invoicing operator API endpoint for one adapter")
                .process(processor::restoreContext)
                // Circuit breaking is inside the processor, per installation: Camel's
                // circuitBreaker() is one instance per route, so a Didox outage would have
                // stopped Faktura.uz too.
                .process(processor::invoke)
                .process(processor::recordOutcome);
    }

    /** Kept so the MDC keys used by the processor and the route cannot drift apart. */
    static void clearContext() {
        MDC.remove("correlationId");
        MDC.remove("providerType");
    }
}
