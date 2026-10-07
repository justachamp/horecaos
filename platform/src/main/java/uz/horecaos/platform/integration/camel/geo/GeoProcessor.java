package uz.horecaos.platform.integration.camel.geo;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.apache.camel.Exchange;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * The geocoding route's steps, as plain Java (ADR 0007) so they are unit-testable without a
 * Camel context.
 *
 * <p>Nothing in this class logs, counts or puts on the MDC anything that identifies a person
 * or a place: the exchange body is an address or a coordinate (ADR 0029). The vocabulary is
 * the provider's name, the operation kind and a bounded outcome, which is also the whole of
 * the metric's labels (ADR 0145: "provider, operation and outcome only").
 */
@Component
public class GeoProcessor {

    private static final Logger log = LoggerFactory.getLogger(GeoProcessor.class);

    private final GeoGateway gateway;
    private final MeterRegistry meters;

    public GeoProcessor(GeoGateway gateway, MeterRegistry meters) {
        this.gateway = gateway;
        this.meters = meters;
    }

    public void lookup(Exchange exchange) {
        GeoOperation operation =
                Objects.requireNonNull(operation(exchange), "No geocoding operation on the exchange body");
        ProviderOutcome outcome = gateway.call(operation);
        count(gateway.providerLabel(), operation.kind(), outcome);
        exchange.getIn().setHeader(GeoRouteBuilder.OUTCOME_HEADER, outcome);
    }

    /** Anything that escaped classification. The class name only; the cause can echo the request. */
    public void deadLetter(Exchange exchange) {
        Throwable failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        String detail = failure == null ? "unknown" : failure.getClass().getSimpleName();
        GeoOperation operation = operation(exchange);
        log.error("The geocoding route failed: {}", detail);
        ProviderOutcome outcome = ProviderOutcome.retryable("GEO_ROUTE_FAILURE", detail, null);
        count(gateway.providerLabel(), operation == null ? GeoOperation.Kind.GEOCODE : operation.kind(), outcome);
        exchange.getIn().setHeader(GeoRouteBuilder.OUTCOME_HEADER, outcome);
    }

    private void count(String provider, GeoOperation.Kind kind, ProviderOutcome outcome) {
        meters.counter(
                        "horecaos.geo.lookups",
                        "provider",
                        provider,
                        "operation",
                        kind.label(),
                        "outcome",
                        label(outcome))
                .increment();
    }

    /** The bounded outcome label. Never the error code: that is unbounded the day someone adds one. */
    static String label(ProviderOutcome outcome) {
        return switch (outcome.status()) {
            case SUCCESS -> "answered";
            case REJECTED ->
                GeoGateway.NOT_CONFIGURED.equals(outcome.errorCode())
                                || GeoGateway.CREDENTIAL_MISSING.equals(outcome.errorCode())
                        ? "not_configured"
                        : "refused";
            case RETRYABLE, UNCERTAIN -> "unavailable";
        };
    }

    private static @Nullable GeoOperation operation(Exchange exchange) {
        return exchange.getIn().getBody(GeoOperation.class);
    }
}
