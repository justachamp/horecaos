package uz.horecaos.platform.customers.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A lead became the order or the reservation it was for (ADR 0111 §4): exactly one of the two ids.
 */
public record LeadConverted(
        UUID eventId,
        UUID tenantId,
        UUID leadId,
        @Nullable UUID orderId,
        @Nullable UUID reservationId,
        Instant occurredAt)
        implements CustomersEvent {

    public LeadConverted {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(leadId, "Lead ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        if ((orderId == null) == (reservationId == null)) {
            throw new IllegalArgumentException("A conversion names exactly one of an order and a reservation");
        }
    }

    @Override
    public String eventType() {
        return "LeadConverted";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(leadId, orderId, reservationId);
    }

    /** The ADR 0111 version-1 payload. */
    public record Payload(
            UUID leadId, @Nullable UUID orderId, @Nullable UUID reservationId) {}
}
