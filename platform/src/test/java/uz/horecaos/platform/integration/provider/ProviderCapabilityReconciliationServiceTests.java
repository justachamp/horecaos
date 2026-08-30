package uz.horecaos.platform.integration.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.provider.ProviderCapabilityCatalog;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.support.TestDatabase;

/** ADR 0026's non-POS capability evidence and activation-precondition facts. */
class ProviderCapabilityReconciliationServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-26T10:00:00Z");
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121501");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121502");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private ProviderCapabilityReconciliationService reconciliation;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
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
        jdbc.sql("TRUNCATE TABLE integration.provider_capability_probes CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.binding_capabilities CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.provider_environments CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        reconciliation = new ProviderCapabilityReconciliationService(
                jdbc,
                List.of(new NotificationCatalog()),
                new AvailableSecretResolver(),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        hierarchy();
    }

    @Test
    void itRecordsSecretAndAdapterEvidenceAndRejectsUndeclaredCapabilitiesInTheSnapshot() {
        UUID installation = installation();
        UUID binding = binding(installation);
        capability(binding, "SEND_SMS");
        capability(binding, "SEND_EMAIL");

        var result = reconciliation.reconcile(TENANT, installation);

        assertThat(result.connectionStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.capabilities())
                .containsEntry("SEND_SMS", "SUPPORTED")
                .containsEntry("SEND_EMAIL", "UNSUPPORTED");

        String snapshot =
                jdbc.sql("""
                SELECT capability_snapshot::text FROM integration.installations WHERE id = :id
                """).param("id", installation).query(String.class).single();
        assertThat(snapshot)
                .contains("SEND_SMS", "SUPPORTED", "SEND_EMAIL", "UNSUPPORTED")
                .doesNotContain("configured-secret");

        assertThat(jdbc.sql("""
                SELECT capability_code || ':' || probe_status
                  FROM integration.provider_capability_probes
                 WHERE installation_id = :id
                 ORDER BY capability_code
                """).param("id", installation).query(String.class).list())
                .containsExactly("CONNECTION:SUPPORTED", "SEND_EMAIL:UNSUPPORTED", "SEND_SMS:SUPPORTED");

        assertThat(jdbc.sql("""
                SELECT verified_at IS NOT NULL AND capability_version = 'notification/GENERIC_SMS/v1'
                  FROM integration.binding_capabilities
                 WHERE binding_id = :id AND capability_code = 'SEND_SMS'
                """).param("id", binding).query(Boolean.class).single())
                .isTrue();
    }

    @Test
    void anUnavailableSecretFailsTheConnectionPreflightWithoutClaimingAProviderCapability() {
        UUID installation = installation();
        UUID binding = binding(installation);
        capability(binding, "SEND_SMS");
        reconciliation = new ProviderCapabilityReconciliationService(
                jdbc,
                List.of(new NotificationCatalog()),
                new MissingSecretResolver(),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = reconciliation.reconcile(TENANT, installation);

        assertThat(result.connectionStatus()).isEqualTo("FAILED");
        assertThat(result.capabilities()).containsEntry("SEND_SMS", "UNSUPPORTED");
        assertThat(jdbc.sql("""
                SELECT last_connection_evidence FROM integration.installations WHERE id = :id
                """).param("id", installation).query(String.class).single())
                .isEqualTo("The configured secret reference could not be resolved");
    }

    private UUID installation() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('sms-test', 'NOTIFICATION', 'GENERIC_SMS',
                        'https://sms.example', false, 'sms.example')
                """).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'GENERIC_SMS', 'sms-test',
                        'Test SMS', 'DRAFT', 'horecaos:test:provider_notification:tenant:sms')
                """).param("id", id).param("tenantId", TENANT).update();
        return id;
    }

    private UUID binding(UUID installation) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings
                    (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'SUSPENDED')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("installationId", installation)
                .param("brandId", BRAND)
                .update();
        return id;
    }

    private void capability(UUID binding, String code) {
        jdbc.sql("""
                INSERT INTO integration.binding_capabilities
                    (binding_id, tenant_id, capability_code, enabled, is_primary)
                VALUES (:bindingId, :tenantId, :code, true, false)
                """)
                .param("bindingId", binding)
                .param("tenantId", TENANT)
                .param("code", code)
                .update();
    }

    private void hierarchy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'provider-test', 'Provider Test', 'Provider Test',
                        'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'TEST', 'test', 'Test', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    private static final class NotificationCatalog implements ProviderCapabilityCatalog {
        @Override
        public ProviderCategory category() {
            return ProviderCategory.NOTIFICATION;
        }

        @Override
        public Optional<Declaration> declarationFor(String providerType) {
            return "GENERIC_SMS".equals(providerType)
                    ? Optional.of(new Declaration(Set.of("SEND_SMS"), "notification/GENERIC_SMS/v1"))
                    : Optional.empty();
        }
    }

    private static final class AvailableSecretResolver implements SecretResolver {
        @Override
        public SecretValue resolve(SecretReference reference) {
            return SecretValue.of("configured-secret");
        }

        @Override
        public SecretValue resolveFresh(SecretReference reference) {
            return resolve(reference);
        }
    }

    private static final class MissingSecretResolver implements SecretResolver {
        @Override
        public SecretValue resolve(SecretReference reference) {
            throw new SecretNotFoundException(reference);
        }

        @Override
        public SecretValue resolveFresh(SecretReference reference) {
            return resolve(reference);
        }
    }
}
