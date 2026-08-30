package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * Money that had been captured against this order has gone back, in whole or in
 * part (ADR 0013, ADR 0048).
 *
 * <p>Two different facts in payments produce this, and both are legitimate:
 *
 * <ul>
 *   <li>a provider reports a reversal on a captured attempt —
 *       {@code PaymentAttemptService.applyToIntent}'s {@code REVERSED} case,
 *       reached from Payme's inbound {@code CancelTransaction} after a cabinet
 *       refund, or from Click's own reversal of a capture that landed against an
 *       order that could no longer be fulfilled;</li>
 *   <li>an operator records a refund or a delivery-fee reimbursement under ADR
 *       0048's bookkeeping model — {@code OrderRemedyService.recordMoneyRemedy}
 *       — which is the platform's only sanctioned way to initiate a refund at
 *       all: "Qoida never calls a payment provider's refund API." Staff refund in
 *       the provider's own cabinet or hand back cash, and this fact is Qoida
 *       recording that they did.</li>
 * </ul>
 *
 * <p>Declared here for the same reason {@link PaymentCaptured} is: see that
 * type's Javadoc for why this lives in ordering's own {@code api} rather than
 * payments', and why that is the direction the module graph permits.
 *
 * <p><strong>Not an {@link OrderingEvent}.</strong> An inbound signal, not a
 * business fact ordering itself produces — no ADR 0032 catalogue entry, never
 * appended to the outbox.
 *
 * <p>Moves {@code ordering.orders.payment_status_projection} to {@code
 * REFUNDED}. Nothing else: the projection mirrors the payment/remedy aggregate
 * and never decides an order's own state on its own account. A cash order's
 * projection is {@code NOT_REQUIRED} and stays there even when it is genuinely
 * refunded through {@code OrderRemedyService} — {@code
 * JdbcOrderStore.updatePaymentProjection} refuses to move a {@code NOT_REQUIRED}
 * projection at all, because that value means "no online payment is tracked
 * here", not "no payment happened yet".
 */
public record PaymentRefunded(UUID eventId, TenantId tenantId, UUID orderId, Instant occurredAt) {

    public PaymentRefunded {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
