package uz.horecaos.platform.customers.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.customers.api.CustomerIdentityPolicy;

/**
 * A resolved customer session (ADR 0051).
 *
 * <p>What a presented token turned into, with no token in it. Every field came
 * off the row rather than out of the credential, which is the whole reason the
 * credential is opaque: a client that cannot read its own token cannot edit it
 * into somebody else's tenant.
 *
 * @param identityPartitionBrandId the partition the account lives in — null under
 *                                 {@code TENANT_SHARED}, the brand under
 *                                 {@code BRAND_ISOLATED}. Copied from the account
 *                                 when the session was minted, so
 *                                 {@link #covers} compares a decision that was
 *                                 made against the rule in force now
 */
public record CustomerSession(
        UUID sessionId,
        UUID tenantId,
        UUID brandId,
        UUID accountId,
        @Nullable UUID identityPartitionBrandId,
        Instant issuedAt,
        Instant expiresAt) {

    public CustomerSession {
        Objects.requireNonNull(sessionId, "A session id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(brandId, "A brand id is required");
        Objects.requireNonNull(accountId, "A customer account id is required");
        Objects.requireNonNull(expiresAt, "A session expiry is required");
    }

    /**
     * Whether this session speaks for the account a request at this tenant and
     * brand is asking about.
     *
     * <p>The partition is compared rather than the brand, and that is the identity
     * mode split made concrete. Under {@code TENANT_SHARED} both sides are null,
     * so one session reaches every brand of the tenant — which is what a shared
     * account <em>means</em>. Under {@code BRAND_ISOLATED} the tenant's brands are
     * separate businesses holding separate accounts for the same person, so a
     * session minted at one reaches only that one.
     *
     * <p>The expected side is computed from the policy in force <em>now</em>, not
     * from the one that minted the session. A governed mode change therefore stops
     * matching, and the customer signs in again into the partitioning the tenant
     * has actually chosen. The alternative — trusting the stored partition alone —
     * would keep serving the old partitioning for the lifetime of every live
     * session, which under a shift to {@code BRAND_ISOLATED} is exactly the
     * cross-brand exposure the mode was changed to end.
     */
    public boolean covers(UUID requestedTenantId, UUID requestedBrandId, CustomerIdentityPolicy modeInForce) {

        return tenantId.equals(requestedTenantId)
                && Objects.equals(identityPartitionBrandId, modeInForce.partitionFor(requestedBrandId));
    }

    /**
     * The subject this session presents to anything that only wants a stable
     * handle for the caller.
     *
     * <p>Namespaced, so it can never equal a Keycloak subject. The account id
     * rather than the session id, because the thing being scoped — an ADR 0031
     * idempotency window, chiefly — belongs to the customer and not to the handset
     * they happen to be holding: signing out and back in must not let the same key
     * replay a charge.
     */
    public String actorSubject() {
        return "customer:" + accountId;
    }
}
