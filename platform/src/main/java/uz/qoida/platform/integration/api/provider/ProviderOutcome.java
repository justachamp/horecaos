package uz.qoida.platform.integration.api.provider;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The canonical result of a provider call (ADR 0007).
 *
 * <p>Four outcomes, and the fourth is the one that matters. Most integration
 * bugs come from collapsing {@code UNCERTAIN} into {@code RETRYABLE}: a timeout
 * after the provider accepted a request is not a failure to retry, it is a
 * request whose outcome must be discovered before anything else happens.
 */
public record ProviderOutcome(
        Status status,
        Map<String, Object> normalized,
        String externalReference,
        String errorCode,
        String detail,
        Duration retryAfter) {

    public enum Status {
        /** The provider completed the operation. */
        SUCCESS,

        /** The provider refused on business grounds. Retrying changes nothing. */
        REJECTED,

        /** Transport or provider fault. Safe to retry under the same idempotency key. */
        RETRYABLE,

        /**
         * The provider may or may not have acted. Reconcile by querying before
         * any further attempt; never retry blindly.
         */
        UNCERTAIN
    }

    public static ProviderOutcome success(Map<String, Object> normalized, String externalReference) {
        return new ProviderOutcome(Status.SUCCESS, normalized, externalReference, null, null, null);
    }

    public static ProviderOutcome rejected(String errorCode, String detail) {
        return new ProviderOutcome(Status.REJECTED, Map.of(), null, errorCode, detail, null);
    }

    public static ProviderOutcome retryable(String errorCode, String detail, Duration retryAfter) {
        return new ProviderOutcome(Status.RETRYABLE, Map.of(), null, errorCode, detail, retryAfter);
    }

    public static ProviderOutcome uncertain(String errorCode, String detail) {
        return new ProviderOutcome(Status.UNCERTAIN, Map.of(), null, errorCode, detail, null);
    }

    public Optional<Duration> retryDelay() {
        return Optional.ofNullable(retryAfter);
    }

    /** Whether a caller may attempt the same operation again without reconciling first. */
    public boolean mayRetryDirectly() {
        return status == Status.RETRYABLE;
    }

    /** Whether the outcome must be discovered before anything else is attempted. */
    public boolean requiresReconciliation() {
        return status == Status.UNCERTAIN;
    }
}
