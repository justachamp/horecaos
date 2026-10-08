package uz.horecaos.platform.integration.camel.routing;

import java.util.Optional;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.fulfillment.api.RoadDistancePort;
import uz.horecaos.platform.fulfillment.api.RoadRoute;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * {@link RoadDistancePort} over the ADR 0007 road-distance route (ADR 0147).
 *
 * <p>Fulfillment declares the port and compiles with no Camel on its classpath, which
 * {@code ModularArchitectureTests} enforces; this is where the port meets the route, the
 * same way {@code CamelShipmentBookingPort} does for booking.
 *
 * <p>It never throws. A route that was never started (a build failure at boot, which
 * {@code CamelRouteHealthIndicator} reports) is "no consumers available", and
 * {@code ProducerTemplate.request} attaches that to the exchange rather than throwing it,
 * so it is read explicitly: the alternative reading is "the route ran and found nothing",
 * which is a different answer. Either way the quote is priced and the fee says
 * {@code RADIUS_FALLBACK}.
 */
@Component
public class CamelRoadDistancePort implements RoadDistancePort {

    private static final Logger log = LoggerFactory.getLogger(CamelRoadDistancePort.class);

    private final ProducerTemplate producer;

    public CamelRoadDistancePort(ProducerTemplate producer) {
        this.producer = producer;
    }

    @Override
    public Optional<RoadRoute> route(GeoPoint origin, GeoPoint destination, @Nullable UUID installationId) {
        try {
            Exchange result = producer.request(
                    RoadDistanceRouteBuilder.ENDPOINT,
                    exchange -> exchange.getIn().setBody(new RoadDistanceCommand(origin, destination, installationId)));
            if (result.getException() != null) {
                log.warn(
                        "The road-distance route could not be entered: {}",
                        result.getException().getClass().getSimpleName());
                return Optional.empty();
            }
            Object answer = result.getMessage().getBody();
            if (answer instanceof Optional<?> measured && measured.orElse(null) instanceof RoadRoute route) {
                return Optional.of(route);
            }
            return Optional.empty();
        } catch (RuntimeException failure) {
            log.warn("The road-distance route failed: {}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
