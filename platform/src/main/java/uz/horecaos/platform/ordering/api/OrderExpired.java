package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * Nobody decided in time (ADR 0002, ADR 0019).
 *
 * <p>Distinct from {@link OrderRejected} on purpose. "The restaurant declined"
 * and "the restaurant never looked" lead to different customer wording, different
 * operational follow-up, and different quality metrics for the branch; one code
 * covering both would hide the second inside the first.
 */
public record OrderExpired(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        Instant approvalDeadlineAt,
        String status,
        int orderVersion) implements OrderingEvent {

    public OrderExpired {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "OrderExpired";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(orderId, brandId, locationId,
                approvalDeadlineAt == null ? null : approvalDeadlineAt.toString(), status,
                orderVersion);
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            // ISO-8601 text; see OrderAwaitingApproval for why the wire shape of a
            // timestamp is pinned rather than left to the serializer.
            String approvalDeadlineAt,
            String status,
            int orderVersion) { }
}
