package uz.horecaos.platform.payments.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * What one provider call came to (ADR 0013, ADR 0007).
 *
 * <p>The four classifications are deliberately ADR 0007's, the same four the
 * delivery adapters answer with, because the discipline is the same one and the
 * platform should not hold two vocabularies for "we do not know whether that
 * worked". This is a payments-local type rather than
 * {@code integration.api.provider.ProviderOutcome} for two reasons: payments needs
 * the provider's state, its identifiers, and the amount it observed as typed
 * fields rather than as entries in a loose map, and ADR 0029 forbids a payment
 * payload — which is full of personal data — travelling in an untyped bag that
 * nothing stops from being logged.
 *
 * <p>{@link Classification#UNCERTAIN} is the one that matters. Most integration
 * bugs come from collapsing it into {@code RETRYABLE}; here that collapse is a
 * second charge on a customer's card, because Click's MERCHANT API carries no
 * idempotency key on any call.
 *
 * @param observedStatus  the HorecaOS state the adapter read from the provider's
 *                        answer, or null when the answer does not say — which is
 *                        every uncertain outcome and most rejections
 * @param observedAmount  what the provider says was charged, in whole som, already
 *                        converted back from tiyin by the adapter. Null when the
 *                        provider reported none
 * @param failureCode     a HorecaOS code, never the provider's. The domain never sees
 *                        a provider error code: the two vocabularies share nothing,
 *                        so the mapping belongs entirely to the adapter
 */
public record ProviderOutcome(
        Classification classification,
        PaymentAttemptStatus observedStatus,
        ProviderEvidence evidence,
        String externalPaymentId,
        String externalDocumentId,
        SomAmount observedAmount,
        String failureCode,
        String detail,
        Duration retryAfter) {

    public enum Classification {

        /** The provider completed the operation and said so. */
        SUCCESS,

        /** The provider refused on business grounds. Retrying changes nothing. */
        REJECTED,

        /**
         * Transport or provider fault on a call that changed nothing.
         *
         * <p>Only ever answered for a read. A mutating call whose response was
         * lost is {@link #UNCERTAIN}, never this, however transient the failure
         * looked.
         */
        RETRYABLE,

        /**
         * The provider may or may not have acted.
         *
         * <p>Resolve by querying before anything else happens. Never retry.
         */
        UNCERTAIN
    }

    public ProviderOutcome {
        Objects.requireNonNull(classification, "A classification is required");
    }

    public static ProviderOutcome success(
            PaymentAttemptStatus observedStatus,
            ProviderEvidence evidence,
            String externalPaymentId,
            SomAmount observedAmount) {
        return new ProviderOutcome(
                Classification.SUCCESS,
                observedStatus,
                evidence,
                externalPaymentId,
                null,
                observedAmount,
                null,
                null,
                null);
    }

    public static ProviderOutcome rejected(String failureCode, String detail, ProviderEvidence evidence) {
        return new ProviderOutcome(
                Classification.REJECTED,
                PaymentAttemptStatus.FAILED,
                evidence,
                null,
                null,
                null,
                failureCode,
                detail,
                null);
    }

    public static ProviderOutcome retryable(String failureCode, String detail, Duration retryAfter) {
        return new ProviderOutcome(
                Classification.RETRYABLE, null, null, null, null, null, failureCode, detail, retryAfter);
    }

    public static ProviderOutcome uncertain(String failureCode, String detail) {
        return new ProviderOutcome(Classification.UNCERTAIN, null, null, null, null, null, failureCode, detail, null);
    }

    public boolean requiresReconciliation() {
        return classification == Classification.UNCERTAIN;
    }

    /** Whether the caller may attempt the same operation again without reconciling first. */
    public boolean mayRetryDirectly() {
        return classification == Classification.RETRYABLE;
    }

    public Optional<ProviderEvidence> providerEvidence() {
        return Optional.ofNullable(evidence);
    }

    public Optional<SomAmount> amount() {
        return Optional.ofNullable(observedAmount);
    }

    public Optional<Duration> retryDelay() {
        return Optional.ofNullable(retryAfter);
    }
}
