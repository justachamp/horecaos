package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * A provider's money has landed against an order still waiting on it in
 * {@code PAYMENT_AUTHORIZING} (ADR 0013, ADR 0019).
 *
 * <p>Declared here, in ordering's own {@code api}, and not in payments'.
 * Payments already depends on {@code ordering.api} for the reverse
 * direction — {@code OrderConfirmedSettlementTrigger} listens for
 * {@link OrderConfirmed}, {@code TerminalOrderPaymentVoid} listens for
 * {@link OrderCancelled} and {@link OrderExpired} — so a dependency running the
 * other way, ordering importing a payments event type, would make the two
 * modules mutually dependent, which the module graph does not permit. Payments
 * constructs and publishes this fact using a type it can already see; ordering
 * listens for a type it already owns, and imports nothing from payments to do
 * it. See {@code PaymentCaptureConfirmationTrigger}.
 *
 * <p><strong>Not an {@link OrderingEvent}.</strong> This is an inbound signal
 * telling ordering something happened elsewhere, not a business fact ordering
 * itself produces — so it carries no ADR 0032 catalogue entry and is never
 * appended to the outbox. What ordering decides in response is the outbound
 * fact: {@link OrderConfirmed} when the location auto-confirms, or
 * {@link OrderAwaitingApproval} when it requires restaurant approval, published
 * exactly as either would be from checkout. Those, not this, are what a
 * consumer sees.
 */
public record PaymentCaptured(UUID eventId, TenantId tenantId, UUID orderId, Instant occurredAt) {

    public PaymentCaptured {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
