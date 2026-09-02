package uz.horecaos.platform.tenancy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The organization-to-tenant lookup the session-context fallback navigates by.
 * The ACTIVE filter is the assertion that matters: a suspended tenant's staff
 * must not have their capability view silently resolved against it.
 */
class JdbcTenantOrganizationDirectoryTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120501");
    private static final String ORGANIZATION = "kc-org-directory-test";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcTenantOrganizationDirectory directory;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        directory = new JdbcTenantOrganizationDirectory(jdbc);
    }

    @Test
    void resolvesAnActiveTenantByItsOrganization() {
        insertTenant(TENANT, ORGANIZATION, "ACTIVE");

        assertThat(directory.tenantIdForKeycloakOrganization(ORGANIZATION)).contains(TENANT);
    }

    @Test
    void aSuspendedTenantDoesNotResolve() {
        insertTenant(TENANT, ORGANIZATION, "SUSPENDED");

        assertThat(directory.tenantIdForKeycloakOrganization(ORGANIZATION)).isEmpty();
    }

    @Test
    void anUnknownOrganizationResolvesNothing() {
        assertThat(directory.tenantIdForKeycloakOrganization("never-linked")).isEmpty();
    }

    private void insertTenant(UUID id, String organizationId, String status) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                                            default_timezone, keycloak_organization_id, status)
                VALUES (:id, :slug, 'Org Directory Test LLC', 'Org Directory Test', 'UZS',
                        'Asia/Tashkent', :organizationId, :status)
                """)
                .param("id", id)
                .param("slug", "org-dir-" + status.toLowerCase(java.util.Locale.ROOT))
                .param("organizationId", organizationId)
                .param("status", status)
                .update();
    }
}
