package uz.horecaos.platform.marketing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.http.HttpHeaders;
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
 * The HTTP surface for {@link AutomationRuleController} (gap-map row 6.5,
 * ADR 0044 Triggers).
 *
 * <p>Two capabilities, split the same asymmetric way {@code
 * OperationsMarketingCrudEndpointTests}/{@code
 * OperationsMarketingResumeEndpointTests} already exercise for campaigns:
 * {@code TENANT_ADMIN} holds {@code campaign.author} (create/update/reorder/
 * list/runs), {@code TENANT_OWNER} holds {@code campaign.approve} (activate/
 * deactivate) — proving "nothing sends without a human" is enforced by the
 * server, not only shown as two acts in the console.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutomationRuleControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000c1");
    private static final UUID BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000c2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000c3");

    private static final String OWNER = "018f9b20-4000-7000-8000-0000000000d1";
    private static final String ADMINISTRATOR = "018f9b20-4000-7000-8000-0000000000d2";

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
        jdbc.sql("TRUNCATE TABLE marketing.automation_runs, marketing.automation_rules CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedFixtures();
        roleRegistry.synchronize();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN);
    }

    @Test
    void creatingARuleReturnsItInertAndListsItBack() throws Exception {
        MvcResult created = mvc.perform(post(automationsPath())
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "create-automation-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthdayRuleBody("Birthday treat")))
                .andReturn();

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String body = created.getResponse().getContentAsString();
        assertThat(body).contains("\"name\":\"Birthday treat\"").contains("\"active\":false");

        MvcResult listed = mvc.perform(get(automationsPath()).with(tokenFor(ADMINISTRATOR)))
                .andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(listed.getResponse().getContentAsString()).contains("Birthday treat");
    }

    @Test
    void anOwnerCannotAuthorARule() throws Exception {
        MvcResult refused = mvc.perform(post(automationsPath())
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "create-automation-refused-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthdayRuleBody("Refused")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");
    }

    @Test
    void anAdministratorCannotArmARuleTheyAuthored() throws Exception {
        UUID ruleId = createRule("Birthday treat");

        MvcResult refused = mvc.perform(post(automationsPath() + "/" + ruleId + "/activations")
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-refused-1")
                        .header(HttpHeaders.IF_MATCH, "W/\"1\""))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");
    }

    @Test
    void anOwnerArmsAnAuthoredRule() throws Exception {
        UUID ruleId = createRule("Birthday treat");

        MvcResult activated = mvc.perform(post(automationsPath() + "/" + ruleId + "/activations")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-1")
                        .header(HttpHeaders.IF_MATCH, "W/\"1\""))
                .andReturn();

        assertThat(activated.getResponse().getStatus()).isEqualTo(200);
        assertThat(activated.getResponse().getContentAsString())
                .contains("\"active\":true")
                .contains("\"activatedBy\":\"" + OWNER + "\"");
    }

    @Test
    void armingAnUnwiredChannelIsRefusedWithAReason() throws Exception {
        UUID ruleId = createRuleWithChannel("SMS rule", "SMS");

        MvcResult refused = mvc.perform(post(automationsPath() + "/" + ruleId + "/activations")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-unwired-1")
                        .header(HttpHeaders.IF_MATCH, "W/\"1\""))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("SMS");
    }

    @Test
    void reorderRewritesPriorityForTheWholeSet() throws Exception {
        UUID first = createRule("A");
        UUID second = createRule("B");

        MvcResult reordered = mvc.perform(put(automationsPath() + "/reorder")
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reorder-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedRuleIds\":[\"%s\",\"%s\"]}".formatted(second, first)))
                .andReturn();

        assertThat(reordered.getResponse().getStatus()).isEqualTo(200);
        String body = reordered.getResponse().getContentAsString();
        // B (second) now leads A (first) in priority order.
        assertThat(body.indexOf("\"id\":\"" + second)).isLessThan(body.indexOf("\"id\":\"" + first));
    }

    @Test
    void aRuleFromASiblingBrandIsNotFound() throws Exception {
        UUID ruleId = createRule("Birthday treat");

        MvcResult notFound = mvc.perform(get("/api/v1/tenants/" + TENANT + "/brands/" + OTHER_BRAND
                                + "/marketing/automations/" + ruleId + "/runs")
                        .with(tokenFor(ADMINISTRATOR)))
                .andReturn();

        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(notFound.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    // ----------------------------------------------------------------- helpers

    private UUID createRule(String name) throws Exception {
        return createRuleWithChannel(name, "MESSAGING_APP");
    }

    private UUID createRuleWithChannel(String name, String channel) throws Exception {
        MvcResult created = mvc.perform(post(automationsPath())
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "create-" + name + "-" + channel)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(birthdayRuleBodyWithChannel(name, channel)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String body = created.getResponse().getContentAsString();
        String marker = "\"id\":\"";
        int start = body.indexOf(marker) + marker.length();
        return UUID.fromString(body.substring(start, body.indexOf('"', start)));
    }

    private static String birthdayRuleBody(String name) {
        return birthdayRuleBodyWithChannel(name, "MESSAGING_APP");
    }

    private static String birthdayRuleBodyWithChannel(String name, String channel) {
        return """
                {"name":"%s","triggerType":"BIRTHDAY","channel":"%s","consentPurpose":"MARKETING_PROMOTIONS",
                 "templateKey":"AUTOMATION_BIRTHDAY","triggerConfig":{"birthdayWindowDays":0},"cooldownDays":365}
                """.formatted(name, channel);
    }

    private String automationsPath() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/marketing/automations";
    }

    private void seedFixtures() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'automation-endpoint', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'OTHER', 'other', 'Other brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'automation endpoint test', :validFrom)
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
