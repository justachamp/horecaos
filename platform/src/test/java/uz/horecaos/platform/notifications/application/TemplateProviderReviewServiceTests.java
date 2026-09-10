package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.integration.web.NotificationProviderRegistryControllerProbe;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/** ADR 0091: an SMS wording's standing with its gateway, recorded with the provider's own words. */
class TemplateProviderReviewServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-2100-7000-8000-0000000000e1");
    private static final ActorRef DESK = ActorRef.user("moderation-desk", null);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private List<AuditFact> facts;
    private TemplateProviderReviewService reviews;
    private UUID smsVersion;
    private UUID emailVersion;

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
        jdbc.sql("TRUNCATE TABLE notifications.templates CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'reviews', 'Non uyi', 'Non uyi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        smsVersion = seedVersion("ORDER_CONFIRMED", "SMS");
        emailVersion = seedVersion("ORDER_RECEIPT", "EMAIL");
        facts = new ArrayList<>();
        reviews = new TemplateProviderReviewService(
                jdbc, facts::add, Clock.fixed(Instant.parse("2026-09-11T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void anSmsWordingWaitsThenIsApprovedWithTheProvidersReference() {
        reviews.record(TENANT, smsVersion, "PENDING", null, null, DESK, "submitted to the gateway");

        assertThat(reviews.list("PENDING", 50)).singleElement().satisfies(row -> {
            assertThat(row.templateKey()).isEqualTo("ORDER_CONFIRMED");
            assertThat(row.updatedBy()).isEqualTo("moderation-desk");
        });
        assertThat(TemplateProviderReviewService.withheld("PENDING")).isTrue();

        assertThatThrownBy(() -> reviews.record(TENANT, smsVersion, "APPROVED", " ", null, DESK, "ok"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reference");
        reviews.record(TENANT, smsVersion, "APPROVED", "ESKIZ-4471", null, DESK, "gateway approved");

        assertThat(reviews.list(null, 50)).singleElement().satisfies(row -> {
            assertThat(row.providerReview()).isEqualTo("APPROVED");
            assertThat(row.reference()).isEqualTo("ESKIZ-4471");
        });
        assertThat(facts).hasSize(2);
    }

    @Test
    void onlyAnSmsWordingWaitsOnAGatewayAndARefusalSaysWhy() {
        assertThatThrownBy(() -> reviews.record(TENANT, emailVersion, "PENDING", null, null, DESK, "x"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only an SMS wording");
        assertThatThrownBy(() -> reviews.record(TENANT, smsVersion, "REJECTED", null, null, DESK, "x"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("objected");
        assertThatThrownBy(() -> reviews.record(TENANT, smsVersion, "MAYBE", null, null, DESK, "x"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theGatewayRegistryIsAValidReadOverTheMigratedSchema() {
        assertThat(NotificationProviderRegistryControllerProbe.registry(jdbc).gateways())
                .as("V0061 approves the SMS gateway endpoint")
                .isNotEmpty();
    }

    private UUID seedVersion(String key, String channel) {
        UUID templateId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notifications.templates (id, tenant_id, template_key, notification_class, channel)
                VALUES (:id, :tenantId, :key, 'TRANSACTIONAL_REQUIRED', :channel)
                """)
                .param("id", templateId)
                .param("tenantId", TENANT)
                .param("key", key)
                .param("channel", channel)
                .update();
        UUID versionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notifications.template_versions (id, tenant_id, template_id, version_number, locale,
                    body_template, variables_schema, content_hash)
                VALUES (:id, :tenantId, :templateId, 1, 'ru', 'Заказ принят', '{}'::jsonb, :hash)
                """)
                .param("id", versionId)
                .param("tenantId", TENANT)
                .param("templateId", templateId)
                .param("hash", "0".repeat(64))
                .update();
        return versionId;
    }
}
