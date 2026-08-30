package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Every required readiness step passed (ADR 0008).
 *
 * <p>Ready is not live. Activation is a separate platform decision, and this
 * fact exists so that the decision can be prompted rather than polled for.
 */
public record TenantReady(UUID eventId, TenantId tenantId, UUID runId, Instant occurredAt) implements TenancyEvent {

    public TenantReady {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(runId, "Run ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "TenantReady";
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
        return new Payload(tenantId.value(), runId);
    }

    public record Payload(UUID tenantId, UUID runId) {}
}
