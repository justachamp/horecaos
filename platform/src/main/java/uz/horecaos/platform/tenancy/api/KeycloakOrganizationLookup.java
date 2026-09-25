package uz.horecaos.platform.tenancy.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The one question outside {@code tenancy} that needs a tenant's Keycloak
 * organization id: "which organization would a new account for this tenant
 * be linked into" (ADR 0009).
 *
 * <p>A read-only sibling of {@link
 * uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStaffInvitationStore#keycloakOrganizationId},
 * exposed here so a module outside {@code tenancy} that provisions its own
 * kind of account — {@code courier}'s in-house roster is the first caller —
 * can call {@code OrganizationProvisioner.ensureMembership} without a second,
 * module-local copy of {@code SELECT keycloak_organization_id FROM
 * tenant.tenants}. Kept to exactly this one method rather than exposing
 * {@code Tenant} itself: a provisioning caller needs the join key and nothing
 * else about the tenant row.
 */
public interface KeycloakOrganizationLookup {

    /**
     * @return empty when the tenant has no organization yet (onboarding not
     *         yet at that step) or the tenant id does not resolve at all —
     *         the same "nothing to link into" answer either way, since a
     *         caller has the identical remedy for both: it cannot provision
     *         an account for this tenant right now.
     */
    Optional<String> keycloakOrganizationId(UUID tenantId);
}
