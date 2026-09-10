package uz.horecaos.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0084 against the migrated schema: every figure is an exact count across
 * tenants, and every query the board runs agrees with the tables it reads.
 */
class PlatformHealthControllerTests {

    private static final Instant NOW = Instant.parse("2026-09-11T04:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private PlatformHealthController controller;

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
        jdbc.sql("TRUNCATE TABLE integration.outbox_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.inbox_messages CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        controller = new PlatformHealthController(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void tenantsAreCountedExactlyByStatusNotSampledFromAPage() {
        for (int i = 0; i < 3; i++) {
            tenant("active-" + i, "ACTIVE");
        }
        tenant("setting-up", "PROVISIONING");

        PlatformHealthController.PlatformHealth health = controller.health();

        assertThat(health.tenantsByStatus()).containsEntry("ACTIVE", 3L).containsEntry("PROVISIONING", 1L);
        assertThat(health.orders().lastDay())
                .as("every order and receipt query ran")
                .isZero();
        assertThat(health.receipts().blocked()).isZero();
        assertThat(health.measuredAt()).isEqualTo(NOW.toString());
    }

    @Test
    void aQueuesBacklogIsGivenByItsOldestWaitingMessageAndDeadLettersAreCounted() {
        UUID tenant = tenant("queue-tenant", "ACTIVE");
        outbox(tenant, "PENDING", NOW.minusSeconds(1200));
        outbox(tenant, "PENDING", NOW.minusSeconds(60));
        outbox(tenant, "DEAD_LETTER", NOW.minusSeconds(60));

        PlatformHealthController.QueueFigures queues = controller.health().queues();

        assertThat(queues.outbox()).singleElement().satisfies(topic -> {
            assertThat(topic.name()).isEqualTo("tenancy.events");
            assertThat(topic.pending()).isEqualTo(2);
            assertThat(topic.oldestAgeSeconds())
                    .as("age, not depth: the oldest waiting event is twenty minutes old")
                    .isEqualTo(1200);
        });
        assertThat(queues.outboxDeadLetters()).isEqualTo(1);
        assertThat(queues.inbox()).isEmpty();
    }

    @Test
    void theCrossTenantBlockedReceiptListRunsAgainstTheFiscalTable() {
        assertThat(new JdbcFiscalLifecycleStore(jdbc).blockedAcrossTenants(50)).isEmpty();
    }

    private UUID tenant(String slug, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, :slug, :slug, 'UZS', 'Asia/Tashkent', :status, 0)
                """)
                .param("id", id)
                .param("slug", slug)
                .param("status", status)
                .update();
        return id;
    }

    private void outbox(UUID tenantId, String status, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO integration.outbox_events (
                    event_id, event_type, event_version, tenant_id, aggregate_type, aggregate_id,
                    topic, partition_key, correlation_id, occurred_at, payload, status,
                    attempt_count, dead_lettered_at, error_code, last_error, created_at)
                VALUES (
                    :eventId, 'TenantCreated', 1, :tenantId, 'Tenant', :tenantId,
                    'tenancy.events', :tenantId, 'correlation-1', now(), '{}'::jsonb, :status,
                    1, CASE WHEN :status = 'DEAD_LETTER' THEN now() ELSE NULL END,
                    NULL, NULL, :createdAt)
                """)
                .param("eventId", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("status", status)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }
}
