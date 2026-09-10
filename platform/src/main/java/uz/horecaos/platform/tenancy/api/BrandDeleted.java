package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A draft brand was deleted. Only a brand that never left {@code DRAFT} and
 * that nothing referred to can be, so a consumer holding a projection of it
 * can drop that projection outright.
 */
public record BrandDeleted(UUID eventId, TenantId tenantId, BrandId brandId, Instant occurredAt, String code)
        implements TenancyEvent {

    public BrandDeleted {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(code, "Brand code is required");
    }

    @Override
    public String eventType() {
        return "BrandDeleted";
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
        return new Payload(brandId.value(), code);
    }

    public record Payload(UUID brandId, String code) {}
}
