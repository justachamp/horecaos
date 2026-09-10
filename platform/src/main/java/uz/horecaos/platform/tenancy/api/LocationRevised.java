package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A location's name, or while it was still a draft its code, slug or
 * timezone, was corrected. Like {@link BrandRevised}, the whole identity after
 * the change; like {@link LocationCreated}, never the address.
 */
public record LocationRevised(
        UUID eventId,
        TenantId tenantId,
        BrandId brandId,
        LocationId locationId,
        Instant occurredAt,
        String code,
        String slug,
        String displayName,
        String timezone,
        String status)
        implements TenancyEvent {

    public LocationRevised {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(code, "Location code is required");
        Objects.requireNonNull(slug, "Location slug is required");
        Objects.requireNonNull(displayName, "Location display name is required");
        Objects.requireNonNull(timezone, "Location timezone is required");
        Objects.requireNonNull(status, "Location status is required");
    }

    @Override
    public String eventType() {
        return "LocationRevised";
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
        return new Payload(locationId.value(), brandId.value(), code, slug, displayName, timezone, status);
    }

    public record Payload(
            UUID locationId,
            UUID brandId,
            String code,
            String slug,
            String displayName,
            String timezone,
            String status) {}
}
