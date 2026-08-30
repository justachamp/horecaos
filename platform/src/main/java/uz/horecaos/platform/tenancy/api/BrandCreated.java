package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BrandCreated(
        UUID eventId,
        TenantId tenantId,
        BrandId brandId,
        Instant occurredAt,
        String code,
        String slug,
        String displayName,
        String status)
        implements TenancyEvent {

    public BrandCreated {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(status, "Brand status is required");
    }

    @Override
    public String eventType() {
        return "BrandCreated";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String aggregateType() {
        return "Brand";
    }

    @Override
    public UUID aggregateId() {
        return brandId.value();
    }

    @Override
    public Object payload() {
        return new Payload(brandId.value(), code, slug, displayName, status);
    }

    public record Payload(UUID brandId, String code, String slug, String displayName, String status) {}
}
