package uz.horecaos.platform.integration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;

import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/** ADR 0026's activation gate is tested against the database snapshot it consumes. */
class ProviderInstallationControllerTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121601");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121602");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-26T10:00:00Z"), ZoneOffset.UTC);

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private ProviderInstallationController controller;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
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
        jdbc.sql("TRUNCATE TABLE integration.provider_capability_probes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.binding_capabilities CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.provider_environments CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        hierarchy();
        controller = new ProviderInstallationController(jdbc, fact -> { },
                () -> new AuthenticatedActor("operator", Set.of(), Map.of()), CLOCK, null);
    }

    @Test
    void activationRequiresEveryEnabledCapabilityToHaveSupportedSnapshotEvidence() {
        UUID installation = installation("sms-one");
        UUID binding = binding(installation);
        capability(binding, "SEND_SMS");
        successfulPreflight(installation, "{\"SEND_SMS\":{\"support\":\"UNSUPPORTED\"}}");

        assertThatThrownBy(() -> controller.activateBinding(TENANT, installation, binding,
                new ProviderInstallationController.ReasonRequest("ready")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Every enabled binding capability");
        assertThat(status(binding)).isEqualTo("SUSPENDED");

        successfulPreflight(installation, "{\"SEND_SMS\":{\"support\":\"SUPPORTED\"}}");
        assertThat(controller.activateBinding(TENANT, installation, binding,
                new ProviderInstallationController.ReasonRequest("verified")).getBody())
                .containsEntry("outcome", "activated");
        assertThat(status(binding)).isEqualTo("ACTIVE");
        assertThat(status(installation)).isEqualTo("ACTIVE");
    }

    @Test
    void aBindingCannotBeActivatedThroughAnotherInstallationPath() {
        UUID first = installation("sms-one");
        UUID second = installation("sms-two");
        UUID binding = binding(second);
        capability(binding, "SEND_SMS");
        successfulPreflight(first, "{\"SEND_SMS\":{\"support\":\"SUPPORTED\"}}");

        assertThatThrownBy(() -> controller.activateBinding(TENANT, first, binding,
                new ProviderInstallationController.ReasonRequest("wrong installation")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Installation or binding is not available");
        assertThat(status(binding)).isEqualTo("SUSPENDED");
    }

    private UUID installation(String code) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'NOTIFICATION', 'GENERIC_SMS', 'https://sms.example', false, 'sms.example')
                """).param("code", code).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'GENERIC_SMS', :environment,
                        'Test SMS', 'DRAFT', 'horecaos:test:provider_notification:tenant:sms')
                """).param("id", id).param("tenantId", TENANT).param("environment", code).update();
        return id;
    }

    private UUID binding(UUID installation) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings
                    (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'SUSPENDED')
                """).param("id", id).param("tenantId", TENANT).param("installationId", installation)
                .param("brandId", BRAND).update();
        return id;
    }

    private void capability(UUID binding, String code) {
        jdbc.sql("""
                INSERT INTO integration.binding_capabilities
                    (binding_id, tenant_id, capability_code, enabled, is_primary)
                VALUES (:bindingId, :tenantId, :code, true, false)
                """).param("bindingId", binding).param("tenantId", TENANT).param("code", code).update();
    }

    private void successfulPreflight(UUID installation, String snapshot) {
        jdbc.sql("""
                UPDATE integration.installations
                   SET last_connection_status = 'SUCCEEDED', capability_snapshot = cast(:snapshot AS jsonb)
                 WHERE id = :id
                """).param("id", installation).param("snapshot", snapshot).update();
    }

    private String status(UUID id) {
        return jdbc.sql("""
                SELECT status FROM integration.bindings WHERE id = :id
                """).param("id", id).query(String.class).optional()
                .orElseGet(() -> jdbc.sql("SELECT status FROM integration.installations WHERE id = :id")
                        .param("id", id).query(String.class).single());
    }

    private void hierarchy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'controller-test', 'Controller Test', 'Controller Test',
                        'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'TEST', 'test', 'Test', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }
}
