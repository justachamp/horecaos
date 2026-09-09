package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * A raised callback was cleared (ADR 0039).
 *
 * <p>Raised through the same {@code SET_CALLBACK_REQUESTED} amendment command
 * as {@link OrderCallbackRequested}, this time with {@code requested = false}
 * — ADR 0039 deliberately makes clearing the same command rather than an
 * eleventh one. Carries no identifier for who resolved it: {@code
 * callback_resolved_by} on the order row is an operator subject, and this
 * event does not need to name one for a consumer to know the work is done.
 */
public record OrderCallbackResolved(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        UUID amendmentId,
        int orderVersion)
        implements OrderingEvent {

    public OrderCallbackResolved {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(amendmentId, "Amendment ID is required");
    }

    @Override
    public String eventType() {
        return "OrderCallbackResolved";
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
