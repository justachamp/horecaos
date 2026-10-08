package uz.horecaos.platform.reviews.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A case moved between states (ADR 0159, Decision 8). The owner is the staff member's non-personal
 * {@code display_reference} ({@code S-0142}), never a name.
 */
public record RecoveryCaseStateChanged(
        UUID eventId,
        UUID tenantId,
        UUID caseId,
        UUID orderId,
        UUID brandId,
        UUID locationId,
        String fromState,
        String toState,
        @Nullable String outcomeCode,
        @Nullable String ownerReference,
        Instant changedAt)
        implements ReviewsEvent {

    public RecoveryCaseStateChanged {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(caseId, "Case ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(fromState, "The previous state is required");
        Objects.requireNonNull(toState, "The new state is required");
        Objects.requireNonNull(changedAt, "The change time is required");
    }

    @Override
    public String eventType() {
        return "RecoveryCaseStateChanged";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Instant occurredAt() {
        return changedAt;
    }

    @Override
    public Object payload() {
        return new Payload(
                caseId, orderId, brandId, locationId, fromState, toState, outcomeCode, ownerReference, changedAt);
    }

    /** The ADR 0159 version-1 payload. */
    public record Payload(
            UUID caseId,
            UUID orderId,
            UUID brandId,
            UUID locationId,
            String fromState,
            String toState,
            @Nullable String outcomeCode,
            @Nullable String ownerReference,
            Instant changedAt) {}
}
