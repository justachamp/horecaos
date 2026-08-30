package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * The commercial commitment (ADR 0019).
 *
 * <p>The customer is told the order is accepted from this fact and nothing else.
 * In particular, POS export does not gate it: a POS outage would otherwise become
 * a customer-facing checkout outage and the restaurant would lose the revenue for
 * an integration problem.
 *
 * <p>This is the fact the inventory process manager commits a reservation on.
 */
public record OrderConfirmed(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        String acceptanceMode,
        String decisionChannel,
        Instant confirmedAt,
        String currency,
        long totalMinor,
        String status,
        int orderVersion)
        implements OrderingEvent {

    public OrderConfirmed {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(confirmedAt, "A confirmation time is required");
    }

    @Override
    public String eventType() {
        return "OrderConfirmed";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(
                orderId,
                brandId,
                locationId,
                acceptanceMode,
                decisionChannel,
                confirmedAt.toString(),
                currency,
                totalMinor,
                status,
                orderVersion);
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            String acceptanceMode,
            String decisionChannel,
            // ISO-8601 text; see OrderAwaitingApproval for why the wire shape of a
            // timestamp is pinned rather than left to the serializer.
            String confirmedAt,
            String currency,
            long totalMinor,
            String status,
            int orderVersion) {}
}
