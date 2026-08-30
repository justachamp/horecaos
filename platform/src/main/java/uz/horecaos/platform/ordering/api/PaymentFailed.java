package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * A payment attempt failed before any money moved (ADR 0013).
 *
 * <p>Declared here for the same reason {@link PaymentCaptured} is: payments
 * already depends on {@code ordering.api} for the reverse direction —
 * {@code OrderConfirmedSettlementTrigger} listens for {@link OrderConfirmed},
 * {@code TerminalOrderPaymentVoid} listens for {@link OrderCancelled} and
 * {@link OrderExpired} — so a dependency running the other way would make the
 * two modules mutually dependent, which the module graph does not permit.
 * Payments constructs and publishes this fact using a type it can already see;
 * ordering listens for a type it already owns.
 *
 * <p><strong>Not an {@link OrderingEvent}.</strong> Like {@link PaymentCaptured},
 * this is an inbound signal telling ordering something happened elsewhere, not a
 * business fact ordering itself produces, so it carries no ADR 0032 catalogue
 * entry and is never appended to the outbox.
 *
 * <p>What ordering does with it is entirely local and entirely inert as far as
 * the order's own state machine is concerned: {@code
 * ordering.orders.payment_status_projection} — a rendering aid for an operations
 * list, never an authority — is set to {@code FAILED} so a BEFORE_CONFIRMATION
 * order that never got paid stops reading {@code PENDING} forever. Nothing about
 * the order's status, its approval timer or its inventory hold follows from this
 * event; a declined attempt is not, on its own, a reason to give up on the order,
 * and {@code CheckoutService}/{@code OrderStateService} already own every
 * decision that is.
 */
public record PaymentFailed(UUID eventId, TenantId tenantId, UUID orderId, Instant occurredAt) {

    public PaymentFailed {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
