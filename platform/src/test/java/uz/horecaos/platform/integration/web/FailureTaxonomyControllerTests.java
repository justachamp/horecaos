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
import uz.horecaos.platform.integration.failures.FailureCategory;
import uz.horecaos.platform.support.TestDatabase;

/** ADR 0086 against PostgreSQL: every category with its live counts. */
class FailureTaxonomyControllerTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac1586a1");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;

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
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'taxonomy', 'Taxonomy', 'Taxonomy', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    @Test
    void everyCategoryIsListedWithItsRulesAndWhatSitsInItNow() {
        outbox("DEAD_LETTER", "UNCERTAIN_EXTERNAL_OUTCOME");
        outbox("DEAD_LETTER", "UNCERTAIN_EXTERNAL_OUTCOME");
        outbox("PENDING", "TRANSIENT_PROVIDER");
        outbox("DEAD_LETTER", "A_CODE_NOBODY_DECLARES");

        var taxonomy = new FailureTaxonomyController(jdbc).taxonomy();

        assertThat(taxonomy)
                .extracting(FailureTaxonomyController.CategoryView::code)
                .as("every category, including the empty ones")
                .containsExactly(java.util.Arrays.stream(FailureCategory.values())
                        .map(Enum::name)
                        .toArray(String[]::new));
        assertThat(taxonomy)
                .filteredOn(view -> view.code().equals("UNCERTAIN_EXTERNAL_OUTCOME"))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.outboxDeadLettered()).isEqualTo(2);
                    assertThat(view.requiresReconciliation()).isTrue();
                    assertThat(view.retryableByTimer()).isFalse();
                });
        assertThat(taxonomy)
                .filteredOn(view -> view.code().equals("TRANSIENT_PROVIDER"))
                .singleElement()
                .satisfies(view -> assertThat(view.outboxWaiting()).isEqualTo(1));
        assertThat(taxonomy)
                .filteredOn(view -> view.code().equals("UNKNOWN"))
                .singleElement()
                .satisfies(view -> assertThat(view.outboxDeadLettered())
                        .as("a code the enum no longer declares is counted as unknown, not dropped")
                        .isEqualTo(1));
    }

    private void outbox(String status, String errorCode) {
        jdbc.sql("""
                INSERT INTO integration.outbox_events (
                    event_id, event_type, event_version, tenant_id, aggregate_type, aggregate_id,
                    topic, partition_key, correlation_id, occurred_at, payload, status,
                    attempt_count, dead_lettered_at, error_code, last_error)
                VALUES (
                    :eventId, 'TenantCreated', 1, :tenantId, 'Tenant', :tenantId,
                    'tenancy.events', :tenantId, 'correlation-1', now(), '{}'::jsonb, :status,
                    3, CASE WHEN :status = 'DEAD_LETTER' THEN now() ELSE NULL END, :code, 'failed')
                """)
                .param("eventId", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("status", status)
                .param("code", errorCode)
                .update();
    }
}
