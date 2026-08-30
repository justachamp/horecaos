package uz.qoida.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import uz.qoida.platform.tenancy.api.TenantId;

/**
 * The restaurant declined (ADR 0002, ADR 0019).
 *
 * <p>{@code reasonCode} is a stable code, never free text: a rejection reason a
 * member of staff typed would be personal data on a Kafka topic, and would be
 * untranslatable in the customer's app.
 */
public record OrderRejected(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        String decisionChannel,
        String reasonCode,
        String status,
        int orderVersion) implements OrderingEvent {

    public OrderRejected {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "OrderRejected";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(orderId, brandId, locationId, decisionChannel, reasonCode,
                status, orderVersion);
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            String decisionChannel,
            String reasonCode,
            String status,
            int orderVersion) { }
}
