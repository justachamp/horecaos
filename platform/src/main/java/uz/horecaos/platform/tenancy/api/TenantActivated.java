package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The tenant is live (ADR 0008).
 *
 * <p>The one onboarding fact other modules act on rather than observe, so it
 * carries the tenant's new status explicitly instead of leaving a consumer to
 * infer it from the event's name.
 */
public record TenantActivated(UUID eventId, TenantId tenantId, UUID runId, String status, Instant occurredAt)
        implements TenancyEvent {

    public TenantActivated {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(runId, "Run ID is required");
        Objects.requireNonNull(status, "Tenant status is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "TenantActivated";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String aggregateType() {
        return "Tenant";
    }

    @Override
    public UUID aggregateId() {
        return tenantId.value();
    }

    @Override
    public Object payload() {
        return new Payload(tenantId.value(), runId, status);
    }

    public record Payload(UUID tenantId, UUID runId, String status) {}
}
