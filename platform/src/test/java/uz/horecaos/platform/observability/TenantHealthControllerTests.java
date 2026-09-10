package uz.horecaos.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/** ADR 0090: the directory's health column reads the three queues a tenant's issue queue lists. */
class TenantHealthControllerTests {

    private static TestDatabase.Handle db;

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

    @Test
    void aTenantWithNothingWaitingIsNotListedAndOneWithADeadLetterIs() {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        TenantHealthController controller = new TenantHealthController(jdbc);
        assertThat(controller.health())
                .as("every query is valid against the migrated schema")
                .isEmpty();

        UUID tenant = UUID.fromString("018f6f4e-2100-7000-8000-0000000000d1");
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'health', 'Health', 'Health', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenant).update();
        jdbc.sql("""
                INSERT INTO integration.outbox_events (event_id, tenant_id, aggregate_type, aggregate_id,
                    event_type, event_version, topic, partition_key, payload, status, correlation_id,
                    occurred_at, dead_lettered_at, last_error)
                VALUES (gen_random_uuid(), :tenant, 'Order', gen_random_uuid(), 'OrderPlaced', 1,
                    'orders.events', 'k', '{}'::jsonb, 'DEAD_LETTER', 'c', now(), now(), 'PAYLOAD_INVALID')
                """).param("tenant", tenant).update();

        assertThat(controller.health()).singleElement().satisfies(row -> {
            assertThat(row.tenantId()).isEqualTo(tenant);
            assertThat(row.deadLetters()).isEqualTo(1);
            assertThat(row.openProblems()).isEqualTo(1);
        });
    }
}
