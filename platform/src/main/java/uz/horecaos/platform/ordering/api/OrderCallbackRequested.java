package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * A customer needs a callback (ADR 0039).
 *
 * <p>Raised through the {@code SET_CALLBACK_REQUESTED} amendment command with
 * {@code requested = true} — the only way the flag is ever set. Carries no
 * reason and no note: the callback flag is deliberately a bare signal that
 * work exists, and whatever an operator wrote about why lives behind the
 * ordinary order API, never on this topic.
 */
public record OrderCallbackRequested(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        UUID amendmentId,
        int orderVersion)
        implements OrderingEvent {

    public OrderCallbackRequested {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(amendmentId, "Amendment ID is required");
    }

    @Override
    public String eventType() {
        return "OrderCallbackRequested";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(orderId, brandId, locationId, amendmentId, orderVersion);
    }

    public record Payload(UUID orderId, UUID brandId, UUID locationId, UUID amendmentId, int orderVersion) {}
}
