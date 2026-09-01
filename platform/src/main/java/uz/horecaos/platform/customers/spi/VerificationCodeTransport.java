package uz.horecaos.platform.customers.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The seam a one-time code leaves the platform through (ADR 0015, ADR 0020,
 * ADR 0007).
 *
 * <p>Customers never opens a socket, for the same reason notifications does not:
 * ADR 0007 keeps every provider call behind an adapter that owns timeouts,
 * bounded redelivery and credential resolution, and keeps those concerns out of a
 * domain module. The domain names the send; somebody else performs it.
 *
 * <p><strong>There is deliberately no default implementation.</strong> A stand-in
 * that accepted the message and dropped it would make an unwired deployment look
 * exactly like a working one — every customer would be issued a challenge whose
 * code never arrived, and the first person to notice would be a customer. A
 * stand-in that logged the code instead would be worse: ADR 0029 and ADR 0028
 * both forbid a one-time code reaching a log, and the log is the one place it
 * would then live for the retention period. So when no adapter is present there
 * is no bean, {@code CustomerVerificationService} answers that verification is
 * unavailable, and {@code VerificationTransportGuard} refuses to start a
 * non-local profile at all.
 *
 * <p>Three outcomes rather than the four on {@code DispatchOutcome}. The missing
 * one is "uncertain", and it is missing because the reconciliation it exists to
 * force buys nothing here: a verification message repeated to the same
 * destination carries the same code from the same single-use challenge, so a
 * duplicate is not a second effect the way a duplicate order confirmation is.
 * What it would cost is a second SMS, which the per-destination issuance limits
 * already bound.
 */
public interface VerificationCodeTransport {

    /**
     * Sends one code. Never called inside a business transaction.
     *
     * <p>Implementations must not throw for a provider failure; every failure
     * comes back as an {@link Outcome}, because whether the challenge should be
     * kept or torn down is a decision the caller makes from that answer.
     */
    Outcome send(VerificationMessage message);

    /**
     * One code, addressed, for the length of one call.
     *
     * <p>Neither {@code destination} nor {@code code} is written down on either
     * side of this port. The challenge row holds the destination encrypted under
     * ADR 0029 and a keyed hash of the code, and nothing holds the code itself.
     *
     * @param destination E.164, already normalized and validated by the caller
     * @param validFor    passed rather than left to the adapter to invent, so the
     *                    minutes the customer reads in the message are the minutes
     *                    the row will actually honour
     * @param locale      the customer's language when one is known, null before
     *                    they have an account to have chosen one
     */
    record VerificationMessage(
            UUID tenantId,
            UUID brandId,
            UUID challengeId,
            ContactChannel channel,
            String destination,
            String code,
            Duration validFor,
            @Nullable String locale,
            Instant issuedAt) {

        public VerificationMessage {
            Objects.requireNonNull(tenantId, "A tenant id is required");
            Objects.requireNonNull(brandId, "A brand id is required");
            Objects.requireNonNull(challengeId, "A challenge id is required");
            Objects.requireNonNull(channel, "A channel is required");
            Objects.requireNonNull(validFor, "A validity period is required");
            if (destination == null || destination.isBlank()) {
                throw new IllegalArgumentException("A message without a destination cannot be sent");
            }
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("A verification message without a code is not one");
            }
        }

        /**
         * Deliberately overridden.
         *
         * <p>A record's generated {@code toString} prints every component, so one
         * incautious log line or one {@code String.valueOf} in an exception
         * message would put a customer's phone number and a live one-time code
         * into a log file that ADR 0029 says must never hold either.
         */
        @Override
        public String toString() {
            return "VerificationMessage[challengeId=%s, channel=%s]".formatted(challengeId, channel);
        }
    }

    /** How a code can travel. SMS is the only one this market needs first. */
    enum ContactChannel {
        SMS
    }

    /**
     * What came back.
     *
     * <p>ADR 0063 widens this with which delivery provider actually carried the
     * code, and what that carriage cost — recorded by {@code CustomerVerificationService}
     * on the challenge row the moment it sees {@code ACCEPTED}, the way SMS
     * delivery is now recorded too. All four are null for every {@code REFUSED}
     * or {@code UNAVAILABLE} outcome, and for an {@code ACCEPTED} one from a
     * transport that predates this widening — nothing requires a caller to fill
     * them in, and {@link #accepted()} still exists for exactly that caller.
     *
     * @param reasonCode a provider or adapter code, safe to log and to return as
     *                   an ADR 0031 problem property. Never a provider message
     *                   body: gateways are known to echo the request — which here
     *                   is the number and the code — back inside an error
     * @param deliveryChannel which provider actually sent it — {@code "SMS"} or
     *                   {@code "TELEGRAM_GATEWAY"} — or null when the outcome is
     *                   not {@code ACCEPTED}, or the caller does not track this
     * @param providerMessageId the provider's own opaque reference for this send,
     *                   or null when the provider gave none
     * @param costMinor  integer minor units, never a float (CLAUDE.md), or null
     *                   when the provider reported no cost
     * @param costCurrencyCode ISO 4217, paired with {@code costMinor} — both null
     *                   or both present
     */
    record Outcome(
            Status status,
            @Nullable String reasonCode,
            @Nullable String deliveryChannel,
            @Nullable String providerMessageId,
            @Nullable Long costMinor,
            @Nullable String costCurrencyCode) {

        public Outcome(Status status, @Nullable String reasonCode) {
            this(status, reasonCode, null, null, null, null);
        }

        public enum Status {

            /** The gateway took it. */
            ACCEPTED,

            /** Refused on business grounds. Sending it again produces the same answer. */
            REFUSED,

            /** No adapter, no binding, or the gateway could not be reached. */
            UNAVAILABLE
        }

        public static Outcome accepted() {
            return new Outcome(Status.ACCEPTED, null);
        }

        /** {@link #accepted()}, naming which provider carried it and at what cost (ADR 0063). */
        public static Outcome accepted(
                String deliveryChannel,
                @Nullable String providerMessageId,
                @Nullable Long costMinor,
                @Nullable String costCurrencyCode) {
            return new Outcome(Status.ACCEPTED, null, deliveryChannel, providerMessageId, costMinor, costCurrencyCode);
        }

        public static Outcome refused(@Nullable String reasonCode) {
            return new Outcome(Status.REFUSED, reasonCode);
        }

        public static Outcome unavailable(@Nullable String reasonCode) {
            return new Outcome(Status.UNAVAILABLE, reasonCode);
        }
    }
}
