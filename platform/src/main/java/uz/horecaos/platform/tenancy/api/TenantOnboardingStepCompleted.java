package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One onboarding step finished successfully (ADR 0008).
 *
 * <p>Carries the step key and nothing the step produced. A step result holds an
 * organization id, a subject id, or a count of applied configuration keys, and
 * ADR 0008 is explicit that raw configuration does not go on a topic. A consumer
 * that needs the detail reads the run through the authorized API.
 */
public record TenantOnboardingStepCompleted(
        UUID eventId,
        TenantId tenantId,
        UUID runId,
        String stepKey,
        int stepVersion,
        int attemptCount,
        Instant occurredAt)
        implements TenancyEvent {

    public TenantOnboardingStepCompleted {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(runId, "Run ID is required");
        Objects.requireNonNull(stepKey, "Step key is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "TenantOnboardingStepCompleted";
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
        return new Payload(tenantId.value(), runId, stepKey, stepVersion, attemptCount);
    }

    public record Payload(UUID tenantId, UUID runId, String stepKey, int stepVersion, int attemptCount) {}
}
