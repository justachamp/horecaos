package uz.horecaos.platform.marketing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A scenario's action selection made a choice for one guest at one step (ADR 0112):
 * the step was sent, or it was blocked for a stated reason. Keyed by campaign, so one
 * scenario's facts stay in order on their topic.
 *
 * <p>The payload is the decision and nothing it was about: the reason is a code, never
 * its sentence, and there is no channel address and no text. A consumer that wants the
 * sentence reads the decision through the authorized scenario API.
 */
public record ScenarioStepDecided(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID campaignId,
        UUID customerAccountId,
        int stepSequence,
        String decision,
        @Nullable String refusalReason,
        Instant occurredAt)
        implements MarketingEvent {

    public ScenarioStepDecided {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(campaignId, "Campaign ID is required");
        Objects.requireNonNull(customerAccountId, "Customer account ID is required");
        Objects.requireNonNull(decision, "Decision is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "ScenarioStepDecided";
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
        return new Payload(campaignId, brandId, customerAccountId, stepSequence, decision, refusalReason);
    }

    /** The version-1 wire shape. A component name is a wire field name. */
    public record Payload(
            UUID campaignId,
            UUID brandId,
            UUID customerAccountId,
            int stepSequence,
            String decision,
            @Nullable String refusalReason) {}
}
