package uz.qoida.platform.fulfillment.application.port;

import java.util.Optional;
import java.util.UUID;

import uz.qoida.platform.tenancy.api.GeoPoint;

/**
 * Road distance from a routing provider (ADR 0037, bound under ADR 0026).
 *
 * <p>Provider-neutral on purpose. ADR 0037 leaves the provider as an open input,
 * and a port shaped around one vendor's response is a decision made by accident:
 * the resolver needs metres and a provider name, and everything else — polylines,
 * turn instructions, traffic classes — belongs to whoever is being replaced.
 *
 * <p>An empty answer is not an error. It means routing did not answer in time or
 * is not installed, and the resolver falls back to straight-line distance
 * multiplied by the tariff's detour factor, records
 * {@code distance_source = RADIUS_FALLBACK}, and increments a metric. It never
 * fails the quote: a customer unable to check out because a routing provider is
 * slow is a worse outcome than a fee that is a little wrong and says so.
 */
public interface RoadDistancePort {

    Optional<RoadDistance> distance(GeoPoint origin, GeoPoint destination, UUID installationId);

    /** @param provider the adapter's own name, stored so a bad calibration can be traced to it */
    record RoadDistance(int meters, String provider) { }
}
