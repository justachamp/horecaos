package uz.horecaos.platform.payments.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One try at one provider through one merchant account (ADR 0013).
 *
 * <p>Two fields exist only so that a lost response can be resolved, and both are
 * written and committed <em>before</em> the mutating call rather than after it.
 *
 * <p>{@link #merchantTransId()} is HorecaOS's own identifier. On Click it is the join
 * key the callback carries and the only argument {@code status_by_mti} accepts —
 * and since Click's MERCHANT API has no idempotency key anywhere, it is the entire
 * recovery mechanism. On Payme it is the {@code account.order_id} the checkout link
 * carries, deliberately opaque and non-sequential because
 * {@code CheckPerformTransaction} is unauthenticated from the customer's side and
 * sequential integers would let anyone enumerate other customers' orders.
 *
 * <p>{@link #businessDate()} is snapshotted at initiation because Click's
 * {@code status_by_mti} carries a trailing {@code YYYY-MM-DD} whose meaning and
 * timezone Click does not document. A wrong date reads as "no payment found",
 * which is exactly the answer that would make a retry look safe. Snapshotting a
 * possibly-wrong date is recoverable; not snapshotting one is not.
 *
 * @param providerCreatedAt Payme's {@code params.time} — Payme's creation moment,
 *                          which is the only clock the twelve-hour expiry may be
 *                          measured from. Payme's own Java template measures from
 *                          the merchant's clock instead, and is wrong by however
 *                          far apart the two are
 */
public record PaymentAttempt(
        UUID id,
        UUID tenantId,
        UUID intentId,
        PaymentProviderType providerType,
        UUID merchantBindingId,
        String merchantTransId,
        LocalDate businessDate,
        @Nullable String externalPaymentId,
        @Nullable String externalDocumentId,
        SomAmount amount,
        PaymentAttemptStatus status,
        @Nullable PresentationKind presentationKind,
        @Nullable ProviderEvidence evidence,
        @Nullable Instant providerCreatedAt,
        @Nullable Instant expiresAt,
        @Nullable String failureCode,
        @Nullable Uncertainty uncertainty,
        int version,
        Instant createdAt,
        @Nullable Instant settledAt) {

    /**
     * The obligation an uncertain attempt carries.
     *
     * <p>A first-observed time, a named resolver, and a deadline after which this
     * stops being an automated problem and becomes an operations exception. Held
     * as one object rather than as three loose nullable fields so that a partially
     * populated uncertainty is unconstructible, which is the same claim the three
     * pair-completeness CHECKs make in the schema.
     */
    public record Uncertainty(
            Instant since,
            UncertaintyResolver resolver,
            Instant deadline,
            int resolutionAttempts,
            @Nullable Instant resolvedAt) {

        public Uncertainty {
            Objects.requireNonNull(since, "An uncertainty needs the moment it was first observed");
            Objects.requireNonNull(resolver, "An uncertainty needs a named resolver");
            Objects.requireNonNull(deadline, "An uncertainty needs a deadline");
            if (resolutionAttempts < 0) {
                throw new IllegalArgumentException("Resolution attempts cannot be negative");
            }
        }

        public boolean pastDeadline(Instant now) {
            return now.isAfter(deadline);
        }

        public boolean resolved() {
            return resolvedAt != null;
        }
    }

    public PaymentAttempt {
        Objects.requireNonNull(id, "An attempt id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(intentId, "An intent id is required");
        Objects.requireNonNull(providerType, "A provider type is required");
        Objects.requireNonNull(merchantBindingId, "A merchant binding is required");
        Objects.requireNonNull(
                merchantTransId, "A merchant transaction id must exist before any mutating provider call");
        Objects.requireNonNull(businessDate, "A business date must be snapshotted before any mutating provider call");
        Objects.requireNonNull(amount, "An amount is required");
        Objects.requireNonNull(status, "A status is required");

        if (status == PaymentAttemptStatus.UNCERTAIN && uncertainty == null) {
            throw new IllegalArgumentException("An uncertain attempt must carry its resolver and its deadline");
        }
    }

    public Optional<String> externalPayment() {
        return Optional.ofNullable(externalPaymentId);
    }

    public Optional<ProviderEvidence> providerEvidence() {
        return Optional.ofNullable(evidence);
    }

    public Optional<Uncertainty> uncertain() {
        return Optional.ofNullable(uncertainty);
    }

    /**
     * Whether Payme's twelve-hour window has closed.
     *
     * <p>Measured from {@link #providerCreatedAt()} and never from
     * {@link #createdAt()}. An expired transaction must never be performed, which
     * is why this is asked before a capture rather than only by the sweep.
     */
    public boolean expired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    /** Whether a second charge against this attempt's intent is currently blocked. */
    public boolean blocksFurtherAttempts() {
        return status.blocksFurtherAttempts();
    }
}
