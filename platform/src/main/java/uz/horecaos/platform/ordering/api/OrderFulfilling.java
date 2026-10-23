package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * The order left the pass with a courier (ADR 0019 {@code FULFILLING}, entered from {@code READY}).
 *
 * <p>ADR 0045 names this milestone as the one that replaces the map: a recorded business transition at
 * handover, not something derived from where a phone is. The customer is told «on the way»; the
 * courier's first name, which that record allows and nothing more, is resolved by the consumer through
 * its own authorized port at the moment it renders, and is not on this event. Delivery is the only
 * fulfilment mode that reaches {@code FULFILLING} ({@code OrderStateMachine}), so the mode is on the
 * event for symmetry with its two siblings and not because a consumer has a choice to make.
 *
 * <p>Forward edges only; see {@link OrderPreparing}.
 */
public record OrderFulfilling(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        String fulfillmentMode,
        Instant handedOverAt,
        int orderVersion)
        implements OrderingEvent {

    public OrderFulfilling {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(fulfillmentMode, "A fulfilment mode is required");
        Objects.requireNonNull(handedOverAt, "A handover time is required");
    }

    @Override
    public String eventType() {
        return "OrderFulfilling";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(
                orderId, brandId, locationId, fulfillmentMode, handedOverAt.toString(), "FULFILLING", orderVersion);
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            String fulfillmentMode,
            // ISO-8601 text; see OrderAwaitingApproval for why the wire shape of a timestamp is pinned.
            String handedOverAt,
            String status,
            int orderVersion) {}
}
