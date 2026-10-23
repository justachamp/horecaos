package uz.horecaos.platform.courier.application.port;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The seam a courier's first-sign-in link leaves the platform through (ADR 0178, ADR 0146).
 *
 * <p>The courier module never opens a socket: sending a text is a provider call, which ADR 0007 keeps
 * behind an adapter that owns timeouts, credentials and the gateway account's cleared purposes. ADR 0146
 * clears a gateway account for verification codes and order messages first and refuses courier traffic
 * until the owner answers in writing, so the real adapter for this port is the {@code COURIER} purpose of
 * that record, and it is the owner's to connect (see the runbook).
 *
 * <p><strong>There is deliberately no default implementation,</strong> for the reason {@code
 * VerificationCodeTransport} gives: a stand-in that accepted the message and dropped it would make an
 * unwired deployment look like a working one, and every invited courier would wait for a text that is not
 * coming. With no bean the relay leaves the invitation queued with a stable error code and the console says
 * the SMS gateway is not connected. Only the {@code local} profile has a fake.
 *
 * <p>Implementations must not throw for a provider failure: every failure comes back as an {@link Outcome},
 * because whether to retry or to give up is the relay's decision.
 */
public interface CourierAccessMessenger {

    /** Sends one link. Never called inside a database transaction. */
    Outcome send(AccessMessage message);

    /**
     * One link, addressed, for the length of one call.
     *
     * @param destination the courier's phone number, resolved for this call only and kept nowhere (ADR 0029)
     * @param link        the whole link, token included; it is a bearer credential and is never logged
     * @param kind        {@code INVITE} or {@code RESET}
     * @param locale      the language the text is in
     * @param text        the words to send, already in {@code locale} and already carrying {@code link}
     */
    record AccessMessage(
            UUID tenantId,
            UUID invitationId,
            String kind,
            String destination,
            String link,
            String locale,
            String text,
            Duration validFor) {

        public AccessMessage {
            Objects.requireNonNull(tenantId, "A tenant id is required");
            Objects.requireNonNull(invitationId, "An invitation id is required");
            Objects.requireNonNull(validFor, "A validity period is required");
            if (destination.isBlank() || link.isBlank() || text.isBlank()) {
                throw new IllegalArgumentException("A message needs a destination, a link and words");
            }
        }

        /** Holds a phone number and a credential; a record prints its components, so it prints neither. */
        @Override
        public String toString() {
            return "AccessMessage[invitationId=%s, kind=%s]".formatted(invitationId, kind);
        }
    }

    /**
     * @param reasonCode a stable code safe to store and to show an operator; never a provider body, which
     *                   gateways are known to echo the number and the text back inside
     */
    record Outcome(Status status, @Nullable String reasonCode) {

        public enum Status {

            /** The gateway took it. */
            ACCEPTED,

            /** Refused on business grounds (an unpermitted purpose, a number it will not carry). Asking again gives the same answer. */
            REFUSED,

            /** The gateway could not be reached, or no sender is configured. Worth asking again. */
            UNAVAILABLE
        }

        public static Outcome accepted() {
            return new Outcome(Status.ACCEPTED, null);
        }

        public static Outcome refused(String reasonCode) {
            return new Outcome(Status.REFUSED, reasonCode);
        }

        public static Outcome unavailable(String reasonCode) {
            return new Outcome(Status.UNAVAILABLE, reasonCode);
        }
    }
}
