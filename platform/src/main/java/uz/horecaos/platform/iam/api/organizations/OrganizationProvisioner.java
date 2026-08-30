package uz.horecaos.platform.iam.api.organizations;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates and reconciles Keycloak organizations (ADR 0009).
 *
 * <p>Every operation is idempotent by immutable identifier, because onboarding
 * retries and a create that timed out may already have succeeded. The one thing
 * this must never do is create a replacement organization when a stored id is
 * missing externally: that produces two identities for one tenant and orphans
 * the memberships attached to the first.
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

    record EnsureOrganization(UUID tenantId, String alias, String displayName, String existingOrganizationId) {

        public EnsureOrganization {
            Objects.requireNonNull(tenantId, "A tenant id is required");
            Objects.requireNonNull(alias, "An alias is required");
        }
    }

    record EnsureMembership(String organizationId, String email, String existingSubjectId) {

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
