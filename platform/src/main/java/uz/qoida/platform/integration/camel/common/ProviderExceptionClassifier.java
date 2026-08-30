package uz.qoida.platform.integration.camel.common;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;

import org.springframework.stereotype.Component;

import uz.qoida.platform.integration.api.provider.ProviderOutcome;

/**
 * Maps transport failures onto canonical outcomes (ADR 0007).
 *
 * <p>Shared so every adapter classifies the same way. The distinction that
 * matters is whether the provider might already have acted: a connection refused
 * before the request was sent is safe to retry, while a read timeout after it
 * was sent is not, because the provider may have processed it and only the reply
 * was lost.
 *
 * <p>A business rejection is never retried as infrastructure. Retrying a 400
 * produces the same 400 forever while looking like an outage.
 */
@Component
public class ProviderExceptionClassifier {

    public ProviderOutcome classify(int statusCode, String body, Duration retryAfter) {
        if (statusCode >= 200 && statusCode < 300) {
            return ProviderOutcome.success(java.util.Map.of("body", body == null ? "" : body), null);
        }
        return classifyFailureStatus(statusCode, body, retryAfter);
    }

    /**
     * Classifies a non-2xx response.
     *
     * <p>Split out so {@link ProviderHttpClient} can share exactly this logic
     * instead of keeping its own copy. It had one, and the copies had drifted:
     * 408 and 425 were {@code UNCERTAIN} here and {@code RETRYABLE} there, and
     * the wired-in path was the unsafe one.
     */
    public ProviderOutcome classifyFailureStatus(int statusCode, String body, Duration retryAfter) {
        String detail = body == null ? "" : body;
        if (statusCode == 408 || statusCode == 425) {
            // The provider received the request and timed out processing it, so
            // it may well have acted. Reconcile rather than retry.
            return ProviderOutcome.uncertain("PROVIDER_TIMEOUT", detail);
        }
        if (statusCode == 409) {
            // A conflict usually means the provider already holds what we tried
            // to create. Which of the two it is can only be discovered by asking.
            return ProviderOutcome.uncertain("PROVIDER_CONFLICT", detail);
        }
        if (statusCode == 429) {
            return ProviderOutcome.retryable(
                    "RATE_LIMITED", detail,
                    retryAfter == null ? Duration.ofSeconds(30) : retryAfter);
        }
        if (statusCode == 401 || statusCode == 403) {
            // Configuration or credential failure. Retrying on a timer would
            // hide a rotation that never happened.
            return ProviderOutcome.rejected("PROVIDER_AUTHENTICATION", detail);
        }
        if (statusCode >= 400 && statusCode < 500) {
            return ProviderOutcome.rejected("PROVIDER_REJECTED", detail);
        }
        if (statusCode >= 500) {
            // A deliberate exception to the "assume it was received" rule. A 5xx
            // is the provider itself answering, and every partner here documents
            // it as a failed request. Treating it as uncertain would send every
            // transient blip to manual reconciliation.
            return ProviderOutcome.retryable("PROVIDER_UNAVAILABLE", detail,
                    retryAfter == null ? Duration.ofSeconds(10) : retryAfter);
        }
        return ProviderOutcome.retryable("PROVIDER_UNKNOWN", detail, null);
    }

    /**
     * Classifies a thrown transport failure.
     *
     * @param requestSent whether the request was known to have left this process.
     *                    This is the whole distinction: not-sent is retryable,
     *                    sent-then-lost is uncertain.
     */
    public ProviderOutcome classify(Throwable failure, boolean requestSent) {
        if (failure instanceof SocketTimeoutException) {
            return requestSent
                    ? ProviderOutcome.uncertain("READ_TIMEOUT", "No response after the request was sent")
                    : ProviderOutcome.retryable("CONNECT_TIMEOUT", "Could not reach the provider", null);
        }
        if (failure instanceof IOException) {
            return requestSent
                    ? ProviderOutcome.uncertain("CONNECTION_RESET", "Connection lost after sending")
                    : ProviderOutcome.retryable("CONNECTION_FAILED", "Could not reach the provider", null);
        }
        // Everything else — a JSON parse failure on a 2xx body, a mapping error
        // inside an adapter, an unexpected runtime fault. Jackson 3's
        // JacksonException extends RuntimeException rather than IOException, so
        // these reach here rather than the branch above, and they run *after*
        // the provider has answered: the courier is already dispatched.
        //
        // requestSent is honoured here for exactly that reason. Returning
        // RETRYABLE unconditionally, as this did, hands the caller a licence to
        // re-send a create the provider has already executed.
        return requestSent
                ? ProviderOutcome.uncertain("RESPONSE_UNREADABLE",
                        "The provider answered but the response could not be interpreted: "
                                + failure.getClass().getSimpleName())
                : ProviderOutcome.retryable("TRANSPORT_FAILURE", failure.getClass().getSimpleName(), null);
    }
}
