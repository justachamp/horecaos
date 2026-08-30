package uz.qoida.platform.tenancy.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.qoida.platform.tenancy.application.port.TenantOrganizationLinkStore;

/**
 * Reads the authoritative tenant-to-organization link (ADR 0009).
 *
 * <p>Reaches into {@code tenant.tenants} on purpose. That column is the
 * immutable link ADR 0009 keeps in tenancy, and a drift report exists precisely
 * to compare it against the realm; holding a second copy in {@code iam} would
 * add a third state for the two to disagree with.
 *
 * <p>Archived tenants are excluded. Their organization is preserved rather than
 * deleted — ADR 0009 does not delete Keycloak objects — so an archived tenant
 * whose organization still exists is expected, not drift.
 */
@Repository
public class JdbcTenantOrganizationLinkStore implements TenantOrganizationLinkStore {

    private final JdbcClient jdbc;

    public JdbcTenantOrganizationLinkStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TenantOrganizationLink> tenantsToReconcile(int limit) {
        return jdbc.sql("""
                SELECT id, slug, status, keycloak_organization_id
                  FROM tenant.tenants
                 WHERE status IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED')
                 ORDER BY created_at
                 LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, n) -> new TenantOrganizationLink(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("status"),
                        Optional.ofNullable(rs.getString("keycloak_organization_id"))))
                .list();
    }
}
