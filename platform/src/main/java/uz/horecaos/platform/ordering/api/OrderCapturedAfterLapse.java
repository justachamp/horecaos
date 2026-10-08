package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * A payment was captured for an order that had already ended as {@code PAYMENT_FAILED} when its unpaid
 * window elapsed (ADR 0170 Decision 17).
 *
 * <p>The order is never revived: its inventory hold is released, its quote has expired, its kitchen slot is
 * freed and the customer was told. The money stays recorded on the settlement, refundable through ADR
 * 0048's remedy, and this fact exists so that a person is told a refund is due. In-process only: it is
 * consumed by the operations alert fan-out in the same transaction, and no consumer outside this process is
 * promised it, so it is not an {@link OrderingEvent} and has no schema or catalogue entry. No amount, name
 * or provider detail (ADR 0029).
 */
public record OrderCapturedAfterLapse(
        UUID eventId, TenantId tenantId, UUID orderId, Instant occurredAt, UUID brandId, UUID locationId) {

    public OrderCapturedAfterLapse {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
