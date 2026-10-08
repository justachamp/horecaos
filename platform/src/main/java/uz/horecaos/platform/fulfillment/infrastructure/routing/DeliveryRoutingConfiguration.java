package uz.horecaos.platform.fulfillment.infrastructure.routing;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.horecaos.platform.fulfillment.api.RoadDistancePort;
import uz.horecaos.platform.fulfillment.api.RoadRoute;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * What answers a road-distance question while no routing adapter is wired
 * (ADR 0037, ADR 0147).
 *
 * <p>ADR 0147 chose a self-hosted OSRM as the first adapter, and it lives in the
 * integration module ({@code OsrmRoadDistanceAdapter}). This stays as the answer
 * for a context that does not include it, so the resolver always has a port: it
 * answers empty, always — which is exactly the timeout path, meaning a {@code ROAD}
 * tariff prices at straight-line distance multiplied by its detour factor and
 * records {@code RADIUS_FALLBACK}, visibly, on every row.
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
    public RoadDistancePort unboundRoadDistancePort() {
        return new RoadDistancePort() {
            @Override
            public Optional<RoadRoute> route(GeoPoint origin, GeoPoint destination, @Nullable UUID installationId) {
                return Optional.empty();
            }
        };
    }
}
