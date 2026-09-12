package uz.horecaos.platform.partner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.partnerclients.PartnerClientProvisioner;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.iam.api.secrets.SecretWriter;
import uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0040, ADR 0106 (gap-map row 10.8d): issuing, rotating and revoking a
 * partner API credential over a real Keycloak client — proven against a fake
 * provisioner, since no live Keycloak realm is available to a unit test, but
 * against the actual database schema (V0038's constraints, V0252's new
 * column) rather than a mock of it.
 */
class PartnerApiClientServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac122001");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac122002");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac122003");
    private static final Instant NOW = Instant.parse("2026-09-12T09:00:00Z");
    private static final ActorRef OPERATOR = ActorRef.user("operator-1", "Operator One");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcPartnerStore store;
    private FakeProvisioner provisioner;
    private PartnerApiClientService service;

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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE partner.api_clients CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedHierarchy();

        store = new JdbcPartnerStore(jdbc);
        provisioner = new FakeProvisioner();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new PartnerApiClientService(
                store,
                provisioner,
                new SecretIngressGateway(new InMemorySecretWriter(), "test"),
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                clock);
    }

    @Test
    void issueCreatesAnActiveClientAndReturnsTheSecretExactlyOnce() {
        UUID installation = marketplaceInstallation(TENANT);

        PartnerApiClientService.IssuedSecret issued =
                service.issue(TENANT, installation, "Uzum Tezkor", OPERATOR, "connecting Uzum");

        assertThat(issued.secretValue()).isEqualTo(provisioner.lastSecret);
        assertThat(issued.secretExpiresAt()).isEqualTo(NOW.plus(PartnerApiClientService.SECRET_LIFETIME));

        var rows = store.listClients(TENANT, installation);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().status()).isEqualTo("ACTIVE");
        assertThat(rows.getFirst().secretReference())
                .as("a reference, never the value")
                .isNotEqualTo(issued.secretValue())
                .isNotBlank();
    }

    @Test
    void issueRefusesAnInstallationThatIsNotMarketplace() {
        UUID posInstallation = nonMarketplaceInstallation(TENANT);

        assertThatThrownBy(() -> service.issue(TENANT, posInstallation, "Not a marketplace", OPERATOR, "reason"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThat(provisioner.createCalls.get())
                .as("Keycloak is never called for a refusal the database could have told us before")
                .isZero();
    }

    @Test
    void issueRefusesASecondLiveClientOnTheSameInstallation() {
        UUID installation = marketplaceInstallation(TENANT);
        service.issue(TENANT, installation, "First client", OPERATOR, "first");

        assertThatThrownBy(() -> service.issue(TENANT, installation, "Second client", OPERATOR, "second"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(store.listClients(TENANT, installation)).hasSize(1);
    }

    @Test
    void rotateReplacesTheSecretAndAdvancesTheVersion() {
        UUID installation = marketplaceInstallation(TENANT);
        PartnerApiClientService.IssuedSecret issued =
                service.issue(TENANT, installation, "Uzum Tezkor", OPERATOR, "connecting");
        String firstSecret = issued.secretValue();

        PartnerApiClientService.IssuedSecret rotated =
                service.rotate(TENANT, issued.id(), issued.version(), OPERATOR, "scheduled rotation");

        assertThat(rotated.secretValue()).isNotEqualTo(firstSecret);
        assertThat(rotated.version()).isEqualTo(issued.version() + 1);
        assertThat(provisioner.regenerateCalls.get())
                .as("once at issue, once at rotate")
                .isEqualTo(2);
    }

    @Test
    void rotateRefusesAStaleVersion() {
        UUID installation = marketplaceInstallation(TENANT);
        PartnerApiClientService.IssuedSecret issued =
                service.issue(TENANT, installation, "Uzum Tezkor", OPERATOR, "connecting");
        service.rotate(TENANT, issued.id(), issued.version(), OPERATOR, "first rotation");

        assertThatThrownBy(() -> service.rotate(TENANT, issued.id(), issued.version(), OPERATOR, "stale retry"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void rotateRefusesAnotherTenantsClient() {
        UUID installation = marketplaceInstallation(TENANT);
        PartnerApiClientService.IssuedSecret issued =
                service.issue(TENANT, installation, "Uzum Tezkor", OPERATOR, "connecting");

        assertThatThrownBy(() -> service.rotate(OTHER_TENANT, issued.id(), issued.version(), OPERATOR, "not my client"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void revokeDisablesTheKeycloakClientAndRetiresTheRow() {
        UUID installation = marketplaceInstallation(TENANT);
        PartnerApiClientService.IssuedSecret issued =
                service.issue(TENANT, installation, "Uzum Tezkor", OPERATOR, "connecting");

        boolean revoked = service.revoke(TENANT, issued.id(), issued.version(), OPERATOR, "aggregator offboarded");

        assertThat(revoked).isTrue();
        assertThat(provisioner.disabledRefs).containsExactly(provisioner.lastKeycloakRef);
        assertThat(store.listClients(TENANT, installation).getFirst().status()).isEqualTo("RETIRED");
    }

    @Test
    void revokeIsIdempotentToARetryAfterItAlreadyApplied() {
        UUID installation = marketplaceInstallation(TENANT);
        PartnerApiClientService.IssuedSecret issued =
                service.issue(TENANT, installation, "Uzum Tezkor", OPERATOR, "connecting");
        service.revoke(TENANT, issued.id(), issued.version(), OPERATOR, "first revoke");

        boolean secondAttempt = service.revoke(TENANT, issued.id(), issued.version(), OPERATOR, "retry");

        assertThat(secondAttempt)
                .as("the version has already advanced past what this retry expects")
                .isFalse();
    }

    private UUID marketplaceInstallation(UUID tenantId) {
        return installation(tenantId, "MARKETPLACE", "UZUM_TEZKOR");
    }

    private UUID nonMarketplaceInstallation(UUID tenantId) {
        return installation(tenantId, "POS", "clopos");
    }

    private UUID installation(UUID tenantId, String category, String providerType) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, :category, :providerType, 'https://example.test', false, '')
                ON CONFLICT (code) DO NOTHING
                """)
                .param("code", "test-" + providerType)
                .param("category", category)
                .param("providerType", providerType)
                .update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :tenantId, :category, :providerType, :env, :name, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("category", category)
                .param("providerType", providerType)
                .param("env", "test-" + providerType)
                .param("name", "Test " + providerType)
                .update();
        return id;
    }

    private void seedHierarchy() {
        for (UUID tenantId : new UUID[] {TENANT, OTHER_TENANT}) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants
                        (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                    VALUES (:id, :slug, :name, :name, 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", tenantId)
                    .param("slug", "partner-client-" + tenantId)
                    .param("name", "Partner Client Test " + tenantId)
                    .update();
        }
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'TEST', 'test', 'Test', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    /** A fake Keycloak, in memory: no HTTP, deterministic secrets, and a call count. */
    private static final class FakeProvisioner implements PartnerClientProvisioner {

        private final AtomicInteger createCalls = new AtomicInteger();
        private final AtomicInteger regenerateCalls = new AtomicInteger();
        private final java.util.List<String> disabledRefs = new java.util.ArrayList<>();
        private String lastSecret = "";
        private String lastKeycloakRef = "";

        @Override
        public ProvisionedClient create(String clientLabel) {
            createCalls.incrementAndGet();
            String ref = "kc-ref-" + UUID.randomUUID();
            lastKeycloakRef = ref;
            return new ProvisionedClient(ref, "partner-" + UUID.randomUUID());
        }

        @Override
        public String regenerateSecret(String keycloakClientRef) {
            regenerateCalls.incrementAndGet();
            lastSecret = "secret-" + regenerateCalls.get() + "-" + UUID.randomUUID();
            return lastSecret;
        }

        @Override
        public void disable(String keycloakClientRef) {
            disabledRefs.add(keycloakClientRef);
        }
    }

    /** No secret manager in this test — an in-memory map is enough to prove a reference round-trips. */
    private static final class InMemorySecretWriter implements SecretWriter {
        private final Map<SecretReference, SecretValue> values = new HashMap<>();

        @Override
        public void write(SecretReference reference, SecretValue value) {
            values.put(reference, value);
        }
    }
}
