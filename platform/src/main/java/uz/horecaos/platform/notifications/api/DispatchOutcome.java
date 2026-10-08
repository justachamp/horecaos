package uz.horecaos.platform.notifications.api;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
 * @param normalizedStatus the provider's word mapped onto ADR 0020's ladder by
 *                         the adapter that understands it (ADR 0146), or null
 *                         for a gateway whose adapter leaves that to the
 *                         dispatcher's own generic reading
 * @param providerSegments how many segments the provider says it billed, null
 *                         when it did not say (ADR 0146 Decision 7)
 * @param hardBounce whether the provider says the receiver can never be reached
 *                   (blacklisted, unroutable): ADR 0146 Decision 6 turns that,
 *                   and only that, into an ADR 0044 suppression
 */
public record DispatchOutcome(
        Status status,
        @Nullable String externalMessageId,
        @Nullable String providerStatus,
        @Nullable String errorCode,
        @Nullable String detail,
        @Nullable Duration retryAfter,
        @Nullable UUID providerBindingId,
        @Nullable String providerType,
        @Nullable String normalizedStatus,
        @Nullable Integer providerSegments,
        boolean hardBounce) {

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

    public static DispatchOutcome accepted(@Nullable String externalMessageId, @Nullable String providerStatus) {
        return new DispatchOutcome(
                Status.ACCEPTED, externalMessageId, providerStatus, null, null, null, null, null, null, null, false);
    }

    public static DispatchOutcome rejected(@Nullable String errorCode, @Nullable String detail) {
        return new DispatchOutcome(Status.REJECTED, null, null, errorCode, detail, null, null, null, null, null, false);
    }

    public static DispatchOutcome retryable(
            @Nullable String errorCode, @Nullable String detail, @Nullable Duration retryAfter) {
        return new DispatchOutcome(
                Status.RETRYABLE, null, null, errorCode, detail, retryAfter, null, null, null, null, false);
    }

    public static DispatchOutcome uncertain(@Nullable String errorCode, @Nullable String detail) {
        return new DispatchOutcome(
                Status.UNCERTAIN, null, null, errorCode, detail, null, null, null, null, null, false);
    }

    /** The same outcome, attributed to the account that produced it. */
    public DispatchOutcome from(UUID bindingId, @Nullable String providerType) {
        return new DispatchOutcome(
                status,
                externalMessageId,
                providerStatus,
                errorCode,
                detail,
                retryAfter,
                bindingId,
                providerType,
                normalizedStatus,
                providerSegments,
                hardBounce);
    }

    /**
     * The same accepted outcome with what the adapter understood of it (ADR 0146):
     * its own normalisation of the provider's word, the segments billed, and
     * whether the receiver is unreachable for good.
     */
    public DispatchOutcome understood(
            @Nullable String normalizedStatus, @Nullable Integer providerSegments, boolean hardBounce) {
        return new DispatchOutcome(
                status,
                externalMessageId,
                providerStatus,
                errorCode,
                detail,
                retryAfter,
                providerBindingId,
                providerType,
                normalizedStatus,
                providerSegments,
                hardBounce);
    }

    public Optional<Duration> retryDelay() {
        return Optional.ofNullable(retryAfter);
    }
}
