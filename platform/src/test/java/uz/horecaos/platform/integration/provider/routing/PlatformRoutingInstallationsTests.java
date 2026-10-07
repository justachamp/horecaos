package uz.horecaos.platform.integration.provider.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.fulfillment.api.RoutingInstallationPort.RoutingEngineStatus;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;

/**
 * "Use platform routing" (ADR 0147, decision 3): the keyless installation, created once
 * per tenant and only ever idempotently.
 */
class PlatformRoutingInstallationsTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final String ACTOR = UUID.randomUUID().toString();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcRoutingInstallations installations;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the installation tests");
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
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedTenant(TENANT, "routing-installation-tenant");
        seedTenant(OTHER_TENANT, "other-routing-installation-tenant");
        installations = new JdbcRoutingInstallations(jdbc);
    }

    @Test
    @DisplayName("the first use creates one active, keyless ROUTING installation of the approved endpoint")
    void theFirstUseCreatesTheInstallation() {
        UUID id = service(OsrmProperties.enabledWith("2026-10-01")).ensurePlatformRouting(TENANT);

        Map<String, Object> row =
                jdbc.sql("""
                SELECT provider_category, provider_type, environment_code, status,
                       secret_reference, external_account_reference
                  FROM integration.installations WHERE id = :id AND tenant_id = :tenantId
                """).param("id", id).param("tenantId", TENANT).query().singleRow();
        assertThat(row)
                .containsEntry("provider_category", "ROUTING")
                .containsEntry("provider_type", "OSRM")
                // The environment is platform-owned reference data. A tenant never names
                // a URL, so nothing here can point the platform at a host of its choosing.
                .containsEntry("environment_code", "osrm_internal")
                .containsEntry("status", "ACTIVE")
                .containsEntry("external_account_reference", "platform-routing");
        // Keyless: there is no secret to ingest, so the reference is absent.
        assertThat(row.get("secret_reference")).isNull();
    }

    @Test
    @DisplayName("a second request returns the same installation and writes nothing")
    void aSecondRequestIsIdempotent() {
        PlatformRoutingInstallations service = service(OsrmProperties.enabledWith("2026-10-01"));

        UUID first = service.ensurePlatformRouting(TENANT);
        UUID second = service.ensurePlatformRouting(TENANT);

        assertThat(second).isEqualTo(first);
        assertThat(count(TENANT)).isEqualTo(1);
        // One creation, one fact: the second request found the row and said nothing.
        assertThat(AuditTrail.facts(jdbc, "integration.installation_created")).hasSize(1);
    }

    @Test
    @DisplayName("two requests at once still leave one installation, and both are given it")
    void concurrentRequestsShareOneInstallation() throws Exception {
        PlatformRoutingInstallations service = service(OsrmProperties.enabledWith("2026-10-01"));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<UUID>> requests = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                requests.add(() -> service.ensurePlatformRouting(TENANT));
            }
            List<UUID> ids = new ArrayList<>();
            for (Future<UUID> result : pool.invokeAll(requests)) {
                ids.add(result.get());
            }

            // The unique index on the account reference is what makes this safe, not
            // the read before the insert: a read-then-write alone loses this race.
            assertThat(ids).doesNotContainNull().containsOnly(ids.getFirst());
            assertThat(count(TENANT)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("each tenant has its own installation and never sees another's")
    void tenantsDoNotShareAnInstallation() {
        PlatformRoutingInstallations service = service(OsrmProperties.enabledWith("2026-10-01"));

        UUID mine = service.ensurePlatformRouting(TENANT);
        UUID theirs = service.ensurePlatformRouting(OTHER_TENANT);

        assertThat(theirs).isNotEqualTo(mine);
        assertThat(jdbc.sql("SELECT tenant_id FROM integration.installations WHERE id = :id")
                        .param("id", mine)
                        .query(UUID.class)
                        .single())
                .isEqualTo(TENANT);
    }

    @Test
    @DisplayName("the creation is an audit fact naming the installation, the category and the approved environment")
    void theCreationIsAudited() {
        UUID id = service(OsrmProperties.enabledWith("2026-10-01")).ensurePlatformRouting(TENANT);

        AuditTrail.Fact fact = AuditTrail.only(jdbc, "integration.installation_created");
        assertThat(fact.targetId()).isEqualTo(id);
        assertThat(fact.scopeId()).isEqualTo(TENANT);
        assertThat(fact.after("category").asString()).isEqualTo("ROUTING");
        assertThat(fact.after("environment").asString()).isEqualTo("osrm_internal");
        assertThat(fact.before("category").isMissingNode()
                        || fact.before("category").isNull())
                .as("a from-nothing creation")
                .isTrue();
    }

    @Test
    @DisplayName("the engine status says what is configured and installed, and reads nothing from the engine")
    void theEngineStatusIsConfigurationAndStanding() {
        PlatformRoutingInstallations on = service(OsrmProperties.enabledWith("2026-10-01"));
        UUID id = on.ensurePlatformRouting(TENANT);

        assertThat(on.engineStatus(id)).isEqualTo(new RoutingEngineStatus(true, "ACTIVE", "osrm", "2026-10-01"));

        jdbc.sql("UPDATE integration.installations SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", id)
                .update();
        RoutingEngineStatus suspended = on.engineStatus(id);
        assertThat(suspended.installationStatus()).isEqualTo("SUSPENDED");
        assertThat(suspended.answering())
                .as("a suspended installation is not answering")
                .isFalse();

        PlatformRoutingInstallations off = service(new OsrmProperties(
                false, "2026-10-01", java.time.Duration.ofMillis(500), 1_000, 10, java.time.Duration.ofSeconds(30)));
        RoutingEngineStatus switchedOff = off.engineStatus(id);
        assertThat(switchedOff.engineEnabled()).isFalse();
        assertThat(switchedOff.datasetVersion())
                .as("no engine, no dataset to name")
                .isNull();

        assertThat(on.engineStatus(null)).isEqualTo(new RoutingEngineStatus(true, null, null, "2026-10-01"));
        assertThat(on.engineStatus(UUID.randomUUID()).installationStatus()).isNull();
    }

    // ------------------------------------------------------------------- helpers

    private PlatformRoutingInstallations service(OsrmProperties properties) {
        return new PlatformRoutingInstallations(
                installations,
                properties,
                AuditTrail.recorder(jdbc),
                AuditTrail.actor(ACTOR),
                Clock.fixed(Instant.parse("2026-10-08T06:00:00Z"), ZoneOffset.UTC));
    }

    private long count(UUID tenantId) {
        return jdbc.sql(
                        "SELECT count(*) FROM integration.installations WHERE tenant_id = :tenantId AND provider_category = 'ROUTING'")
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();
    }

    private void seedTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }
}
