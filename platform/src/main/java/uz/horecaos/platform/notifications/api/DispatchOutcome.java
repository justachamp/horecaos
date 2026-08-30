package uz.horecaos.platform.notifications.api;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * What came back from a send (ADR 0020, ADR 0007).
 *
 * <p>The four cases mirror ADR 0007's provider outcome, restated here so the
 * notifications module does not import an integration type to describe its own
 * domain. The fourth is the one that matters: an {@link Status#UNCERTAIN} send is
 * not a failure to retry, it is a send whose outcome has to be discovered before
 * anything else happens. Collapsing it into {@code RETRYABLE} is how a customer
 * receives two confirmations for one order.
 *
 * @param providerStatus the provider's own word, kept verbatim. "accepted" and
 *                       "delivered to handset" are different promises and support
 *                       conversations turn on which one was actually given
 * @param providerBindingId which ADR 0026 account handled it, null when the call
 *                          failed before one was resolved. Recorded on the
 *                          attempt because "which gateway did we use?" is half of
 *                          any answer about a message that did not arrive
 */
public record DispatchOutcome(
        Status status,
        String externalMessageId,
        String providerStatus,
        String errorCode,
        String detail,
        Duration retryAfter,
        UUID providerBindingId,
        String providerType) {

    public enum Status {

        /** The provider took it. How strong that is depends on providerStatus. */
        ACCEPTED,

        /** Refused on business grounds. Retrying produces the same refusal. */
        REJECTED,

        /** Transport or provider fault, safe to repeat under the same key. */
        RETRYABLE,

        /** The provider may already have sent it. Ask before doing anything else. */
        UNCERTAIN
    }

    public static DispatchOutcome accepted(String externalMessageId, String providerStatus) {
        return new DispatchOutcome(Status.ACCEPTED, externalMessageId, providerStatus, null, null,
                null, null, null);
    }

    public static DispatchOutcome rejected(String errorCode, String detail) {
        return new DispatchOutcome(Status.REJECTED, null, null, errorCode, detail, null, null, null);
    }

    public static DispatchOutcome retryable(String errorCode, String detail, Duration retryAfter) {
        return new DispatchOutcome(Status.RETRYABLE, null, null, errorCode, detail, retryAfter,
                null, null);
    }

    public static DispatchOutcome uncertain(String errorCode, String detail) {
        return new DispatchOutcome(Status.UNCERTAIN, null, null, errorCode, detail, null, null, null);
    }

    /** The same outcome, attributed to the account that produced it. */
    public DispatchOutcome from(UUID bindingId, String providerType) {
        return new DispatchOutcome(status, externalMessageId, providerStatus, errorCode, detail,
                retryAfter, bindingId, providerType);
    }

    public Optional<Duration> retryDelay() {
        return Optional.ofNullable(retryAfter);
    }
}
