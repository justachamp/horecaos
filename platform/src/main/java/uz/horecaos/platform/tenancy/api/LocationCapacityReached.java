package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A checkout claim just filled the last concurrent-order slot (ADR 0036).
 *
 * <p>Fired by {@code ServiceabilityService.claimCapacity} on exactly the claim
 * that brings the open-hold count to the ceiling — the transaction-settled,
 * authoritative half of rule 8, not the advisory browse count. Fired once per
 * crossing rather than once per refused claim after: a claim that finds the
 * kitchen already full returns {@code AT_CAPACITY} to its caller and publishes
 * nothing, because the location became full on an earlier claim and this
 * event already told anyone listening.
 */
public record LocationCapacityReached(
        UUID eventId,
        TenantId tenantId,
        BrandId brandId,
        LocationId locationId,
        Instant occurredAt,
        int maxConcurrentOrders)
        implements TenancyEvent {

    public LocationCapacityReached {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "LocationCapacityReached";
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
