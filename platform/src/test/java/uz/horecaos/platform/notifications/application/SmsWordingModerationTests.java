package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0091 as decided on 2026-09-11: a new SMS wording for a gateway that
 * moderates texts starts waiting for the gateway's approval, attributed to the
 * platform's rule rather than to a person.
 *
 * <p>Against the migrated schema, so the VAS endpoint V0061 approves and V0208
 * marks as moderating is the real one. Each case differs from the next in one
 * fact, so a rule that ignored the tenant's bindings, or ignored the endpoint's
 * flag, fails one of them.
 */
class SmsWordingModerationTests {

    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private NotificationTemplateService templates;
    private UUID tenantId;
    private UUID brandId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE notifications.templates CASCADE").update();
        jdbc.sql("DELETE FROM integration.provider_environments WHERE code = 'quiet-gateway'")
                .update();
        jdbc.sql("UPDATE integration.provider_environments SET moderates_wordings = (provider_type = 'SMSGW_VAS')")
                .update();
        templates = new NotificationTemplateService(
                new JdbcTemplateStore(jdbc), JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));

        tenantId = UUID.randomUUID();
        brandId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'moderation', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', 'moderation-brand', 'Pilot brand', 'ACTIVE')
                """).param("id", brandId).param("tenantId", tenantId).update();
    }

    @Test
    @DisplayName("with no SMS gateway bound yet, a new wording waits while any approved gateway moderates")
    void anUnboundTenantsWordingWaitsForAModeratingGateway() {
        String review = saveSmsWording();

        assertThat(review).isEqualTo(TemplateProviderReviewService.PENDING);
        assertThat(reviewedBy())
                .as("no person marked it; the platform's rule did")
                .isEqualTo(JdbcTemplateStore.GATEWAY_MODERATION);
    }

    @Test
    @DisplayName("when no approved gateway moderates, a new wording needs no review")
    void nothingWaitsWhenNoGatewayModerates() {
        jdbc.sql("UPDATE integration.provider_environments SET moderates_wordings = false")
                .update();

        assertThat(saveSmsWording()).isEqualTo(TemplateProviderReviewService.NOT_REQUIRED);
    }

    @Test
    @DisplayName("a tenant bound to a gateway that does not moderate is not held for one that does")
    void theTenantsOwnGatewayDecides() {
        bindSmsTo("quiet-gateway", false);

        assertThat(saveSmsWording())
                .as("VAS still moderates, but this tenant does not send through it")
                .isEqualTo(TemplateProviderReviewService.NOT_REQUIRED);

        jdbc.sql("UPDATE integration.provider_environments SET moderates_wordings = true WHERE code = 'quiet-gateway'")
                .update();
        assertThat(saveSmsWording()).isEqualTo(TemplateProviderReviewService.PENDING);
    }

    @Test
    @DisplayName("a Telegram wording is never held for an SMS gateway")
    void onlySmsWordingsWait() {
        UUID templateId = templates.createTemplate(
                tenantId,
                brandId,
                "STAFF_ALERT",
                NotificationClass.TRANSACTIONAL_REQUIRED,
                NotificationChannel.TELEGRAM,
                null);
        templates.addVersion(tenantId, templateId, wordings(), Map.of());

        assertThat(reviewOf(templateId)).isEqualTo(TemplateProviderReviewService.NOT_REQUIRED);
    }

    // ------------------------------------------------------------- fixtures

    private String saveSmsWording() {
        UUID templateId = templates.createTemplate(
                tenantId,
                brandId,
                "ORDER_READY_" + UUID.randomUUID().toString().substring(0, 8),
                NotificationClass.TRANSACTIONAL_REQUIRED,
                NotificationChannel.SMS,
                null);
        templates.addVersion(tenantId, templateId, wordings(), Map.of());
        return reviewOf(templateId);
    }

    private static Map<MessageLocale, Wording> wordings() {
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required().forEach(locale -> wordings.put(locale, new Wording(null, "Your order is ready")));
        return wordings;
    }

    /** Every locale of the one version carries the same review; the distinct set proves it. */
    private String reviewOf(UUID templateId) {
        return jdbc.sql("""
                SELECT DISTINCT provider_review FROM notifications.template_versions
                 WHERE tenant_id = :tenantId AND template_id = :templateId
                """)
                .param("tenantId", tenantId)
                .param("templateId", templateId)
                .query(String.class)
                .single();
    }

    private String reviewedBy() {
        return jdbc.sql("""
                SELECT provider_review_updated_by FROM notifications.template_versions
                 WHERE tenant_id = :tenantId LIMIT 1
                """).param("tenantId", tenantId).query(String.class).single();
    }

    private void bindSmsTo(String environmentCode, boolean moderates) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist,
                    moderates_wordings)
                VALUES (:code, 'NOTIFICATION', 'SMSGW_QUIET', 'http://127.0.0.1:1', false, '127.0.0.1', :moderates)
                """)
                .param("code", environmentCode)
                .param("moderates", moderates)
                .update();
        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'SMSGW_QUIET', :code,
                        'Quiet gateway', 'ACTIVE', 'horecaos:local:provider_notification:platform:quiet',
                        'horecaos:local:provider_notification:platform:quiet')
                """)
                .param("id", installationId)
                .param("tenantId", tenantId)
                .param("code", environmentCode)
                .update();
        UUID bindingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", bindingId)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("brandId", brandId)
                .update();
        jdbc.sql("""
                INSERT INTO integration.binding_capabilities (binding_id, tenant_id, capability_code, is_primary)
                VALUES (:bindingId, :tenantId, 'SEND_SMS', true)
                """).param("bindingId", bindingId).param("tenantId", tenantId).update();
    }
}
