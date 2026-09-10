package uz.horecaos.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.support.TestDatabase;

/** ADR 0092: the data-protection overview reads what the database and the audit trail hold. */
class DataProtectionControllerTests {

    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");

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
    void everyRetentionRuleNamesAJobThatStillExists() {
        for (DataProtectionController.RetentionRule rule :
                DataProtectionController.retentionRules(new MockEnvironment())) {
            assertThat(classExists(rule.enforcedBy()))
                    .as("%s is enforced by %s", rule.code(), rule.enforcedBy())
                    .isTrue();
        }
    }

    @Test
    void theOverviewListsEncryptedColumnsAndCountsARevealFromTheAuditTrail() {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        DataProtectionController controller =
                new DataProtectionController(jdbc, new MockEnvironment(), Clock.fixed(NOW, ZoneOffset.UTC));

        DataProtectionController.DataProtection before = controller.overview();
        assertThat(before.encryptedColumns())
                .as("read from the catalog, so a new encrypted column appears without anyone listing it")
                .anySatisfy(column -> {
                    assertThat(column.schema()).isEqualTo("customer");
                    assertThat(column.column()).endsWith("_encrypted");
                });
        assertThat(before.classes()).anySatisfy(dataClass -> {
            assertThat(dataClass.code()).isEqualTo("PERSONAL");
            assertThat(dataClass.requiresEncryption()).isTrue();
        });
        assertThat(before.erasure().pending()).isZero();

        new JdbcAuditRecorder(jdbc, JsonMapper.builder().build())
                .record(AuditFact.of("customer.contact.revealed", AuditClass.SECURITY)
                        .by(ActorRef.user("operator-1", null))
                        .at(ResourceScope.platform())
                        .target("CustomerAccount", UUID.randomUUID())
                        .because("calling about a late order")
                        .correlatedBy("test-reveal")
                        .occurredAt(NOW.minusSeconds(60))
                        .build());

        assertThat(controller.overview().egressLast30Days()).anySatisfy(count -> {
            assertThat(count.actionCode()).isEqualTo("customer.contact.revealed");
            assertThat(count.count()).isGreaterThanOrEqualTo(1);
        });
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }
}
