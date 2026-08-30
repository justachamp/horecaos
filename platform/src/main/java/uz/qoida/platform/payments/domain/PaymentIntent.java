package uz.qoida.platform.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What an order needs paid (ADR 0013).
 *
 * <p>The intent is provider-neutral by construction: it holds an amount, a
 * currency, a tender, and the seller, and it survives every attempt made against
 * it. An order whose Click attempt timed out and whose Payme attempt then
 * succeeded has one intent and two attempts, and the difference between those two
 * sentences is the difference between being able to explain a settlement and not.
 *
 * @param tenderId       ADR 0046's tender, which sits between the order and this.
 *                       Empty until split tender ships; a populated value is what
 *                       lifts the one-live-intent-per-order constraint
 * @param legalEntityId  the seller, snapshotted at creation rather than resolved
 *                       on read. Empty for a cash intent and until ADR 0038's
 *                       assignment exists
 */
public record PaymentIntent(
        UUID id,
        UUID tenantId,
        UUID orderId,
        UUID brandId,
        UUID locationId,
        UUID tenderId,
        UUID legalEntityId,
        PaymentTender tender,
        PaymentMethod method,
        PaymentProviderType providerType,
        SomAmount amount,
        PaymentIntentStatus status,
        CaptureTiming captureTiming,
        String idempotencyKey,
        int version,
        Instant createdAt,
        Instant settledAt) {

    public PaymentIntent {
        Objects.requireNonNull(id, "An intent id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(orderId, "An order id is required");
        Objects.requireNonNull(tender, "A tender is required");
        Objects.requireNonNull(method, "A payment method is required");
        Objects.requireNonNull(amount, "An amount is required");
        Objects.requireNonNull(status, "A status is required");
        Objects.requireNonNull(captureTiming, "A capture timing is required");
        Objects.requireNonNull(idempotencyKey, "An idempotency key is required (ADR 0031)");

        // The same pair-completeness the CHECK constraint states, asserted here so
        // an in-memory intent cannot be built in a shape the database would refuse.
        if ((tender == PaymentTender.CASH) != (providerType == null)) {
            throw new IllegalArgumentException(
                    "A cash intent has no provider and a provider intent has one");
        }
    }

    public Optional<PaymentProviderType> provider() {
        return Optional.ofNullable(providerType);
    }

    public Optional<UUID> legalEntity() {
        return Optional.ofNullable(legalEntityId);
    }

    public boolean requiresPaymentBeforeConfirmation() {
        return captureTiming.requiredBeforeConfirmation();
    }

    /**
     * Whether this intent can produce a fiscal receipt through a payment partner.
     *
     * <p>False for cash, and not because cash is unimportant. Click's
     * {@code submit_items} needs a CLICK {@code payment_id} that a cash order does
     * not have, and Payme's fiscal data attaches to a Payme receipt that a cash
     * order does not have. Click's {@code received_cash} looks like an answer and
     * is not: it is a tender split <em>inside</em> a CLICK payment, and reading it
     * as a cash-order path produces a system that appears to fiscalize cash and
     * does not — invisible until an inspection.
     */
    public boolean partnerFiscalizationIsPossible() {
        return tender == PaymentTender.PROVIDER
                && providerType != null
                && providerType.canFiscalize();
    }
}
