package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * The food is ready (ADR 0019 {@code READY}, entered from {@code PREPARING}).
 *
 * <p>For a pickup order this is the moment the customer is told to come for it; for a delivery order
 * it is «waiting for a courier», which ADR 0045 does not message (the customer hears from the next
 * milestone, {@link OrderFulfilling}). The fulfilment mode travels on the event so a consumer makes
 * that choice from the fact and not from a second read.
 *
 * <p>Forward edges only: restoring {@code READY} from {@code FULFILLING} is ADR 0110's compensating
 * edge, a correction, and publishes nothing. See {@link OrderPreparing} for the rest of the reasoning;
 * it applies here word for word.
 */
public record OrderReady(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        String fulfillmentMode,
        Instant readyAt,
        int orderVersion)
        implements OrderingEvent {

    public OrderReady {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(fulfillmentMode, "A fulfilment mode is required");
        Objects.requireNonNull(readyAt, "A time the food was ready is required");
    }

    @Override
    public String eventType() {
        return "OrderReady";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(orderId, brandId, locationId, fulfillmentMode, readyAt.toString(), "READY", orderVersion);
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            String fulfillmentMode,
            // ISO-8601 text; see OrderAwaitingApproval for why the wire shape of a timestamp is pinned.
            String readyAt,
            String status,
            int orderVersion) {}
}
