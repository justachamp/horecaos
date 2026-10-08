package uz.horecaos.platform.marketing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A guest's run through a scenario ended (ADR 0112): finished, or stopped by a
 * condition, by consent withdrawn, or by a suppression. The outcome is a code from a
 * closed set.
 */
public record ScenarioParticipantStopped(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID campaignId,
        UUID customerAccountId,
        String outcome,
        Instant occurredAt)
        implements MarketingEvent {

    public ScenarioParticipantStopped {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(campaignId, "Campaign ID is required");
        Objects.requireNonNull(customerAccountId, "Customer account ID is required");
        Objects.requireNonNull(outcome, "Outcome is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "ScenarioParticipantStopped";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String aggregateType() {
        return "MarketingScenario";
    }

    @Override
    public UUID aggregateId() {
        return campaignId;
    }

    @Override
    public Object payload() {
        return new Payload(campaignId, brandId, customerAccountId, outcome);
    }

    public record Payload(UUID campaignId, UUID brandId, UUID customerAccountId, String outcome) {}
}
