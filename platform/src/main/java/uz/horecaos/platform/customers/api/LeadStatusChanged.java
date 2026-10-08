package uz.horecaos.platform.customers.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A lead moved from one status to another (ADR 0111 §4). Two status codes. */
public record LeadStatusChanged(
        UUID eventId, UUID tenantId, UUID leadId, String fromStatus, String toStatus, Instant occurredAt)
        implements CustomersEvent {

    public LeadStatusChanged {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(leadId, "Lead ID is required");
        Objects.requireNonNull(fromStatus, "The previous status is required");
        Objects.requireNonNull(toStatus, "The new status is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "LeadStatusChanged";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(leadId, fromStatus, toStatus);
    }

    /** The ADR 0111 version-1 payload. */
    public record Payload(UUID leadId, String fromStatus, String toStatus) {}
}
