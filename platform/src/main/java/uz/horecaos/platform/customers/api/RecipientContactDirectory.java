package uz.horecaos.platform.customers.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR 0020's {@code ResolveRecipientValue}, implemented against ADR 0015.
 *
 * <p>This is the only way a phone number or an email address reaches the send
 * path, and it exists so that no other module ever stores one. ADR 0020 rejected
 * keeping a second encrypted copy in {@code notifications}: it would double the
 * blast radius of a key compromise and create a second thing to rotate, expire,
 * and erase.
 *
 * <p>The split between the two methods is the point. {@link #primaryContact}
 * answers "can this customer be reached on this channel?" with an id, a hash and
 * a verification state — everything eligibility needs and nothing personal.
 * {@link #resolveValue} returns the value itself, and is called once, immediately
 * before rendering, by code that does not write it down.
 */
public interface RecipientContactDirectory {

    /**
     * The contact this customer should be reached on, as a reference.
     *
     * <p>Returns the primary contact of the requested kind, or the oldest if none
     * is marked primary. Deliberately singular where
     * {@code CustomerProfileService.findAccountsByContact} is plural: that lookup
     * goes value to account and must not collapse a shared household number,
     * while this one goes account to value and there is only one number to text.
     */
    Optional<ContactEndpoint> primaryContact(UUID tenantId, UUID accountId, ContactMethod method);

    /**
     * The plaintext value, for one send.
     *
     * @param purpose why it was revealed, recorded as an ADR 0027 fact. A delivery
     *                path revealing ten thousand numbers in an hour is a different
     *                event from an agent opening one customer, and this parameter
     *                is what tells them apart
     * @return empty when the contact point no longer exists, which is an ordinary
     *         answer for a customer who removed their number between the intent
     *         and the send
     */
    Optional<String> resolveValue(UUID tenantId, UUID contactPointId, String purpose);

    /** The customer's chosen language, when they have chosen one. */
    Optional<String> preferredLocale(UUID tenantId, UUID accountId);

    /** The kinds of contact a message can be addressed to. */
    enum ContactMethod {
        PHONE,
        EMAIL
    }

    /**
     * A reachable contact, described without revealing it.
     *
     * @param normalizedHash the ADR 0029 keyed lookup hash, so an operator can ask
     *                       "was anything sent to this number?" without the number
     *                       being stored outside {@code customer.contact_points}
     */
    record ContactEndpoint(UUID contactPointId, ContactMethod method, String normalizedHash,
            String verificationStatus) {

        public ContactEndpoint {
            Objects.requireNonNull(contactPointId, "A contact point id is required");
            Objects.requireNonNull(method, "A contact method is required");
            Objects.requireNonNull(normalizedHash, "A lookup hash is required");
        }

        public boolean isVerified() {
            return "VERIFIED".equals(verificationStatus);
        }
    }
}
