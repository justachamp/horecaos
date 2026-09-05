package uz.horecaos.platform.marketing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.http.MediaType;
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
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The HTTP surface for wave 13's campaign resume (ADR 0044, ADR 0059 stage 4):
 * the capability refusal, and the RESOURCE_CONFLICT a non-PAUSED campaign gets
 * back — the same shape {@code SendPulseContactImportControllerEndpointTests}
 * proves for {@code CUSTOMER_IMPORT}. The state-machine proofs themselves (a
 * real pause, a real resume, delivery continuing, the guard pausing again) are
 * {@code CampaignBroadcastIntegrationTest}'s job, against a real dispatch
 * path; this class only proves the controller's own authorization and
 * conflict handling, so the campaign fixture here is built directly in SQL
 * rather than through a full ADR 0020 send.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsMarketingResumeEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000d1");
    private static final UUID BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000d2");
    private static final UUID AUDIENCE = UUID.fromString("018f9b20-4000-7000-8000-0000000000d3");

    private static final String OWNER = "campaign-resume-owner";
    private static final String ADMINISTRATOR = "campaign-resume-administrator";

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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedTenantBrandAudience();
        roleRegistry.synchronize();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN);
    }

    @Test
    void anAdministratorCannotResumeACampaign() throws Exception {
        UUID campaignId = pausedCampaign();

        MvcResult refused = mvc.perform(post(resumePath(campaignId))
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "resume-refused-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonBody("Trying anyway")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.CAMPAIGN_APPROVE.code());
        assertThat(statusOf(campaignId))
                .as("a refused resume must leave the campaign exactly where it was")
                .isEqualTo("PAUSED");
    }

    @Test
    void anOwnerResumesAPausedCampaignAndTheResponseReportsWhatThePauseCost() throws Exception {
        UUID campaignId = pausedCampaign();

        MvcResult resumed = mvc.perform(post(resumePath(campaignId))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "resume-ok-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonBody("Investigated; template was not spam")))
                .andReturn();

        assertThat(resumed.getResponse().getStatus()).isEqualTo(200);
        assertThat(resumed.getResponse().getContentAsString())
                .as("no notification was ever created against this fixture, so the pause cost nothing")
                .contains("\"suppressedDuringPause\":0");
        assertThat(statusOf(campaignId)).isEqualTo("SENDING");
        assertThat(blockedCountOf(campaignId)).isZero();

        List<Map<String, Object>> auditRows =
                jdbc.sql("""
                SELECT change_document FROM audit.audit_events
                WHERE action_code = 'MARKETING_CAMPAIGN_RESUMED' AND actor_subject = :subject
                """).param("subject", OWNER).query().listOfRows();
        assertThat(auditRows)
                .as("ADR 0027: the resume itself is an audited fact")
                .hasSize(1);
    }

    @Test
    void resumingACampaignThatIsNotPausedIsARefusedConflict() throws Exception {
        UUID campaignId = draftCampaign();

        MvcResult conflict = mvc.perform(post(resumePath(campaignId))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "resume-conflict-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reasonBody("Nothing to resume")))
                .andReturn();

        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(conflict.getResponse().getContentAsString()).contains("RESOURCE_CONFLICT");
        assertThat(statusOf(campaignId)).isEqualTo("DRAFT");
    }

    // ------------------------------------------------------------------ fixtures

    private static String resumePath(UUID campaignId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/marketing/campaigns/" + campaignId + "/resumptions";
    }

    private static String reasonBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    /** A campaign built straight in SQL, PAUSED as if the block-rate guard had just stopped it. */
    private UUID pausedCampaign() {
        UUID id = insertCampaign();
        jdbc.sql("""
                UPDATE marketing.campaigns
                   SET status = 'PAUSED', blocked_count = 2, paused_at = :now,
                       halted_reason = 'Block-rate guard: test fixture'
                 WHERE id = :id
                """)
                .param("id", id)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        return id;
    }

    private UUID draftCampaign() {
        return insertCampaign();
    }

    private UUID insertCampaign() {
        UUID id = UUID.randomUUID();
        UUID snapshotId = insertAudienceSnapshot();
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
                .param("brandId", BRAND)
                .param("name", "Resume endpoint fixture " + id)
                .param("audienceId", AUDIENCE)
                .param("snapshotId", snapshotId)
                .param("createdBy", UUID.randomUUID())
                .update();
        return id;
    }

    /** {@code ck_campaign_snapshot_before_send}: PAUSED needs one on file, same as SENDING. */
    private UUID insertAudienceSnapshot() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO marketing.audience_snapshots (
                    id, tenant_id, brand_id, audience_id, definition_version, channel, consent_purpose,
                    status, metric_definition_version, built_by, built_at, completed_at)
                VALUES (:id, :tenantId, :brandId, :audienceId, 1, 'SMS', 'MARKETING_PROMOTIONS',
                    'READY', 1, :builtBy, now(), now())
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("audienceId", AUDIENCE)
                .param("builtBy", UUID.randomUUID())
                .update();
        return id;
    }

    private String statusOf(UUID campaignId) {
        return jdbc.sql("SELECT status FROM marketing.campaigns WHERE id = :id")
                .param("id", campaignId)
                .query(String.class)
                .single();
    }

    private int blockedCountOf(UUID campaignId) {
        return jdbc.sql("SELECT blocked_count FROM marketing.campaigns WHERE id = :id")
                .param("id", campaignId)
                .query(Integer.class)
                .single();
    }

    private void seedTenantBrandAudience() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'campaign-resume-endpoint', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
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
                        'ACTIVE', 'test-fixture', 'campaign resume endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(StandardCharsets.UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                // Backdated rather than the column's own now(): a grant read
                // back through JdbcAuthorizationService.grantsFor compares
                // valid_from against this JVM's Clock.systemUTC(), and under
                // heavy concurrent fork load the container's own wall clock can
                // momentarily skew against it.
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
