package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * A payment attempt was released with no money captured — most often {@code
 * TerminalOrderPaymentVoid} closing the provider's side of an order that ended
 * unpaid (ADR 0007, ADR 0013), but any attempt that reaches {@code CANCELLED}
 * without ever having captured produces the same fact.
 *
 * <p>Declared here for the same reason {@link PaymentCaptured} is: see that
 * type's Javadoc for why this lives in ordering's own {@code api} rather than
 * payments', and why that is the direction the module graph permits.
 *
 * <p><strong>Not an {@link OrderingEvent}.</strong> An inbound signal, not a
 * business fact ordering itself produces — no ADR 0032 catalogue entry, never
 * appended to the outbox.
 *
 * <p>Moves {@code ordering.orders.payment_status_projection} to {@code VOIDED}
 * and nothing else. The order's own status is already handled by whatever ended
 * the order in the first place — this event only ever arrives after that
 * decision, never before it, so there is no order-state consequence left to
 * draw from it.
 */
public record PaymentVoided(UUID eventId, TenantId tenantId, UUID orderId, Instant occurredAt) {

    public PaymentVoided {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
