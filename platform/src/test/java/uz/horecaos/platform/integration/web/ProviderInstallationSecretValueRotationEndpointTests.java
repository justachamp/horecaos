package uz.horecaos.platform.integration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import uz.horecaos.platform.integration.camel.notification.telegram.FakeTelegramBotApi;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0065's generalization of wave 13's rotate endpoint: a tenant with no way
 * to write a reference by hand submits the VALUE directly. {@link
 * ProviderInstallationSecretRotationEndpointTests} is this class's sibling and
 * covers the unchanged reference-only endpoint; this class is the new one.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderInstallationSecretValueRotationEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-6000-7000-8000-0000000000e1");
    private static final UUID TELEGRAM_INSTALLATION = UUID.fromString("018f9b20-6000-7000-8000-0000000000e2");
    private static final UUID CLICK_INSTALLATION = UUID.fromString("018f9b20-6000-7000-8000-0000000000e3");

    private static final String OWNER = "value-rotation-owner";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private FakeTelegramBotApi bot;

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
    void setUp() throws IOException {
        bot = FakeTelegramBotApi.start();
        bot.setBotUsername("HorecaOsValueRotationBot");

        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenant();
        seedTelegramEnvironmentAndInstallation();
        seedClickEnvironmentAndInstallation();
        roleRegistry.synchronize();
        grant(OWNER, PlatformRole.TENANT_OWNER);
    }

    @AfterEach
    void tearDown() {
        if (bot != null) {
            bot.close();
        }
    }

    @Test
    void aNewTokenIsVerifiedBeforeItIsEverWrittenAnywhere() throws Exception {
        MvcResult result = mvc.perform(post(valueRotatePath(TELEGRAM_INSTALLATION))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "value-rotate-ok-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateRequest("a-brand-new-bot-token", "Rotating through the door")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"botUsername\":\"HorecaOsValueRotationBot\"")
                .as("the value itself must never appear in the response")
                .doesNotContain("a-brand-new-bot-token");

        String newReference = referenceFrom(installationRow(TELEGRAM_INSTALLATION), "secret_reference");
        assertThat(newReference)
                .as("the door mints a fresh reference; it is never the tenant's own string")
                .startsWith("horecaos:")
                .contains("provider_notification");
        assertThat(installationRow(TELEGRAM_INSTALLATION)).containsEntry("last_connection_status", "SUCCEEDED");
        assertThat(installationRow(TELEGRAM_INSTALLATION).get("last_secret_rotated_at"))
                .isNotNull();
    }

    @Test
    void aTokenTelegramRejectsIsNeverWrittenAndChangesNothing() throws Exception {
        bot.revokeToken();
        String originalReference =
                String.valueOf(installationRow(TELEGRAM_INSTALLATION).get("secret_reference"));

        MvcResult result = mvc.perform(post(valueRotatePath(TELEGRAM_INSTALLATION))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "value-rotate-rejected-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateRequest("a-token-telegram-will-refuse", "Testing a bad token")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("UNPROCESSABLE_STATE");
        assertThat(installationRow(TELEGRAM_INSTALLATION))
                .as("a value Telegram refuses must never be written through the door at all")
                .containsEntry("secret_reference", originalReference);
    }

    @Test
    void aProviderWithNoHarmlessCallIsWrittenButLeftUnverified() throws Exception {
        MvcResult result = mvc.perform(post(valueRotatePath(CLICK_INSTALLATION))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "value-rotate-click-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateRequest("a-new-click-secret-key", "Click gave us a new key")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("a-new-click-secret-key");
        assertThat(installationRow(CLICK_INSTALLATION))
                .as("no harmless authenticated call exists for Click, so this is written honestly as unverified")
                .containsEntry("last_connection_status", "UNVERIFIED");
    }

    // ------------------------------------------------------------------ fixtures

    private static String valueRotatePath(UUID installationId) {
        return "/api/v1/control-plane/tenants/" + TENANT + "/integrations/" + installationId
                + "/secret-rotations/value";
    }

    private static String rotateRequest(String value, String reason) {
        return "{\"value\":\"" + value + "\",\"reason\":\"" + reason + "\"}";
    }

    private static String referenceFrom(Map<String, Object> row, String column) {
        return String.valueOf(row.get(column));
    }

    private Map<String, Object> installationRow(UUID installationId) {
        return jdbc.sql("""
                SELECT secret_reference, last_connection_status, last_secret_rotated_at
                  FROM integration.installations WHERE id = :id
                """).param("id", installationId).query().listOfRows().getFirst();
    }

    private void seedTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'value-rotation-endpoint', 'Value Rotation', 'Value Rotation',
                        'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    private void seedTelegramEnvironmentAndInstallation() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('value-rotation-telegram-env', 'NOTIFICATION', 'TELEGRAM_BOT_API', :baseUrl, false, '127.0.0.1')
                ON CONFLICT DO NOTHING
                """).param("baseUrl", bot.baseUrl()).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'value-rotation-telegram-env',
                        'Pilot bot', 'ACTIVE', 'horecaos:local:provider_notification:tenant-original:telegram')
                """)
                .param("id", TELEGRAM_INSTALLATION)
                .param("tenantId", TENANT)
                .update();
    }

    private void seedClickEnvironmentAndInstallation() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('value-rotation-click-env', 'PAYMENT', 'CLICK', 'https://api.click.uz/v2/merchant',
                        false, 'api.click.uz')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'PAYMENT', 'CLICK', 'value-rotation-click-env',
                        'Click', 'ACTIVE', 'horecaos:local:provider_payment:tenant-original:click')
                """).param("id", CLICK_INSTALLATION).param("tenantId", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'value rotation endpoint test', :validFrom)
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
                // momentarily skew against it — the suspected cause of this
                // class's rare "expected 200, got 403" flake under
                // -Dhorecaos.test.forks>1 (wave 54).
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
