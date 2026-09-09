package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * An amendment ended without applying (ADR 0039).
 *
 * <p>Fires from {@code OrderAmendmentService.withdraw} — an operator's own
 * choice — and from {@code apply}'s own TTL check, when the ADR 0018 quote
 * window ran out between propose and apply. Both land the amendment in the
 * database's {@code REJECTED} status; {@code reasonCode} is what tells them
 * apart, a stable code such as {@code WITHDRAWN_BY_OPERATOR} or {@code EXPIRED}
 * rather than anything an operator typed.
 *
 * <p>The database also has a scheduled TTL sweep ({@code
 * OrderAmendmentService#expireOverdue}) that lands amendments in a true {@code
 * EXPIRED} status outside a request. Nothing currently schedules it, so no
 * amendment reaches that path today, and it publishes no event of its own for
 * exactly that reason — wiring it up is a separate, pre-existing gap this
 * change does not take on.
 */
public record OrderAmendmentRejected(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        UUID amendmentId,
        int baseRevision,
        @Nullable String reasonCode)
        implements OrderingEvent {

    public OrderAmendmentRejected {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(amendmentId, "Amendment ID is required");
    }

    @Override
    public String eventType() {
        return "OrderAmendmentRejected";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(orderId, brandId, locationId, amendmentId, baseRevision, reasonCode);
    }

    public record Payload(
            UUID orderId,
            UUID brandId,
            UUID locationId,
            UUID amendmentId,
            int baseRevision,
            @Nullable String reasonCode) {}
}
