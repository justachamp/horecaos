package uz.horecaos.platform.reviews.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A customer rated a completed order (ADR 0159). Whether there is a comment, and how many tags -- never either. */
public record ReviewSubmitted(
        UUID eventId,
        UUID tenantId,
        UUID reviewId,
        UUID orderId,
        UUID brandId,
        UUID locationId,
        int rating,
        boolean hasComment,
        int tagCount,
        Instant submittedAt)
        implements ReviewsEvent {

    public ReviewSubmitted {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(reviewId, "Review ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(submittedAt, "Submission time is required");
    }

    @Override
    public String eventType() {
        return "ReviewSubmitted";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Instant occurredAt() {
        return submittedAt;
    }

    @Override
    public Object payload() {
        return new Payload(reviewId, orderId, brandId, locationId, rating, hasComment, tagCount, submittedAt);
    }

    /** The ADR 0159 version-1 payload. */
    public record Payload(
            UUID reviewId,
            UUID orderId,
            UUID brandId,
            UUID locationId,
            int rating,
            boolean hasComment,
            int tagCount,
            Instant submittedAt) {}
}
