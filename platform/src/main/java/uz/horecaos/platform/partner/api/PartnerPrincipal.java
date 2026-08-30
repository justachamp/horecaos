package uz.horecaos.platform.partner.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An authenticated aggregator (ADR 0040, ADR 0025).
 *
 * <p>ADR 0025's capability model applies unchanged; what differs is that the
 * principal is not a person. It has no Keycloak organization membership, no
 * roles a tenant administrator granted it, and no session — it presents a client
 * credential and gets back exactly the reach its ADR 0026 bindings describe.
 *
 * <p>{@link #bindingIds()} is the whole of that reach and it is derived from the
 * installation at authentication time rather than copied onto the credential.
 * A copy would be a second answer to "which branches may this partner see", and
 * the two diverge the first time a branch is unbound — leaving a revoked venue
 * readable by a token that was correctly scoped on the day it was issued.
 *
 * <p>The tenant is on the principal and is matched against the tenant in the
 * path, as ADR 0031 requires of every surface. It is never read from the path
 * alone: a partner that could name any tenant would be one path-parameter typo
 * away from another restaurant's order book.
 */
public record PartnerPrincipal(
        UUID clientRegistrationId, String clientId, UUID tenantId, UUID installationId, Set<UUID> bindingIds) {

    public PartnerPrincipal {
        Objects.requireNonNull(clientRegistrationId, "A client registration id is required");
        Objects.requireNonNull(clientId, "A client id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(installationId, "An installation id is required");
        bindingIds = Set.copyOf(bindingIds);
    }

    /**
     * Whether this partner may act on the given binding.
     *
     * <p>The single check that stops one partner enumerating another's orders,
     * and the reason every partner-facing read takes a binding rather than only
     * an order id. An order id is guessable in the sense that matters: an
     * integration bug that iterates identifiers is indistinguishable from an
     * attack, and both are refused by the same predicate.
     */
    public boolean covers(UUID bindingId) {
        return bindingId != null && bindingIds.contains(bindingId);
    }

    /** The rate-limiting identity under ADR 0033. Per partner, never per tenant. */
    public String rateLimitSubject() {
        return "partner:" + clientId;
    }
}
