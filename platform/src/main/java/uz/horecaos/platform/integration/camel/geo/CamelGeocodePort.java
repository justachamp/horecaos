package uz.horecaos.platform.integration.camel.geo;

import java.util.List;
import java.util.Optional;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.geo.GeoRegion;
import uz.horecaos.platform.tenancy.api.geo.GeoSuggestion;
import uz.horecaos.platform.tenancy.api.geo.GeoUnavailableReason;
import uz.horecaos.platform.tenancy.api.geo.GeocodeOutcome;
import uz.horecaos.platform.tenancy.api.geo.GeocodePort;
import uz.horecaos.platform.tenancy.api.geo.GeocodeResult;
import uz.horecaos.platform.tenancy.api.geo.MapClientConfig;
import uz.horecaos.platform.tenancy.api.geo.MapClientConfigPort;

/**
 * {@link GeocodePort} over the ADR 0007 route (ADR 0145).
 *
 * <p>The reason fulfillment, customers and ordering compile without Camel, Jackson or an HTTP
 * client: they name a text, a region and a point, and the translation into an exchange, a
 * route, a provider and a wire format happens here and below. Registered unconditionally,
 * like {@code CamelVerificationCodeTransport}: whether a provider is <em>configured</em> is an
 * environment fact answered at call time, and answered loudly as
 * {@link GeoUnavailableReason#NOT_CONFIGURED}, rather than by the application declining to
 * start for everybody.
 *
 * <p>Nothing here throws for a provider failure, per the port, and nothing logs the request:
 * the only things said about a failure are the reason and the exception's class name.
 */
@Component
public class CamelGeocodePort implements GeocodePort, MapClientConfigPort {

    private static final Logger log = LoggerFactory.getLogger(CamelGeocodePort.class);

    /** The route never started, so no provider was contacted. See the route README. */
    static final String ROUTE_UNAVAILABLE = "GEO_ROUTE_UNAVAILABLE";

    private final ProducerTemplate producer;
    private final GeoGateway gateway;

    public CamelGeocodePort(ProducerTemplate producer, GeoGateway gateway) {
        this.producer = producer;
        this.gateway = gateway;
    }

    @Override
    @SuppressWarnings("unchecked")
    public GeocodeOutcome<List<GeoSuggestion>> suggest(
            String text, GeoRegion region, @Nullable GeoPoint near, String locale) {
        return translate(
                dispatch(GeoOperation.suggest(text, region, near, locale)), payload -> (List<GeoSuggestion>) payload);
    }

    @Override
    @SuppressWarnings("unchecked")
    public GeocodeOutcome<List<GeocodeResult>> geocode(String text, GeoRegion region, String locale) {
        return translate(
                dispatch(GeoOperation.geocode(text, region, locale)), payload -> (List<GeocodeResult>) payload);
    }

    @Override
    @SuppressWarnings("unchecked")
    public GeocodeOutcome<Optional<GeocodeResult>> reverseGeocode(GeoPoint point, GeoRegion region, String locale) {
        return translate(
                dispatch(GeoOperation.reverse(point, region, locale)),
                payload -> ((List<GeocodeResult>) payload).stream().findFirst());
    }

    @Override
    public MapClientConfig clientConfig() {
        return gateway.clientConfig();
    }

    private ProviderOutcome dispatch(GeoOperation operation) {
        try {
            // The whole exchange rather than a body: the outcome travels as a header because
            // the dead-letter path replaces the body.
            Exchange result = producer.request(
                    GeoRouteBuilder.LOOKUP_ENDPOINT,
                    exchange -> exchange.getIn().setBody(operation));
            ProviderOutcome outcome =
                    result.getMessage().getHeader(GeoRouteBuilder.OUTCOME_HEADER, ProviderOutcome.class);
            if (outcome == null && result.getException() != null) {
                return unreachable(result.getException());
            }
            return outcome == null
                    ? ProviderOutcome.retryable(
                            "GEO_ROUTE_PRODUCED_NO_OUTCOME", "The route returned without classifying", null)
                    : outcome;
        } catch (RuntimeException failure) {
            return unreachable(failure);
        }
    }

    /**
     * The route was never entered: almost always "no consumers available" because it failed to
     * build at startup. The exception's class name only, because Camel wraps the exchange into
     * the message of the exception it throws and the exchange body is an address.
     */
    private static ProviderOutcome unreachable(Throwable failure) {
        log.error(
                "The geocoding route could not be reached: {}",
                failure.getClass().getSimpleName());
        return ProviderOutcome.retryable(ROUTE_UNAVAILABLE, failure.getClass().getSimpleName(), null);
    }

    /**
     * ADR 0007's four outcomes onto the port's two. A read has nothing to reconcile, so
     * {@code UNCERTAIN} and {@code RETRYABLE} are the same answer to a person: not now.
     */
    static <T> GeocodeOutcome<T> translate(ProviderOutcome outcome, java.util.function.Function<Object, T> payload) {
        return switch (outcome.status()) {
            case SUCCESS -> {
                Object carried = outcome.normalized().get(GeocoderAdapter.PAYLOAD_KEY);
                yield carried == null
                        ? GeocodeOutcome.unavailable(GeoUnavailableReason.PROVIDER_UNAVAILABLE)
                        : GeocodeOutcome.answered(payload.apply(carried));
            }
            case REJECTED ->
                GeoGateway.NOT_CONFIGURED.equals(outcome.errorCode())
                                || GeoGateway.CREDENTIAL_MISSING.equals(outcome.errorCode())
                        ? GeocodeOutcome.unavailable(GeoUnavailableReason.NOT_CONFIGURED)
                        : GeocodeOutcome.unavailable(GeoUnavailableReason.PROVIDER_REFUSED);
            case RETRYABLE, UNCERTAIN -> GeocodeOutcome.unavailable(GeoUnavailableReason.PROVIDER_UNAVAILABLE);
        };
    }
}
