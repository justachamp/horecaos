package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.tenancy.api.KeycloakOrganizationLookup;

/**
 * The one query behind {@link KeycloakOrganizationLookup} — the same
 * statement {@link JdbcStaffInvitationStore#keycloakOrganizationId} already
 * runs, exposed to callers outside {@code tenancy} instead of duplicated.
 */
@Repository
public class JdbcKeycloakOrganizationLookup implements KeycloakOrganizationLookup {

    private final JdbcClient jdbc;

    public JdbcKeycloakOrganizationLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> keycloakOrganizationId(UUID tenantId) {
        return jdbc.sql("SELECT keycloak_organization_id FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .optional();
    }
}
