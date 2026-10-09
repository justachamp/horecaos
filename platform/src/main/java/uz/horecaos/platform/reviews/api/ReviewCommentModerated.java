package uz.horecaos.platform.reviews.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A review's comment moved between publication states (ADR 0159, Decision 7): a moderator's decision, the
 * customer's withdrawal, or an erasure. States and a reason code; never the comment, and never a rating --
 * a rating is not moderated.
 */
public record ReviewCommentModerated(
        UUID eventId,
        UUID tenantId,
        UUID reviewId,
        UUID orderId,
        UUID brandId,
        String fromState,
        String toState,
        String reasonCode,
        String actorType,
        Instant occurredAt)
        implements ReviewsEvent {

    public ReviewCommentModerated {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(reviewId, "Review ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(fromState, "The previous state is required");
        Objects.requireNonNull(toState, "The new state is required");
        Objects.requireNonNull(reasonCode, "A reason code is required");
        Objects.requireNonNull(actorType, "The actor type is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "ReviewCommentModerated";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(reviewId, orderId, brandId, fromState, toState, reasonCode, actorType);
    }

    /** The ADR 0159 version-1 payload. */
    public record Payload(
            UUID reviewId,
            UUID orderId,
            UUID brandId,
            String fromState,
            String toState,
            String reasonCode,
            String actorType) {}
}
