package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A brand's name, or while it was still a draft its code or slug, was
 * corrected. Carries the whole identity after the change rather than a diff,
 * so a consumer that missed an earlier revision still ends up right.
 */
public record BrandRevised(
        UUID eventId,
        TenantId tenantId,
        BrandId brandId,
        Instant occurredAt,
        String code,
        String slug,
        String displayName,
        String status)
        implements TenancyEvent {

    public BrandRevised {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(code, "Brand code is required");
        Objects.requireNonNull(slug, "Brand slug is required");
        Objects.requireNonNull(displayName, "Brand display name is required");
        Objects.requireNonNull(status, "Brand status is required");
    }

    @Override
    public String eventType() {
        return "BrandRevised";
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
