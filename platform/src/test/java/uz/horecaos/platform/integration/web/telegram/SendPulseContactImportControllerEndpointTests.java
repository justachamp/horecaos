package uz.horecaos.platform.integration.web.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * The HTTP surface for the SendPulse contact import (ADR 0059 stage 3): the
 * capability refusal, the dry-run/real-run distinction, and the ADR 0027
 * audit fact — the same shape {@code MerchantBindingControllerEndpointTests}
 * proves for {@code PAYMENT_MERCHANT_BINDING_MANAGE}, applied to {@link
 * Capability#CUSTOMER_IMPORT}: a tenant administrator holds most of this
 * tenant's authority and must still be refused, because {@code
 * CUSTOMER_IMPORT} is held by {@code tenant-owner} alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SendPulseContactImportControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000b1");
    private static final UUID INSTALLATION = UUID.fromString("018f9b20-4000-7000-8000-0000000000c1");

    private static final String OWNER = "sendpulse-import-owner";
    private static final String ADMINISTRATOR = "sendpulse-import-administrator";

    private static final String IMPORTS = "/api/v1/control-plane/tenants/" + TENANT + "/sendpulse-imports";

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
        // ADR 0029: the import matches and creates customer.contact_points
        // rows, which EnvelopeFieldProtection encrypts and hashes — the
        // default Spring context carries no kek at all outside the "local"
        // profile, unlike MerchantBindingControllerEndpointTests' own tables.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
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
        // customer.customer_accounts carries no foreign key back to
        // tenant.tenants at all (ADR 0015 never gave it one), so — unlike
        // MerchantBindingControllerEndpointTests' own tables — nothing below
        // reaches it through "TRUNCATE tenant.tenants CASCADE" alone. Each
        // test method reuses the same fixed TENANT/INSTALLATION ids, so this
        // has to be explicit or a later test inherits an earlier one's rows.
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions, customer.contact_points, "
                        + "customer.principal_links, customer.brand_profiles, customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.sendpulse_import_run_rows, integration.sendpulse_import_runs CASCADE")
                .update();
        // CASCADE reaches iam.roles, integration.installations/bindings, and
        // everything else that does carry a tenant_id foreign key back to
        // tenant.tenants.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedProviderEnvironment();
        roleRegistry.synchronize();
        insertTenantBrandAndInstallation();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN);
    }

    @Test
    void anOwnerDryRunsThenReallyImportsAndTheAuditFactNamesBoth() throws Exception {
        MvcResult dryRun = mvc.perform(post(IMPORTS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "sendpulse-dry-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importRequest("ep-dry.csv")))
                .andReturn();

        assertThat(dryRun.getResponse().getStatus()).isEqualTo(200);
        String dryRunBody = dryRun.getResponse().getContentAsString();
        assertThat(dryRunBody)
                .contains("\"dryRun\":true")
                .contains("\"total\":1")
                .contains("\"createdCustomer\":1");
        assertThat(customerAccountCount())
                .as("a dry run — even the default, with no dryRun param at all — writes no customer")
                .isZero();

        MvcResult real = mvc.perform(post(IMPORTS)
                        .queryParam("dryRun", "false")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "sendpulse-real-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importRequest("ep-real.csv")))
                .andReturn();

        assertThat(real.getResponse().getStatus()).isEqualTo(200);
        String realBody = real.getResponse().getContentAsString();
        assertThat(realBody).contains("\"dryRun\":false").contains("\"createdCustomer\":1");
        assertThat(customerAccountCount()).isEqualTo(1);
        assertThat(telegramBindingCount()).isEqualTo(1);

        List<Map<String, Object>> auditRows =
                jdbc.sql("""
                SELECT change_document FROM audit.audit_events
                WHERE action_code = 'integration.sendpulse_import_run' AND actor_subject = :subject
                ORDER BY occurred_at
                """).param("subject", OWNER).query().listOfRows();
        assertThat(auditRows)
                .as("ADR 0027: one audit fact per run — dry and real both leave a trail")
                .hasSize(2);

        UUID runId = extractRunId(real.getResponse().getContentAsString());
        MvcResult report =
                mvc.perform(get(IMPORTS + "/" + runId).with(tokenFor(OWNER))).andReturn();
        assertThat(report.getResponse().getStatus()).isEqualTo(200);
        assertThat(report.getResponse().getContentAsString()).contains("\"createdCustomer\":1");
    }

    @Test
    void anAdministratorCannotImportEvenAsADryRun() throws Exception {
        MvcResult refused = mvc.perform(post(IMPORTS)
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "sendpulse-refused-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importRequest("ep-refused.csv")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.CUSTOMER_IMPORT.code());
        assertThat(customerAccountCount())
                .as("a refused caller must leave the tenant's customer base exactly as it was")
                .isZero();
    }

    @Test
    void importingWithoutAnIdempotencyKeyIsRejected() throws Exception {
        MvcResult result = mvc.perform(post(IMPORTS)
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importRequest("ep-nokey.csv")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void aMalformedJsonBodyIsAProblemDetailsValidationFailureNotA500() throws Exception {
        String body = """
                {"installationId":"%s","format":"JSON","fileName":"broken.json","content":"not a json array"}
                """.formatted(INSTALLATION);

        MvcResult result = mvc.perform(post(IMPORTS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "sendpulse-malformed-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
        assertThat(customerAccountCount()).isZero();
    }

    @Test
    void anUnknownInstallationIsNotFoundNotA500() throws Exception {
        String body = """
                {"installationId":"%s","format":"CSV","fileName":"x.csv",
                 "content":"chat_id,status\\n1,subscribed\\n"}
                """.formatted(UUID.randomUUID());

        MvcResult result = mvc.perform(post(IMPORTS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "sendpulse-unknown-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    // ------------------------------------------------------------------ fixtures

    private static String importRequest(String fileName) {
        return """
                {"installationId":"%s","format":"CSV","fileName":"%s",
                 "content":"chat_id,phone,status\\n700000001,+998901112301,subscribed\\n"}
                """.formatted(INSTALLATION, fileName);
    }

    private static UUID extractRunId(String responseBody) {
        int start = responseBody.indexOf("\"runId\":\"") + "\"runId\":\"".length();
        int end = responseBody.indexOf('"', start);
        return UUID.fromString(responseBody.substring(start, end));
    }

    private long customerAccountCount() {
        return jdbc.sql("SELECT count(*) FROM customer.customer_accounts WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(Long.class)
                .single();
    }

    private long telegramBindingCount() {
        return jdbc.sql("""
                SELECT count(*) FROM integration.telegram_bindings WHERE tenant_id = :tenantId AND retired_at IS NULL
                """).param("tenantId", TENANT).query(Long.class).single();
    }

    private void seedProviderEnvironment() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('sendpulse-endpoint-env', 'NOTIFICATION', 'TELEGRAM_BOT_API',
                    'http://127.0.0.1:1', false, '127.0.0.1')
                ON CONFLICT DO NOTHING
                """).update();
    }

    private void insertTenantBrandAndInstallation() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'sendpulse-import-endpoint', 'SendPulse Import', 'SendPulse Import',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, brand_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, :brandId, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'sendpulse-endpoint-env',
                    'Endpoint test bot', 'ACTIVE', 'horecaos:test:provider_notification:telegram-bot',
                    'horecaos:test:provider_notification:telegram-bot')
                """)
                .param("id", INSTALLATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'sendpulse import endpoint test', :validFrom)
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
