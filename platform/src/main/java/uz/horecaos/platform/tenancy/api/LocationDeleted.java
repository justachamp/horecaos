package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A draft location was deleted; {@link BrandDeleted} says what that guarantees a consumer. */
public record LocationDeleted(
        UUID eventId, TenantId tenantId, BrandId brandId, LocationId locationId, Instant occurredAt, String code)
        implements TenancyEvent {

    public LocationDeleted {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(code, "Location code is required");
    }

    @Override
    public String eventType() {
        return "LocationDeleted";
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
        return new Payload(locationId.value(), brandId.value(), code);
    }

    public record Payload(UUID locationId, UUID brandId, String code) {}
}
