package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.iam.api.TenantOrganizationDirectory;

/**
 * The one query behind {@link TenantOrganizationDirectory}: the ACTIVE tenant
 * whose {@code keycloak_organization_id} matches. Backed by
 * {@code uq_tenants_keycloak_organization}, so at most one row can answer.
 */
@Repository
public class JdbcTenantOrganizationDirectory implements TenantOrganizationDirectory {

    private final JdbcClient jdbc;

    public JdbcTenantOrganizationDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> tenantIdForKeycloakOrganization(String keycloakOrganizationId) {
        return jdbc.sql("""
                SELECT id
                  FROM tenant.tenants
                 WHERE keycloak_organization_id = :organizationId
                   AND status = 'ACTIVE'
                """)
                .param("organizationId", keycloakOrganizationId)
                .query(UUID.class)
                .optional();
    }
}
