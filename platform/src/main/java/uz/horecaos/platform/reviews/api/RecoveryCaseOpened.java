package uz.horecaos.platform.reviews.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A service-recovery case opened about a review (ADR 0159, Decision 8), with its two clocks. */
public record RecoveryCaseOpened(
        UUID eventId,
        UUID tenantId,
        UUID caseId,
        UUID reviewId,
        UUID orderId,
        UUID brandId,
        UUID locationId,
        String source,
        int rating,
        Instant firstResponseDueAt,
        Instant resolutionDueAt,
        Instant occurredAt)
        implements ReviewsEvent {

    public RecoveryCaseOpened {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(caseId, "Case ID is required");
        Objects.requireNonNull(reviewId, "Review ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(source, "The source is required");
        Objects.requireNonNull(firstResponseDueAt, "The first-response due time is required");
        Objects.requireNonNull(resolutionDueAt, "The resolution due time is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "RecoveryCaseOpened";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(
                caseId, reviewId, orderId, brandId, locationId, source, rating, firstResponseDueAt, resolutionDueAt);
    }

    /** The ADR 0159 version-1 payload. */
    public record Payload(
            UUID caseId,
            UUID reviewId,
            UUID orderId,
            UUID brandId,
            UUID locationId,
            String source,
            int rating,
            Instant firstResponseDueAt,
            Instant resolutionDueAt) {}
}
