package uz.horecaos.platform.integration.api.marketplace;

import java.util.Set;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * What the platform may believe the partner holds after one availability push (ADR 0141
 * Decision 7). The gateway route draws this conclusion; {@code FailureClassifier} cannot.
 * It files a {@code SocketTimeoutException} and a {@code ConnectException} under the same
 * {@code TRANSIENT_INFRASTRUCTURE}, and only one of them leaves the partner's state as it
 * was.
 *
 * <ul>
 *   <li>{@link #CONFIRMED} — a success answer. The row records the value sent.
 *   <li>{@link #NOT_APPLIED} — no request was written (connection refused, breaker open, a
 *       rate-limit rejection before the send) or the partner answered with a refusal that
 *       changed nothing (a 4xx business answer, a 429). The row changes nothing about
 *       what it believes.
 *   <li>{@link #UNKNOWN} — everything else: above all a timeout or a reset after the request
 *       was written, and any 5xx whose contract does not promise atomicity. The row stops
 *       believing it knows what the partner holds, and the next tick sends the current
 *       desired value whatever it is.
 *   <li>{@link #REJECTED_UNMAPPED} — the partner answered that it does not know this item.
 *       A business answer, not retried: it appears in the mapping pane until the mapping
 *       changes.
 * </ul>
 */
public enum PushConclusion {
    CONFIRMED,
    NOT_APPLIED,
    UNKNOWN,
    REJECTED_UNMAPPED;

    /** The error code an adapter's {@code interpret} returns for "this partner has no such item". */
    public static final String UNKNOWN_ITEM = "UNKNOWN_ITEM";

    /**
     * Failures that provably happened before anything was written to the partner, or that
     * the partner refused outright: the partner's state is as it was.
     */
    private static final Set<String> NOTHING_WAS_WRITTEN = Set.of(
            "CIRCUIT_OPEN",
            "CONNECTION_FAILED",
            "CONNECT_TIMEOUT",
            "TRANSPORT_FAILURE",
            "RATE_LIMITED",
            "INSTALLATION_MISSING",
            "INSTALLATION_INACTIVE",
            "AUTHORIZATION_UNBUILDABLE",
            "METHOD_UNSUPPORTED");

    public static PushConclusion of(ProviderOutcome outcome) {
        return switch (outcome.status()) {
            case SUCCESS -> CONFIRMED;
            case UNCERTAIN -> UNKNOWN;
            case REJECTED -> UNKNOWN_ITEM.equals(outcome.errorCode()) ? REJECTED_UNMAPPED : NOT_APPLIED;
            case RETRYABLE ->
                outcome.errorCode() != null && NOTHING_WAS_WRITTEN.contains(outcome.errorCode())
                        ? NOT_APPLIED
                        : UNKNOWN;
        };
    }
}
