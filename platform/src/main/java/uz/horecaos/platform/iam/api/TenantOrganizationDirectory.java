package uz.horecaos.platform.iam.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a Keycloak organization to the tenant that owns it.
 *
 * <p>The authority is {@code tenant.tenants.keycloak_organization_id}, which
 * ADR 0009 deliberately keeps in tenancy. The port is DEFINED here in iam —
 * the consumer — and implemented by tenancy (whose dependency on iam already
 * exists), because the reverse import closed the audit → iam → tenancy cycle
 * ModularArchitectureTests refuses. IAM reads the belief through this port
 * rather than copying it, for the same reason
 * {@code TenantOrganizationLinkStore}'s own doc gives: a second copy is a
 * second thing to reconcile.
 *
 * <p>The first caller is the session-context endpoint: a staff token carries
 * the signed organization claim (ADR 0003), and a frontend that has not chosen
 * a tenant explicitly needs the platform to resolve "which tenant is this
 * person's organization" — without this, a tenant owner signing in on the
 * staff apps saw zero capabilities and an empty navigation, because the
 * capability view was computed at platform scope only.
 */
public interface TenantOrganizationDirectory {

    /** The ACTIVE tenant linked to this Keycloak organization id, if any. */
    Optional<UUID> tenantIdForKeycloakOrganization(String keycloakOrganizationId);
}
