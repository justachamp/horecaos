package uz.horecaos.platform.customers.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolving an authenticated principal to the account that owns their orders
 * (ADR 0015, consumed by ADR 0019).
 *
 * <p>A lookup, never a creation. A module placing an order must not be able to
 * bring a customer account into existence as a side effect: account creation is
 * governed by the tenant's identity policy and belongs to the sign-in path.
 *
 * <p>This exists so ordering never accepts a customer account id from a client.
 * A storefront that could name any account could read and place orders against
 * somebody else's.
 */
public interface CustomerDirectory {

    /**
     * @param issuer  the token issuer, part of the identity along with the subject
     *                — two realms can mint the same subject string
     * @return empty when this principal has no account for this tenant and brand,
     *         which is an ordinary answer for a customer who has never ordered
     */
    Optional<CustomerAccountRef> findAccount(UUID tenantId, UUID brandId, String issuer,
            String subject);
}
