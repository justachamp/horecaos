package uz.qoida.platform.audit.infrastructure.persistence;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

import tools.jackson.databind.json.JsonMapper;

import uz.qoida.platform.support.TestDatabase;
import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.AuditClass;
import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.domain.ChangeDocuments;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.tenancy.api.TenantId;

/**
 * ADR 0027's two load-bearing guarantees: evidence cannot be rewritten, and a
 * committed change always has its fact.
 */
class AuditImmutabilityTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120901");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private JdbcAuditRecorder recorder;

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
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        recorder = new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        insertTenant();
    }

    @Test
    void recordsAFactWithItsActorScopeAndReason() {
        recorder.record(fact("tenant.suspended", "Non-payment after 60 days"));

        Map<String, Object> row = jdbc.sql("""
                SELECT action_code, actor_subject, actor_type, tenant_id, scope_type, reason, outcome
                  FROM audit.audit_events
                """).query().singleRow();

        assertThat(row)
                .containsEntry("action_code", "tenant.suspended")
                .containsEntry("actor_subject", "operator-1")
                .containsEntry("actor_type", "USER")
                .containsEntry("tenant_id", TENANT)
                .containsEntry("scope_type", "TENANT")
                .containsEntry("reason", "Non-payment after 60 days")
                .containsEntry("outcome", "SUCCEEDED");
    }

    @Test
    void theApplicationRoleCannotRewriteEvidence() {
        recorder.record(fact("tenant.suspended", "Non-payment"));

        // A role is a property of the cluster, not of this suite's database, and
        // the cluster now outlives the class. If the assertions below fail hard
        // enough to skip the finally, the role survives and the next run of this
        // method fails on "role already exists" — a failure with nothing to say
        // about audit immutability. Dropping first makes the method rerunnable.
        jdbc.sql("DROP ROLE IF EXISTS audit_app_probe").update();
        jdbc.sql("CREATE ROLE audit_app_probe LOGIN PASSWORD 'probe'").update();
        jdbc.sql("GRANT qoida_application TO audit_app_probe").update();
        try {
            DataSource restricted =
                    db.dataSourceAs("audit_app_probe", "probe");
            JdbcClient asApplication = JdbcClient.create(restricted);

            assertThat(asApplication.sql("SELECT count(*) FROM audit.audit_events")
                    .query(Long.class).single())
                    .as("the application must still be able to read and write evidence")
                    .isEqualTo(1L);

            assertThatThrownBy(() -> asApplication
                    .sql("UPDATE audit.audit_events SET reason = 'rewritten'").update())
                    .as("no application path may modify an audit row")
                    .isInstanceOfAny(DataIntegrityViolationException.class, UncategorizedSQLException.class,
                            org.springframework.dao.PermissionDeniedDataAccessException.class,
                            org.springframework.dao.InvalidDataAccessResourceUsageException.class);

            assertThatThrownBy(() -> asApplication
                    .sql("DELETE FROM audit.audit_events").update())
                    .as("no application path may delete an audit row")
                    .isInstanceOfAny(DataIntegrityViolationException.class, UncategorizedSQLException.class,
                            org.springframework.dao.PermissionDeniedDataAccessException.class,
                            org.springframework.dao.InvalidDataAccessResourceUsageException.class);
        } finally {
            jdbc.sql("REVOKE qoida_application FROM audit_app_probe").update();
            jdbc.sql("DROP ROLE audit_app_probe").update();
        }
    }

    @Test
    void aRolledBackChangeLeavesNoMisleadingEvidence() {
        TransactionTemplate transactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.sql("UPDATE tenant.tenants SET display_name = 'Changed' WHERE id = :id")
                    .param("id", TENANT).update();
            recorder.record(fact("tenant.renamed", "Rebrand"));
            throw new IllegalStateException("business failure after the audit write");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_events").query(Long.class).single())
                .as("audit joins the caller's transaction, so a rolled-back change records nothing")
                .isZero();
        assertThat(jdbc.sql("SELECT display_name FROM tenant.tenants WHERE id = :id")
                .param("id", TENANT).query(String.class).single())
                .isEqualTo("Display");
    }

    @Test
    void aCommittedChangeAlwaysHasItsFact() {
        TransactionTemplate transactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transactions.executeWithoutResult(status -> {
            jdbc.sql("UPDATE tenant.tenants SET display_name = 'Renamed' WHERE id = :id")
                    .param("id", TENANT).update();
            recorder.record(fact("tenant.renamed", "Rebrand"));
        });

        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_events").query(Long.class).single())
                .isEqualTo(1L);
    }

    @Test
    void protectedValuesNeverReachTheTable() {
        recorder.record(AuditFact.of("customer.contact_changed", AuditClass.BUSINESS)
                .by(ActorRef.user("operator-1", "Operator One"))
                .at(ResourceScope.tenant(TENANT))
                .because("Customer requested update")
                .changed(ChangeDocuments.change("customerPhone", "+998901231076", "+998901231077"))
                .correlatedBy("correlation-1")
                .occurredAt(Instant.parse("2026-08-20T10:00:00Z"))
                .build());

        String document = jdbc.sql("SELECT change_document::text FROM audit.audit_events")
                .query(String.class).single();

        assertThat(document)
                .as("the audit trail must not become a second copy of the data it protects")
                .doesNotContain("998901231076")
                .doesNotContain("998901231077")
                .contains(ChangeDocuments.REDACTED);
    }

    @Test
    void aUserActionWithoutAReasonIsRejectedBeforeItReachesTheDatabase() {
        assertThatThrownBy(() -> fact("tenant.suspended", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a reason");
    }

    @Test
    void aBackfillIsNeverMistakenForAPerson() {
        recorder.record(AuditFact.of("tenant.imported", AuditClass.BUSINESS)
                .by(ActorRef.migration("wave-1-run-7"))
                .at(ResourceScope.tenant(TENANT))
                .correlatedBy("correlation-1")
                .occurredAt(Instant.parse("2026-08-20T10:00:00Z"))
                .build());

        assertThat(jdbc.sql("SELECT actor_type FROM audit.audit_events").query(String.class).single())
                .isEqualTo("MIGRATION");
    }

    private AuditFact fact(String actionCode, String reason) {
        return AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(ActorRef.user("operator-1", "Operator One"))
                .at(ResourceScope.tenant(TENANT))
                .target("Tenant", TENANT)
                .because(reason)
                .usingCapability("tenant.write")
                .correlatedBy("correlation-1")
                .occurredAt(Instant.parse("2026-08-20T10:00:00Z"))
                .build();
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-audit', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }
}
