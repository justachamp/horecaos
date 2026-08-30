package uz.horecaos.platform.fulfillment.infrastructure.routing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.horecaos.platform.fulfillment.application.port.RoadDistancePort;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * What answers a road-distance question while no routing provider is installed
 * (ADR 0037).
 *
 * <p>ADR 0037 rolls {@code RADIUS} out everywhere first and {@code ROAD} only once
 * a routing binding is verified, and the provider itself is still an open input on
 * the decision. So this answers empty, always — which is exactly the timeout path,
 * meaning a {@code ROAD} tariff prices at straight-line distance multiplied by its
 * detour factor and records {@code RADIUS_FALLBACK}, visibly, on every row.
 *
 * <p>Deliberately not a stub that invents a plausible number. A fabricated road
 * distance is indistinguishable from a real one in the evidence, and the first
 * person to notice would be a tenant reconciling a courier invoice.
 *
 * <p>Registered only when nothing else supplies the port, so the first real
 * adapter replaces it by existing rather than by someone remembering to delete
 * this.
 */
@Configuration(proxyBeanMethods = false)
public class DeliveryRoutingConfiguration {

    @Bean
    @ConditionalOnMissingBean(RoadDistancePort.class)
    RoadDistancePort unboundRoadDistancePort() {
        return new RoadDistancePort() {
            @Override
            public Optional<RoadDistance> distance(GeoPoint origin, GeoPoint destination, UUID installationId) {
                return Optional.empty();
            }
        };
    }
}
