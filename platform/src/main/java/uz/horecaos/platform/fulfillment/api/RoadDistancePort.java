package uz.horecaos.platform.fulfillment.api;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * Road distance and duration from a routing provider (ADR 0037, ADR 0147, bound
 * under ADR 0026).
 *
 * <p>Provider-neutral on purpose. The resolver needs metres, a duration, a provider
 * name and the dataset that produced them, and everything else — polylines, turn
 * instructions, traffic classes — belongs to whoever is being replaced. The port
 * sits in {@code fulfillment.api}, with its callers, so the adapter can live in the
 * integration module that owns the provider machinery without fulfillment ever
 * importing it (the direction {@link ShipmentBookingPort} already points).
 *
 * <p>An empty answer is not an error. It means routing did not answer in time, has
 * no route between the two points, or is not installed, and the resolver falls back
 * to straight-line distance multiplied by the tariff's detour factor, records
 * {@code distance_source = RADIUS_FALLBACK}, and increments a metric. It never fails
 * the quote: a customer unable to check out because a routing provider is slow is a
 * worse outcome than a fee that is a little wrong and says so. An empty answer is
 * never a fabricated number.
 *
 * <p>The caller names no tenant. {@code installationId} is the tariff version's own
 * ADR 0026 reference, which belongs to exactly one tenant (the tariff row's foreign
 * key is composite), so an adapter that resolves it and keys its cache on it cannot
 * serve one tenant another's answer.
 */
public interface RoadDistancePort {

    /**
     * The driving route between two points.
     *
     * @param installationId the tariff version's routing installation, or null when
     *                       none is bound (a draft the activation gate would refuse)
     */
    Optional<RoadRoute> route(GeoPoint origin, GeoPoint destination, @Nullable UUID installationId);
}
