package uz.horecaos.platform.marketing.api;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Whether the platform may address a courier by telephone, and how it does (ADR 0146 Decision 8,
 * ADR 0182 D15, gap-map row 6.4b).
 *
 * <p>A courier is not a customer and the platform keeps no number for one: {@code
 * fulfillment.couriers} holds an IAM subject, a display reference and an encrypted name, and the
 * only telephone on its own file is an emergency contact's, a third party who never agreed to be in
 * this system. But a courier signs in with a number. They are created phone-first (ADR 0116), the
 * number is their identity-provider username and {@code phone} attribute, and it is that number a
 * dispatcher's SMS goes to. So the question this port answers is not custody, it is purpose: may the
 * login number be used to tell a courier about a shift change? That is the owner's answer to give
 * (ADR 0182 open input), which is why {@link #isWired} is a deployment fact and not a code path.
 *
 * <p><strong>The number is read, used and forgotten.</strong> {@link #addressable} says which couriers
 * have a number and returns no number. {@link #number} returns one courier's number for the length
 * of one send, with a recorded purpose, and nothing here stores it: not on the broadcast, not on the
 * ledger, not on the endpoint the message is addressed to (ADR 0029). A caller that logs, stores or
 * emits one is wrong.
 */
public interface CourierContactSource {

    /** True only when a courier's own number may be used for a dispatcher's message and can be asked for. */
    boolean isWired();

    /**
     * Which of these couriers have a number the identity provider can give, in one purpose-recorded
     * read, without returning any number. A courier with none (no such account, no phone, or one
     * that is not a number this platform sends to) is absent.
     *
     * @throws UnavailableException when the identity provider could not be asked, so a send fails
     *         visibly and a fleet is never recorded as having no number because a server was down
     */
    default Set<UUID> addressable(UUID tenantId, Collection<UUID> courierIds, String purpose) {
        return Set.of();
    }

    /**
     * One courier's number, for one message, or empty when they have none. The caller holds it for
     * the length of one provider call.
     *
     * @throws UnavailableException when the identity provider could not be asked
     */
    default Optional<String> number(UUID tenantId, UUID courierId, String purpose) {
        return Optional.empty();
    }

    /** The identity provider could not be asked; nothing is known about any courier. */
    final class UnavailableException extends RuntimeException {

        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
