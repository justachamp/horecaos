package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A run stopped on a required step and needs a human (ADR 0008).
 *
 * <p>The error code travels; the detail does not. A step's detail is whatever
 * the failing system said — a Keycloak message naming a user, a provider
 * response — and ADR 0008 forbids putting a stack trace or a raw error on a
 * topic. The code is enough for a consumer to route the failure, and the detail
 * is one authorized read away.
 */
public record TenantOnboardingFailed(
        UUID eventId, TenantId tenantId, UUID runId, String stepKey, String errorCode, Instant occurredAt)
        implements TenancyEvent {

    public TenantOnboardingFailed {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(runId, "Run ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "TenantOnboardingFailed";
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
        return new Payload(tenantId.value(), runId, stepKey, errorCode);
    }

    public record Payload(UUID tenantId, UUID runId, String stepKey, String errorCode) {}
}
