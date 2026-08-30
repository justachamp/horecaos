package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A tenant onboarding run was created (ADR 0008).
 *
 * <p>The aggregate is the tenant rather than the run, which is what puts every
 * onboarding fact for one tenant on one partition. ADR 0008 requires that
 * ordering: a consumer that sees {@code TenantActivated} before
 * {@code TenantOnboardingStarted} would have to reconstruct the order itself.
 */
public record TenantOnboardingStarted(
        UUID eventId,
        TenantId tenantId,
        UUID runId,
        UUID templateId,
        int templateVersion,
        Instant occurredAt) implements TenancyEvent {

    public TenantOnboardingStarted {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(runId, "Run ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "TenantOnboardingStarted";
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
        return new Payload(tenantId.value(), runId, templateId, templateVersion);
    }

    public record Payload(UUID tenantId, UUID runId, UUID templateId, int templateVersion) { }
}
