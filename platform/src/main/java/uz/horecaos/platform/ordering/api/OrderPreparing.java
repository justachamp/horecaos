package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * The kitchen has started on the order (ADR 0019 {@code PREPARING}, entered from {@code CONFIRMED}).
 *
 * <p>Until ADR 0184 this transition was recorded in {@code ordering.order_state_history} and had no
 * event, for the reason {@code OrderCompleted}'s own history gives: nothing outside ordering read it.
 * Now something does. ADR 0045 lists «being prepared» among the milestones a customer is told, as an
 * ADR 0020 message of the optional transactional class, and {@code notifications} cannot trigger a
 * message from a row in a history table.
 *
 * <p>Forward edges only. A correction that restores {@code PREPARING} from {@code READY} (ADR 0110's
 * compensating edge) is not this event: telling a customer twice that the kitchen has started would be
 * the opposite of a correction, and the notification is keyed once per order in any case.
 *
 * <p>The fulfilment mode travels so a consumer can word the message, or decline to send it, without
 * calling back for it. Nothing personal does: who ordered is resolved through an authorized call,
 * because ADR 0029 keeps personal data off every topic.
 */
public record OrderPreparing(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        String fulfillmentMode,
        Instant preparingAt,
        int orderVersion)
        implements OrderingEvent {

    public OrderPreparing {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(fulfillmentMode, "A fulfilment mode is required");
        Objects.requireNonNull(preparingAt, "A time the kitchen started is required");
    }

    @Override
    public String eventType() {
        return "OrderPreparing";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(
                orderId, brandId, locationId, fulfillmentMode, preparingAt.toString(), "PREPARING", orderVersion);
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            String fulfillmentMode,
            // ISO-8601 text; see OrderAwaitingApproval for why the wire shape of a timestamp is pinned.
            String preparingAt,
            String status,
            int orderVersion) {}
}
