package uz.horecaos.platform.integration.provider.routing;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.api.RoadRoute;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * What the road-distance route calls to measure one route (ADR 0147).
 *
 * <p>The seam between the route, which owns the bounded call and the dead-letter policy
 * (ADR 0007), and an engine, which owns the wire. The OSRM adapter is the first
 * implementation; the hosted routing API ADR 0147 names as its second would be another,
 * chosen by the installation's provider type, and neither the route nor the port in front
 * of it would change.
 *
 * <p>The contract is the port's: empty means "did not answer", never a fabricated number,
 * and nothing here may fail the quote that asked.
 */
public interface RoadRouteMeasurer {

    Optional<RoadRoute> measure(GeoPoint origin, GeoPoint destination, @Nullable UUID installationId);
}
