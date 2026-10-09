package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * An unpaid order's window elapsed and the order ended as {@code PAYMENT_FAILED} (ADR 0170, ADR 0019,
 * ADR 0039).
 *
 * <p>Distinct from {@link OrderCancelled} on purpose: a payment that never came is the platform's own
 * commercial fact, not a customer's change of mind or an operator's decision, and ADR 0039 separated the
 * four terminal outcomes so that reports, customer wording and the provider's side can tell them apart.
 * {@code payments.application.TerminalOrderPaymentVoid} closes the provider's live attempt on it, exactly as
 * it does for a cancellation or an expiry; the customer's notification names the reason.
 *
 * <p>Ids, the window and the status only: no name, phone, address or amount (ADR 0029).
 *
 * @param windowMinutes the {@code ordering.payment_window} that elapsed, in whole minutes
 */
public record OrderPaymentLapsed(
        UUID eventId,
        TenantId tenantId,
        UUID orderId,
        Instant occurredAt,
        UUID brandId,
        UUID locationId,
        int windowMinutes,
        String status,
        int orderVersion)
        implements OrderingEvent {

    public OrderPaymentLapsed {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(status, "Status is required");
    }

    @Override
    public String eventType() {
        return "OrderPaymentLapsed";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(orderId, brandId, locationId, windowMinutes, status, orderVersion);
    }

    public record Payload(
            UUID orderId, UUID brandId, UUID locationId, int windowMinutes, String status, int orderVersion) {}
}
