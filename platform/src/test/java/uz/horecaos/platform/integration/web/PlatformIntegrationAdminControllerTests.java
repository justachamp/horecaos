package uz.horecaos.platform.integration.web;

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

class PlatformIntegrationAdminControllerTests {

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private PlatformIntegrationAdminController controller;

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
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.provider_environments CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        controller = new PlatformIntegrationAdminController(jdbc);
    }

    @Test
    void providersReturnsTheStaticCodeOwnedCatalogue() {
        var providers = controller.providers();

        assertThat(providers)
                .as("today's real, honest set: three adapters, not the wider parity inventory")
                .extracting(
                        uz.horecaos.platform.integration.api.provider.ConnectFieldCatalog.ProviderConnectDeclaration
                                ::providerType)
                .containsExactlyInAnyOrder("CLICK", "PAYME", "TELEGRAM_BOT_API");
    }

    @Test
    void installationsCrossesTenantsAndNamesEachOne() {
        UUID tenantA = tenant("cross-a", "Cross Tenant A");
        UUID tenantB = tenant("cross-b", "Cross Tenant B");
        installation(tenantA, "CLICK", "Click for A");
        installation(tenantB, "PAYME", "Payme for B");

        var page = controller.installations(null, null);

        assertThat(page.items())
                .as("a platform-scope read must see every tenant's installations, not one tenant's")
                .hasSize(2)
                .extracting(PlatformIntegrationAdminController.PlatformInstallationView::tenantSlug)
                .containsExactlyInAnyOrder("cross-a", "cross-b");
        assertThat(page.items())
                .extracting(PlatformIntegrationAdminController.PlatformInstallationView::providerType)
                .containsExactlyInAnyOrder("CLICK", "PAYME");
    }

    @Test
    void installationsPaginatesByKeyset() {
        UUID tenant = tenant("paged", "Paged Tenant");
        installation(tenant, "CLICK", "First");
        installation(tenant, "PAYME", "Second");

        var firstPage = controller.installations(null, 1);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.nextCursor()).isNotNull();

        var secondPage = controller.installations(UUID.fromString(firstPage.nextCursor()), 1);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.nextCursor())
                .as("a full page may or may not be the last one, so it still carries a cursor — "
                        + "the same 'answering maybe costs one empty request' rule Page.last documents")
                .isNotNull();

        var thirdPage = controller.installations(UUID.fromString(secondPage.nextCursor()), 1);
        assertThat(thirdPage.items()).as("the empty request that confirms the collection actually ended").isEmpty();
        assertThat(thirdPage.nextCursor()).isNull();

        assertThat(secondPage.items().getFirst().id())
                .isNotEqualTo(firstPage.items().getFirst().id());
    }

    private UUID tenant(String slug, String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, :name, :name, 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).param("name", name).update();
        return id;
    }

    private void installation(UUID tenantId, String providerType, String displayName) {
        String environmentCode = "sandbox-" + providerType;
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'PAYMENT', :providerType, 'https://sandbox.example', false, 'sandbox.example')
                """)
                .param("code", environmentCode)
                .param("providerType", providerType)
                .update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'PAYMENT', :providerType, :environmentCode,
                        :displayName, 'DRAFT', 'horecaos:test:payment:tenant:secret')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("providerType", providerType)
                .param("environmentCode", environmentCode)
                .param("displayName", displayName)
                .update();
    }
}
