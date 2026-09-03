package uz.horecaos.platform.marketing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The HTTP surface for wave 37's read-model additions (ADR 0044): defining
 * and listing audiences, creating and listing campaigns, and listing/lifting
 * suppressions — the gap {@code OperationsMarketingController}'s own doc on
 * {@link OperationsMarketingResumeEndpointTests} names: every lifecycle
 * endpoint already assumed an audience or campaign id in hand, and nothing
 * could list one back or define a new one.
 *
 * <p>Capability grants are split the same way production roles are:
 * {@code TENANT_ADMIN} holds {@code campaign.author}/{@code audience.read}
 * (authoring and reading targeting), {@code TENANT_OWNER} holds {@code
 * suppression.manage} (lifting a suppression, deliberately away from
 * whoever writes the campaign) — the same asymmetric split {@link
 * OperationsMarketingResumeEndpointTests} already exercises for {@code
 * campaign.approve}. State-machine proofs (estimate, approve, send) are
 * {@code MarketingCampaignTests}'/that class's job; this class proves the
 * controller's own authorization, request/response shape, and cross-brand
 * isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsMarketingCrudEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000e1");
    private static final UUID BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000e2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000e3");
    private static final UUID SEED_AUDIENCE = UUID.fromString("018f9b20-4000-7000-8000-0000000000e4");

    // UUID-shaped, not a slug: OperationsMarketingController#actorId parses the
    // JWT subject as a UUID for campaign.author/campaign.approve actions
    // (createdBy/approvedBy/liftedBy), so a token subject has to look like a
    // real Keycloak subject here, unlike OperationsMarketingResumeEndpointTests'
    // own OWNER/ADMINISTRATOR, whose test never exercises an endpoint that
    // records an actor id.
    private static final String OWNER = "018f9b20-4000-7000-8000-0000000000f1";
    private static final String ADMINISTRATOR = "018f9b20-4000-7000-8000-0000000000f2";

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
                        + "marketing.campaigns, marketing.suppressions, marketing.audience_snapshots, "
                        + "marketing.audience_predicates, marketing.audiences CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedFixtures();
        roleRegistry.synchronize();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN);
    }

    // -------------------------------------------------------------- audiences

    @Test
    void definingAnAudienceReturnsItsPredicatesAndListsItBack() throws Exception {
        String body = """
                {"name":"Recent orderers","description":"Ordered in the last 30 days",
                 "predicates":[{"type":"RECENCY_DAYS","operator":"AT_MOST","numericLow":30}]}
                """;

        MvcResult created = mvc.perform(post(audiencesPath())
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "define-audience-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String responseBody = created.getResponse().getContentAsString();
        assertThat(responseBody).contains("\"name\":\"Recent orderers\"").contains("RECENCY_DAYS");

        MvcResult listed =
                mvc.perform(get(audiencesPath()).with(tokenFor(ADMINISTRATOR))).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(listed.getResponse().getContentAsString())
                .contains("Recent orderers")
                // The seed audience from a sibling brand must not leak in.
                .doesNotContain("Other brand seed");
    }

    @Test
    void anOwnerCannotDefineAnAudience() throws Exception {
        String body = """
                {"name":"Refused","predicates":[{"type":"ORDER_COUNT","operator":"AT_LEAST","numericLow":1}]}
                """;

        MvcResult refused = mvc.perform(post(audiencesPath())
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "define-audience-refused-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");
    }

    @Test
    void redefiningAnAudienceBumpsItsDefinitionVersion() throws Exception {
        String body = """
                {"predicates":[{"type":"PREFERRED_LOCALE","operator":"IN","textValues":["ru","uz-Latn"]}]}
                """;

        MvcResult redefined = mvc.perform(put(audiencesPath() + "/" + SEED_AUDIENCE + "/predicates")
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "redefine-audience-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(redefined.getResponse().getStatus()).isEqualTo(200);
        assertThat(redefined.getResponse().getContentAsString())
                .contains("\"definitionVersion\":2")
                .contains("PREFERRED_LOCALE");
    }

    // -------------------------------------------------------------- campaigns

    @Test
    void creatingACampaignReturnsADraftAndListsItBack() throws Exception {
        String body = """
                {"name":"Autumn promotion","channel":"SMS","consentPurpose":"MARKETING_PROMOTIONS",
                 "audienceId":"%s","templateKey":"MARKETING_PROMOTION","recipientCap":1000,
                 "costCeilingMinor":100000,"currency":"UZS"}
                """.formatted(SEED_AUDIENCE);

        MvcResult created = mvc.perform(post(campaignsPath())
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "create-campaign-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getContentAsString())
                .contains("\"name\":\"Autumn promotion\"")
                .contains("\"status\":\"DRAFT\"");

        MvcResult listed =
                mvc.perform(get(campaignsPath()).with(tokenFor(ADMINISTRATOR))).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(listed.getResponse().getContentAsString()).contains("Autumn promotion");
    }

    @Test
    void readingACampaignFromASiblingBrandIsNotFound() throws Exception {
        UUID campaignId = draftCampaignInBrand(BRAND);

        MvcResult notFound = mvc.perform(get("/api/v1/tenants/" + TENANT + "/brands/" + OTHER_BRAND
                                + "/marketing/campaigns/" + campaignId)
                        .with(tokenFor(ADMINISTRATOR)))
                .andReturn();

        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(notFound.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    @Test
    void readingACampaignFromItsOwnBrandReturnsTheFullLifecycleState() throws Exception {
        UUID campaignId = draftCampaignInBrand(BRAND);

        MvcResult read = mvc.perform(get(campaignsPath() + "/" + campaignId).with(tokenFor(ADMINISTRATOR)))
                .andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(read.getResponse().getContentAsString())
                .contains("\"status\":\"DRAFT\"")
                .contains("\"blockedCount\":0");
    }

    // ------------------------------------------------------------ suppression

    @Test
    void listingSuppressionsAndLiftingOne() throws Exception {
        UUID customerAccountId = seedCustomer();
        UUID suppressionId = seedSuppression(customerAccountId);

        MvcResult listed =
                mvc.perform(get(suppressionsPath()).with(tokenFor(OWNER))).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(listed.getResponse().getContentAsString())
                .contains("HARD_BOUNCE")
                .contains(suppressionId.toString());

        MvcResult lifted = mvc.perform(post(suppressionsPath() + "/" + suppressionId + "/lifts")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "lift-suppression-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"False positive\"}"))
                .andReturn();

        assertThat(lifted.getResponse().getStatus()).isEqualTo(200);
        assertThat(lifted.getResponse().getContentAsString()).contains("\"lifted\":true");
    }

    @Test
    void anAdministratorCannotManageSuppressions() throws Exception {
        MvcResult refused = mvc.perform(get(suppressionsPath()).with(tokenFor(ADMINISTRATOR)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");
    }

    // ------------------------------------------------------------------ fixtures

    private static String audiencesPath() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/marketing/audiences";
    }

    private static String campaignsPath() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/marketing/campaigns";
    }

    private static String suppressionsPath() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/marketing/suppressions";
    }

    private UUID draftCampaignInBrand(UUID brandId) {
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
                .param("audienceId", SEED_AUDIENCE)
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
                .param("name", "CRUD endpoint fixture " + id)
                .param("audienceId", SEED_AUDIENCE)
                .param("snapshotId", snapshotId)
                .param("createdBy", UUID.randomUUID())
                .update();
        return id;
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, preferred_locale, created_at)
                VALUES (:id, :tenantId, 'ACTIVE', 'ru', now())
                """).param("id", id).param("tenantId", TENANT).update();
        return id;
    }

    private UUID seedSuppression(UUID customerAccountId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO marketing.suppressions (
                    id, tenant_id, brand_id, customer_account_id, channel, reason,
                    applied_by_type, stated_reason, applied_at, created_at)
                VALUES (:id, :tenantId, :brandId, :accountId, 'SMS', 'HARD_BOUNCE',
                    'PROVIDER', 'Invalid number', now(), now())
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("accountId", customerAccountId)
                .update();
        return id;
    }

    private void seedFixtures() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'marketing-crud-endpoint', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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
                .param("id", SEED_AUDIENCE)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("createdBy", UUID.randomUUID())
                .update();
        jdbc.sql("""
                INSERT INTO marketing.audiences (id, tenant_id, brand_id, name, created_by)
                VALUES (:id, :tenantId, :brandId, 'Other brand seed', :createdBy)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", OTHER_BRAND)
                .param("createdBy", UUID.randomUUID())
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'marketing crud endpoint test')
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(StandardCharsets.UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
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
