package uz.horecaos.platform.iam.api.organizations;

import java.util.List;
import java.util.Optional;

/**
 * Reads Keycloak organizations (ADR 0009).
 *
 * <p>A separate contract from {@link OrganizationProvisioner} rather than a
 * super-interface of it, because the separation is the security decision. The
 * drift report runs unattended on a timer, and ADR 0009 gives it a credential
 * that cannot write: {@code horecaos-identity-reader} holds
 * {@code view-organizations} and {@code query-users} and nothing else. A single
 * interface would mean one bean, one credential, and a scheduled job holding
 * {@code manage-organizations} it never uses — and a drift report able to write
 * could quietly alter the memberships it exists to report on.
 *
 * <p>The provisioning adapter reads through this same contract with its own
 * credential, so there is one implementation of the read path and two
 * capabilities pointed at it.
 */
public interface OrganizationDirectory {

    /** By immutable id, which is the only safe join key (ADR 0003). */
    Optional<OrganizationProvisioner.OrganizationSnapshot> getOrganization(String organizationId);

    /**
     * Pre-create reconciliation only.
     *
     * <p>ADR 0003 forbids joining runtime authorization by alias. This exists
     * for the one moment before an immutable id exists, and for a drift report
     * that has to notice an organization no tenant claims.
     */
    List<OrganizationProvisioner.OrganizationSnapshot> findByAlias(String alias);
}
