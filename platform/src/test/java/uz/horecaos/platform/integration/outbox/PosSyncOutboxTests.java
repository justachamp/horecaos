package uz.horecaos.platform.integration.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.integration.api.pos.PosSyncRequestedPayload;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.domain.Slug;
import uz.horecaos.platform.tenancy.domain.Tenant;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantControlPlaneStore;

/**
 * The producer half of ADR 0012's durable scheduler command (ADR 0004, ADR
 * 0032): an outbox row, in the caller's own transaction, never a direct publish.
 */
class PosSyncOutboxTests {

    private static final Instant NOW = Instant.parse("2026-08-24T05:00:00Z");
    private static final UUID BINDING = UUID.fromString("018f6f4e-7400-7000-8000-0000000d0002");

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private PosSyncOutbox outbox;
    private JdbcTenantControlPlaneStore tenancy;
    private Tenant tenant;

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
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        outbox = new PosSyncOutbox(
                new JdbcOutboxStore(jdbc),
                JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        tenancy = new JdbcTenantControlPlaneStore(jdbc);
        tenant = Tenant.provision(
                new TenantId(UUID.fromString("018f6f4e-7400-7000-8000-0000000d0001")),
                new Slug("pos-sync-outbox"),
                "Pos Sync Outbox LLC",
                "Pos Sync Outbox",
                Currency.getInstance("UZS"),
                ZoneId.of("Asia/Tashkent"));
        tenancy.insertTenant(tenant);
    }

    @Test
    @DisplayName("requestSync appends one row on the binding's own partition, topic, and event contract")
    void appendsOnePosCommandsRow() {
        PosSyncRequestedPayload command = PosSyncRequestedPayload.scheduled(
                UUID.randomUUID(), tenant.id().value(), BINDING, UUID.randomUUID(), NOW);

        outbox.requestSync(command, "request-42");

        var claimed = new JdbcOutboxStore(jdbc).claimBatch(NOW, java.time.Duration.ofMinutes(5), 10);

        assertThat(claimed).singleElement().satisfies(row -> {
            assertThat(row.eventType()).isEqualTo("PosSyncRequested");
            assertThat(row.eventVersion()).isEqualTo(1);
            assertThat(row.topic()).isEqualTo("pos.commands");
            assertThat(row.tenantId()).isEqualTo(tenant.id().value());
            assertThat(row.aggregateType()).isEqualTo("PosBinding");
            assertThat(row.aggregateId()).isEqualTo(BINDING);
            assertThat(row.partitionKey()).isEqualTo(BINDING.toString());
            assertThat(row.correlationId()).isEqualTo("request-42");
            assertThat(row.payloadJson()).contains("SCHEDULED");
        });
    }

    @Test
    @DisplayName("a rolled-back business transaction rolls back the command with it")
    void rollsBackWithItsBusinessTransaction() {
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        PosSyncRequestedPayload command = PosSyncRequestedPayload.scheduled(
                UUID.randomUUID(), tenant.id().value(), BINDING, UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    outbox.requestSync(command, "request-43");
                    throw new ExpectedRollback();
                }))
                .isInstanceOf(ExpectedRollback.class);

        assertThat(jdbc.sql("SELECT count(*) FROM integration.outbox_events WHERE event_type = 'PosSyncRequested'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private static final class ExpectedRollback extends RuntimeException {}
}
