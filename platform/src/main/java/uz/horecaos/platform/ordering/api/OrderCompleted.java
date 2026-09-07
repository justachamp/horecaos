package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * The order was fulfilled (ADR 0019).
 *
 * <p>{@code OrderStateService} deferred this one deliberately, and said so in
 * place: PREPARING, READY, FULFILLING and COMPLETED "have no external consumer
 * in this slice… rather than being published now to a catalogue nobody reads."
 * That was right, and the condition has changed — ADR 0075's rating prompt has
 * to know an order finished, and ADR 0071 accepts a review only once it has. So
 * this is the one of the four that now has a reader, and the other three stay
 * unpublished for the reason that comment gives.
 *
 * <p>Terminal, and therefore final: nothing leaves {@code COMPLETED}. A consumer
 * may treat this as the last word on the order without waiting for anything
 * further.
 *
 * <p>Carries no customer. Who ordered is resolved through an authorized call —
 * ADR 0029 keeps personal data out of every payload, and a rating prompt needs
 * the order id and nothing about the person to find the chat to send it to.
 */
public record OrderCompleted(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        Instant completedAt,
        String currency,
        long totalMinor,
        int orderVersion)
        implements OrderingEvent {

    public OrderCompleted {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(completedAt, "A completion time is required");
    }

    @Override
    public String eventType() {
        return "OrderCompleted";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(orderId, brandId, locationId, completedAt.toString(), currency, totalMinor, orderVersion);
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            // ISO-8601 text; see OrderAwaitingApproval for why the wire shape of a
            // timestamp is pinned rather than left to the serializer.
            String completedAt,
            String currency,
            long totalMinor,
            int orderVersion) {}
}
