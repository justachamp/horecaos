package uz.horecaos.platform.customers.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A lead was created (ADR 0111 §4). Source and brand, and nothing about the guest. */
public record LeadRegistered(UUID eventId, UUID tenantId, UUID leadId, UUID brandId, String source, Instant occurredAt)
        implements CustomersEvent {

    public LeadRegistered {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(leadId, "Lead ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(source, "A source is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "LeadRegistered";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(leadId, tenantId, brandId, source);
    }

    /** The ADR 0111 version-1 payload. */
    public record Payload(UUID leadId, UUID tenantId, UUID brandId, String source) {}
}
