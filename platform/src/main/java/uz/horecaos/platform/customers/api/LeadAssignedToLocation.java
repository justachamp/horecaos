package uz.horecaos.platform.customers.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A lead was handed to a branch (ADR 0111 §5 and §6): a field, not a workflow. */
public record LeadAssignedToLocation(UUID eventId, UUID tenantId, UUID leadId, UUID locationId, Instant occurredAt)
        implements CustomersEvent {

    public LeadAssignedToLocation {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(leadId, "Lead ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "LeadAssignedToLocation";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(leadId, locationId);
    }

    /** The ADR 0111 version-1 payload. */
    public record Payload(UUID leadId, UUID locationId) {}
}
