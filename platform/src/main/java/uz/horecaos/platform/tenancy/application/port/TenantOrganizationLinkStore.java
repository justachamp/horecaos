package uz.horecaos.platform.tenancy.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the platform believes about which Keycloak organization belongs to which
 * tenant (ADR 0009).
 *
 * <p>A port rather than a direct query because the authority for that belief is
 * {@code tenant.tenants.keycloak_organization_id}, which ADR 0009 deliberately
 * leaves in tenancy. IAM reads it; it does not copy it. A second copy inside
 * {@code iam} would be a second thing to reconcile, and reconciliation is the
 * problem being solved rather than one to create more of.
 */
public interface TenantOrganizationLinkStore {

    /**
     * Every tenant a drift report has to account for, oldest first.
     *
     * <p>Includes tenants with no organization id, because an active tenant that
     * was never linked is one of the findings, not an absence of one.
     */
    List<TenantOrganizationLink> tenantsToReconcile(int limit);

    /**
     * One tenant's belief about its Keycloak organization link.
     *
     * @param organizationId empty when the tenant has never been linked
     * @param expectedAlias  the deterministic alias ADR 0009 derives from the
     *                       tenant slug, which is the only alias a reconciliation
     *                       may match on
     */
    record TenantOrganizationLink(
            UUID tenantId, String expectedAlias, String tenantStatus, Optional<String> organizationId) {}
}
