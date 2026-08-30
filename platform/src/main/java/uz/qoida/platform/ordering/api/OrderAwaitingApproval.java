package uz.qoida.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import uz.qoida.platform.tenancy.api.TenantId;

/**
 * The order is waiting for a restaurant decision (ADR 0002, ADR 0019).
 *
 * <p>Carries the deadline, because every consumer that renders this state needs
 * to count down to the same instant. A consumer computing its own deadline from
 * the policy would drift from the durable timer that will actually fire.
 */
public record OrderAwaitingApproval(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        String approvalChannel,
        Instant approvalDeadlineAt,
        String timeoutAction,
        String status,
        int orderVersion) implements OrderingEvent {

    public OrderAwaitingApproval {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(approvalDeadlineAt, "An approval deadline is required");
    }

    @Override
    public String eventType() {
        return "OrderAwaitingApproval";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(orderId, brandId, locationId, approvalChannel,
                approvalDeadlineAt.toString(), timeoutAction, status, orderVersion);
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            String approvalChannel,
            // ISO-8601 text rather than an Instant. The wire shape of a timestamp
            // must not depend on how a serializer happens to be configured: a
            // consumer reading a numeric epoch where the schema promises a
            // date-time is a contract break nothing else would have caught.
            String approvalDeadlineAt,
            String timeoutAction,
            String status,
            int orderVersion) { }
}
