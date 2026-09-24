package uz.horecaos.platform.marketing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Row 7.9b's aggregate: {@code GET .../campaigns/{campaignId}/recipients/counts},
 * added this wave over the store-level {@code recipientCounts} rollup that
 * {@code CampaignFeedbackService} already relied on internally but nothing
 * ever exposed. Capability split mirrors {@link OperationsMarketingResumeEndpointTests}:
 * {@code TENANT_ADMIN} holds {@code campaign.author} (the same capability
 * {@code .../recipients} itself already declares), {@code TENANT_OWNER}
 * does not, which is this class's negative case.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsMarketingRecipientCountsEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000a3");
    private static final UUID AUDIENCE = UUID.fromString("018f9b20-4000-7000-8000-0000000000a4");

    private static final String OWNER = "campaign-counts-owner";
    private static final String ADMINISTRATOR = "campaign-counts-administrator";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);

        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE marketing.campaign_recipients, marketing.campaign_batches, "
                        + "marketing.campaigns, marketing.audience_snapshots, marketing.audiences CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedTenantAndBrands();
        roleRegistry.synchronize();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN);
    }

    @Test
    void anOwnerWithoutCampaignAuthorCannotReadRecipientCounts() throws Exception {
        UUID campaignId = insertCampaign(BRAND);

        MvcResult refused = mvc.perform(get(countsPath(BRAND, campaignId)).with(tokenFor(OWNER)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.CAMPAIGN_AUTHOR.code());
    }

    @Test
    void countsGroupByTheSameStatusRecipientsItselfReturnsAndSumToTheTotal() throws Exception {
        UUID campaignId = insertCampaign(BRAND);
        insertRecipient(campaignId, seedCustomer(), 0, "QUEUED");
        insertRecipient(campaignId, seedCustomer(), 1, "QUEUED");
        insertRecipient(campaignId, seedCustomer(), 2, "DEFERRED");
        insertRecipient(campaignId, seedCustomer(), 3, "REFUSED");
        insertRecipient(campaignId, seedCustomer(), 4, "PENDING");

        MvcResult result = mvc.perform(get(countsPath(BRAND, campaignId)).with(tokenFor(ADMINISTRATOR)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"pending\":1")
                .contains("\"queued\":2")
                .contains("\"deferred\":1")
                .contains("\"refused\":1")
                .contains("\"total\":5");
    }

    /**
     * Row 6.4 (wave 10 w5-reports-exports): the campaign history/statistics
     * view's own "suppressed" count — nameable from {@code refusal_reason}
     * alone, without a terminal-status projection this wave does not add.
     */
    @Test
    void refusedByReasonBreaksDownTheRefusedTotalIncludingSuppressed() throws Exception {
        UUID campaignId = insertCampaign(BRAND);
        insertRecipientRefused(campaignId, seedCustomer(), 0, "SUPPRESSED");
        insertRecipientRefused(campaignId, seedCustomer(), 1, "SUPPRESSED");
        insertRecipientRefused(campaignId, seedCustomer(), 2, "CONSENT_WITHHELD");
        insertRecipient(campaignId, seedCustomer(), 3, "QUEUED");

        MvcResult result = mvc.perform(get(countsPath(BRAND, campaignId)).with(tokenFor(ADMINISTRATOR)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"refused\":3").contains("\"queued\":1");
        assertThat(body)
                .as("the same REFUSED total, broken down by reason — SUPPRESSED nameable on its own")
                .contains("\"refusedByReason\"")
                .contains("\"SUPPRESSED\":2")
                .contains("\"CONSENT_WITHHELD\":1");
    }

    @Test
    void recipientCountsRefusesACampaignFromAnotherBrand() throws Exception {
        UUID campaignId = insertCampaign(OTHER_BRAND);

        MvcResult notFound = mvc.perform(get(countsPath(BRAND, campaignId)).with(tokenFor(ADMINISTRATOR)))
                .andReturn();

        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(notFound.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    // ------------------------------------------------------------------ fixtures

    private static String countsPath(UUID brandId, UUID campaignId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + brandId + "/marketing/campaigns/" + campaignId
                + "/recipients/counts";
    }

    private void insertRecipient(UUID campaignId, UUID customerAccountId, int sequence, String status) {
        UUID notificationId = "QUEUED".equals(status) ? UUID.randomUUID() : null;
        String refusalReason = "REFUSED".equals(status) ? "SUPPRESSED" : null;
        OffsetDateTime deferredUntil =
                "DEFERRED".equals(status) ? OffsetDateTime.now(ZoneOffset.UTC).plusHours(1) : null;
        jdbc.sql("""
                INSERT INTO marketing.campaign_recipients (
                    campaign_id, tenant_id, customer_account_id, sequence, status,
                    notification_id, refusal_reason, deferred_until, created_at, updated_at)
                VALUES (:campaignId, :tenantId, :accountId, :sequence, :status,
                    :notificationId, :refusalReason, :deferredUntil, now(), now())
                """)
                .param("campaignId", campaignId)
                .param("tenantId", TENANT)
                .param("accountId", customerAccountId)
                .param("sequence", sequence)
                .param("status", status)
                .param("notificationId", notificationId)
                .param("refusalReason", refusalReason)
                .param("deferredUntil", deferredUntil)
                .update();
    }

    /** Row 6.4: a REFUSED recipient with a chosen reason, unlike {@link #insertRecipient}'s own fixed SUPPRESSED. */
    private void insertRecipientRefused(UUID campaignId, UUID customerAccountId, int sequence, String refusalReason) {
        jdbc.sql("""
                INSERT INTO marketing.campaign_recipients (
                    campaign_id, tenant_id, customer_account_id, sequence, status,
                    refusal_reason, created_at, updated_at)
                VALUES (:campaignId, :tenantId, :accountId, :sequence, 'REFUSED',
                    :refusalReason, now(), now())
                """)
                .param("campaignId", campaignId)
                .param("tenantId", TENANT)
                .param("accountId", customerAccountId)
                .param("sequence", sequence)
                .param("refusalReason", refusalReason)
                .update();
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, preferred_locale, created_at)
                VALUES (:id, :tenantId, 'ACTIVE', 'ru', now())
                """).param("id", id).param("tenantId", TENANT).update();
        return id;
    }

    private UUID insertCampaign(UUID brandId) {
        UUID id = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO marketing.audience_snapshots (
                    id, tenant_id, brand_id, audience_id, definition_version, channel, consent_purpose,
                    status, metric_definition_version, built_by, built_at, completed_at)
                VALUES (:id, :tenantId, :brandId, :audienceId, 1, 'SMS', 'MARKETING_PROMOTIONS',
                    'READY', 1, :builtBy, now(), now())
                """)
                .param("id", snapshotId)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("audienceId", AUDIENCE)
                .param("builtBy", UUID.randomUUID())
                .update();
        jdbc.sql("""
                INSERT INTO marketing.campaigns (
                    id, tenant_id, brand_id, name, channel, consent_purpose, status,
                    audience_id, audience_snapshot_id, template_key, recipient_cap, currency, timezone,
                    created_by, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :name, 'SMS', 'MARKETING_PROMOTIONS', 'DRAFT',
                    :audienceId, :snapshotId, 'MARKETING_PROMOTION', 100, 'UZS', 'Asia/Tashkent', :createdBy,
                    now(), now())
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("name", "Recipient counts fixture " + id)
                .param("audienceId", AUDIENCE)
                .param("snapshotId", snapshotId)
                .param("createdBy", UUID.randomUUID())
                .update();
        return id;
    }

    private void seedTenantAndBrands() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'campaign-counts-endpoint', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'OTHER', 'other', 'Other brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO marketing.audiences (id, tenant_id, brand_id, name, created_by)
                VALUES (:id, :tenantId, :brandId, 'Everybody', :createdBy)
                """)
                .param("id", AUDIENCE)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("createdBy", UUID.randomUUID())
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'recipient counts endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(StandardCharsets.UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }
    }
}
