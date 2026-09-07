package uz.horecaos.platform.iam.api.organizations;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Creates and reconciles Keycloak organizations (ADR 0009).
 *
 * <p>Every operation is idempotent by immutable identifier, because onboarding
 * retries and a create that timed out may already have succeeded. The one thing
 * this must never do is create a replacement organization when a stored id is
 * missing externally: that produces two identities for one tenant and orphans
 * the memberships attached to the first.
 *
 * <p><strong>Deliberately no {@code ensureOrganizationRoles}.</strong> ADR 0009's
 * port sketch names one for org-internal roles, and it is not implemented here:
 * Keycloak's Organizations Admin REST API (26.7) has no organization-scoped
 * role sub-resource — no {@code
 * /organizations/{orgId}/members/{memberId}/role-mappings} of any kind. Roles
 * in Keycloak stay realm- or client-wide, assigned per user through the
 * ordinary {@code /users/{id}/role-mappings/...} endpoints, never scoped to an
 * organization by the API itself. Implementing the method honestly would mean
 * synthesizing organization scoping ourselves — a group per organization, or a
 * user-level role mapping that is not actually organization-scoped no matter
 * what it is named — which is a real design decision this record does not
 * cover and not something to smuggle in under a method name that promises
 * more precision than the platform underneath it has. {@link
 * uz.horecaos.platform.iam.api.grants.TenantOwnerAuthorityGrantor} is the v1
 * answer instead: platform-side authority through ADR 0025's own grant model,
 * which already has tenant scoping as a first-class concept.
 */
public interface OrganizationProvisioner {

    /**
     * Creates the organization, or returns the existing one.
     *
     * @throws OrganizationDriftException when a stored id no longer resolves, or
     *         when several organizations match the alias. Both need a human.
     */
    OrganizationRef ensureOrganization(EnsureOrganization command);

    Optional<OrganizationSnapshot> getOrganization(String organizationId);

    /** Links an existing verified subject, or creates and invites one. */
    MembershipRef ensureMembership(EnsureMembership command);

    /**
     * Disables or re-enables the organization's stored {@code enabled} flag.
     *
     * <p><strong>Not an authentication control.</strong> An earlier version of
     * this javadoc claimed the opposite — that Keycloak refuses authentication
     * for every member of a disabled organization — on the strength of this
     * ADR's prose, never checked against a real realm. Verified 2026-09-08
     * against a live Keycloak 26.7.0: a member of a disabled organization
     * still completes the resource-owner password grant and receives a valid
     * token. See {@code
     * disablingAnOrganizationDoesNotByItselfBlockDirectGrantAuthentication} in
     * {@code KeycloakOrganizationIntegrationTests} for the proof. Denying a
     * suspended tenant's people access to tenant resources is, and must stay,
     * {@code TenantAccessPolicy}'s job through real {@code iam.grants}
     * capabilities — authentication success alone never authorizes a domain
     * operation.
     *
     * <p>What this method is for: keeping the stored flag consistent with
     * tenant status, so the drift reporter's {@code ORGANIZATION_DISABLED}
     * comparison has something correct to compare against and an operator
     * reading Keycloak directly sees a state that matches the tenant record.
     *
     * <p>Idempotent by design, not merely by accident: applying the same
     * enabled state twice is a no-op rather than a second write, because a
     * retried call must never fail just because the first attempt already
     * landed.
     *
     * @throws OrganizationDriftException when the organization does not
     *         resolve — the same refusal {@link #ensureOrganization} gives a
     *         vanished stored id, and for the same reason: a human, not a
     *         retry, decides what a missing organization means.
     */
    void setOrganizationEnabled(String organizationId, boolean enabled);

    record EnsureOrganization(
            UUID tenantId,
            String alias,
            String displayName,
            @Nullable String existingOrganizationId) {

        public EnsureOrganization {
            Objects.requireNonNull(tenantId, "A tenant id is required");
            Objects.requireNonNull(alias, "An alias is required");
        }
    }

    record EnsureMembership(
            String organizationId, String email, @Nullable String existingSubjectId) {

        public EnsureMembership {
            Objects.requireNonNull(organizationId, "An organization id is required");
        }
    }

    /** The immutable identifier, which is the only safe join key (ADR 0003). */
    record OrganizationRef(String organizationId, String alias, boolean created) {}

    record OrganizationSnapshot(String organizationId, String alias, String name, boolean enabled) {}

    record MembershipRef(String organizationId, String subjectId, boolean created) {}

    /**
     * Something about the external state needs a human. Deliberately not a
     * retryable failure: retrying drift produces more drift.
     */
    class OrganizationDriftException extends RuntimeException {
        public OrganizationDriftException(String message) {
            super(message);
        }
    }
}
