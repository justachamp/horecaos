package uz.horecaos.platform.iam.api.grants;

import java.util.UUID;

/**
 * The missing half of ADR 0009: platform-side authority for a linked tenant
 * owner (ADR 0025).
 *
 * <p>Keycloak organization membership (ADR 0009) proves who the owner is;
 * this is what lets them actually use the control plane once linked. Without
 * it, {@code TENANT_OWNER_LINK_OR_INVITE} completing successfully still left
 * the owner unable to do anything — organization membership alone authorizes
 * nothing under ADR 0025's capability model.
 *
 * <p>Deliberately one fixed operation rather than a general grant port. The
 * caller — the onboarding workflow — never chooses a role or a scope; it
 * always asks for exactly {@code tenant-owner} at exactly the tenant it just
 * linked. A general "grant anything to anyone" port reachable from a
 * background workflow would be the privilege-escalation surface ADR 0025's
 * capability checks exist to close.
 */
public interface TenantOwnerAuthorityGrantor {

    /**
     * Idempotently grants {@code subjectId} tenant-owner authority at
     * {@code tenantId}.
     *
     * <p>Safe to call again for the same {@code tenantId}/{@code subjectId}: a
     * retried onboarding step (ADR 0008) must never fail because the grant it
     * is trying to make already exists, and must never mint a second one.
     *
     * @param reason recorded on the ADR 0027 audit fact this produces
     */
    void grantTenantOwner(UUID tenantId, String subjectId, String reason);
}
