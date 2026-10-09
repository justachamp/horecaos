package uz.horecaos.platform.reviews.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An open case passed one of its clocks (ADR 0159, Decision 10). Published once, by the worker that
 * marked the breach, and only while the case was open: a case dismissed or resolved before the worker
 * reached it is never marked and publishes nothing.
 */
public record RecoveryCaseSlaBreached(
        UUID eventId,
        UUID tenantId,
        UUID caseId,
        UUID orderId,
        UUID brandId,
        UUID locationId,
        String kind,
        Instant dueAt,
        Instant breachedAt)
        implements ReviewsEvent {

    /** {@link #kind()} for the first-response clock. */
    public static final String FIRST_RESPONSE = "FIRST_RESPONSE";

    /** {@link #kind()} for the resolution clock. */
    public static final String RESOLUTION = "RESOLUTION";

    public RecoveryCaseSlaBreached {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(caseId, "Case ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(kind, "The clock kind is required");
        Objects.requireNonNull(dueAt, "The due time is required");
        Objects.requireNonNull(breachedAt, "The breach time is required");
        if (!FIRST_RESPONSE.equals(kind) && !RESOLUTION.equals(kind)) {
            throw new IllegalArgumentException("A breach is FIRST_RESPONSE or RESOLUTION, not " + kind);
        }
    }

    @Override
    public String eventType() {
        return "RecoveryCaseSlaBreached";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Instant occurredAt() {
        return breachedAt;
    }

    @Override
    public Object payload() {
        return new Payload(caseId, orderId, brandId, locationId, kind, dueAt, breachedAt);
    }

    /** The ADR 0159 version-1 payload. */
    public record Payload(
            UUID caseId, UUID orderId, UUID brandId, UUID locationId, String kind, Instant dueAt, Instant breachedAt) {}
}
