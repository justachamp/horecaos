package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.Serviceability;
import uz.horecaos.platform.tenancy.api.ServiceabilityResolver;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStorefrontPickupLocationStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStorefrontPickupLocationStore.PickupLocationCandidate;

/**
 * Finds public, catalogue-capable pickup branches near a customer (ADR 0016).
 *
 * <p>The candidate query establishes that a location can serve a published
 * storefront menu. It deliberately does not duplicate ADR 0036's time,
 * capacity and manual-close rules: each candidate goes through the one
 * serviceability resolver, at one shared instant, before it reaches the
 * storefront. The modest result cap makes this bounded fan-out rather than a
 * location-listing implementation disguised as a browse request.
 */
@Service
public class StorefrontPickupLocationQuery {

    /** More than this is neither useful on a phone nor appropriate for one browse read. */
    public static final int MAXIMUM_LIMIT = 20;

    private final JdbcStorefrontPickupLocationStore locations;
    private final ServiceabilityResolver serviceability;
    private final Clock clock;

    public StorefrontPickupLocationQuery(
            JdbcStorefrontPickupLocationStore locations, ServiceabilityResolver serviceability, Clock clock) {
        this.locations = locations;
        this.serviceability = serviceability;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PickupLocations nearby(GeoPoint point, int limit) {
        if (limit < 1 || limit > MAXIMUM_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAXIMUM_LIMIT);
        }

        Instant now = clock.instant();
        List<PickupLocation> result = locations.nearestTo(point, limit).stream()
                .map(candidate -> viewOf(candidate, now))
                .toList();
        return new PickupLocations(result);
    }

    private PickupLocation viewOf(PickupLocationCandidate candidate, Instant now) {
        Serviceability answer = serviceability.resolve(
                candidate.tenantId(),
                candidate.brandId(),
                candidate.locationId(),
                candidate.channelId(),
                FulfillmentMode.PICKUP,
                now);
        return new PickupLocation(
                candidate.tenantId(),
                candidate.brandId(),
                candidate.locationId(),
                candidate.brandName(),
                candidate.locationName(),
                candidate.addressLine(),
                candidate.district(),
                candidate.city(),
                Math.round(candidate.distanceMeters()),
                answer.available(),
                answer.reason() == null ? null : answer.reason().name(),
                answer.acceptsScheduledOrders(),
                answer.preparationMinutes());
    }

    /** The response envelope permits new browse metadata without breaking list consumers. */
    public record PickupLocations(List<PickupLocation> locations) {}

    /** One branch a customer may browse, plus its current pickup availability. */
    public record PickupLocation(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String brandName,
            String locationName,
            String addressLine,
            String district,
            String city,
            long distanceMeters,
            boolean available,
            String reason,
            boolean acceptsScheduledOrders,
            Integer preparationMinutes) {}
}
