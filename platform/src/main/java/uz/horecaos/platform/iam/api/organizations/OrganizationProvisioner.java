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
