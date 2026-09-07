package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A release just opened the first free concurrent-order slot (ADR 0036).
 *
 * <p>The mirror of {@link LocationCapacityReached}: fired by {@code
 * ServiceabilityService.releaseCapacity} on exactly the release that drops the
 * open-hold count from the ceiling to one below it — the crossing back the
 * other way, not every release. A kitchen with slack releases silently, the
 * same way a claim below the ceiling claims silently; only the crossing itself
 * is a fact worth a consumer's attention.
 */
public record LocationCapacityCleared(
        UUID eventId,
        TenantId tenantId,
        BrandId brandId,
        LocationId locationId,
        Instant occurredAt,
        int maxConcurrentOrders)
        implements TenancyEvent {

    public LocationCapacityCleared {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "LocationCapacityCleared";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String aggregateType() {
        return "Location";
    }

    @Override
    public UUID aggregateId() {
        return locationId.value();
    }

    @Override
    public Object payload() {
        return new Payload(locationId.value(), brandId.value(), maxConcurrentOrders);
    }

    public record Payload(UUID locationId, UUID brandId, int maxConcurrentOrders) {}
}
