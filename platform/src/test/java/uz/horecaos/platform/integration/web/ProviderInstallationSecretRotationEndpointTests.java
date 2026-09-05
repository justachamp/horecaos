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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.integration.camel.notification.telegram.FakeTelegramBotApi;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The HTTP surface for wave 13's rotate-secret endpoint (ADR 0028, ADR 0027):
 * docs/runbooks/sendpulse-cutover.md step 9's own named gap. Against a real
 * {@link FakeTelegramBotApi}, the same fake {@code CampaignBroadcastIntegrationTest}
 * drives through the real {@code TelegramBotApiClient} — this class reaches it
 * through {@code ProviderInstallationController} instead, which is the point:
 * the endpoint must verify the new reference for real before it ever touches
 * the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderInstallationSecretRotationEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000e1");
    private static final UUID INSTALLATION = UUID.fromString("018f9b20-4000-7000-8000-0000000000e2");

    private static final String OWNER = "secret-rotation-owner";
    private static final String FINANCE = "secret-rotation-finance";

    /** Registered via {@link #properties} so the resolver answers a real value for it. */
    private static final String RESOLVABLE_REFERENCE = "horecaos:local:provider_notification:platform:telegram-rotated";

    private static final String UNRESOLVABLE_REFERENCE =
            "horecaos:local:provider_notification:platform:telegram-missing";

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
        registry.add("horecaos.secrets.provider_notification.platform.telegram-rotated", () -> "the-rotated-bot-token");
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
        bot.setBotUsername("HorecaOsPilotBot");

        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedProviderEnvironment();
        insertTenantAndInstallation("horecaos:local:provider_notification:platform:telegram-original");
        roleRegistry.synchronize();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        // TENANT_ADMIN holds INTEGRATION_INSTALLATION_MANAGE alongside the
        // owner, so TENANT_FINANCE is the role that actually proves the
        // refusal: it holds real tenant-wide authority (approvals, refunds,
        // fiscal reads) and still does not reach this endpoint.
        grant(FINANCE, PlatformRole.TENANT_FINANCE);
    }

    @AfterEach
    void tearDown() {
        if (bot != null) {
            bot.close();
        }
    }

    @Test
    void anOwnerRotatesToAReferenceThatResolvesAndPassesGetMe() throws Exception {
        MvcResult result = mvc.perform(post(rotatePath())
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "rotate-ok-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateRequest(RESOLVABLE_REFERENCE, "BotFather rotation, step 9")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"newSecretReference\":\"" + RESOLVABLE_REFERENCE + "\"")
                .contains("\"botUsername\":\"HorecaOsPilotBot\"")
                .as("the token itself must never appear in the response")
                .doesNotContain("the-rotated-bot-token");

        assertThat(secretReferenceOf(INSTALLATION)).isEqualTo(RESOLVABLE_REFERENCE);

        List<Map<String, Object>> auditRows =
                jdbc.sql("""
                SELECT change_document FROM audit.audit_events
                WHERE action_code = 'integration.installation_secret_rotated' AND actor_subject = :subject
                """).param("subject", OWNER).query().listOfRows();
        assertThat(auditRows)
                .as("ADR 0027: who rotated which installation is an audited fact")
                .hasSize(1);
        String changeDocument = String.valueOf(auditRows.getFirst().get("change_document"));
        assertThat(changeDocument)
                .as("reference NAMES only — the audit row must never carry the token value either")
                .contains("telegram-original")
                .contains("telegram-rotated")
                .doesNotContain("the-rotated-bot-token");
    }

    @Test
    void aReferenceThatDoesNotResolveChangesNothing() throws Exception {
        MvcResult result = mvc.perform(post(rotatePath())
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "rotate-unresolvable-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateRequest(UNRESOLVABLE_REFERENCE, "Typo'd the reference")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("UNPROCESSABLE_STATE");
        assertThat(secretReferenceOf(INSTALLATION))
                .as("a reference that does not resolve must leave the installation exactly as it was")
                .isEqualTo("horecaos:local:provider_notification:platform:telegram-original");
    }

    @Test
    void aTokenTelegramRejectsChangesNothing() throws Exception {
        bot.revokeToken();

        MvcResult result = mvc.perform(post(rotatePath())
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "rotate-rejected-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateRequest(RESOLVABLE_REFERENCE, "Testing a bad token")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("UNPROCESSABLE_STATE");
        assertThat(secretReferenceOf(INSTALLATION))
                .as("Telegram refusing the new token must leave the installation exactly as it was")
                .isEqualTo("horecaos:local:provider_notification:platform:telegram-original");
    }

    @Test
    void tenantFinanceCannotRotateAnInstallationsSecret() throws Exception {
        MvcResult refused = mvc.perform(post(rotatePath())
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "rotate-refused-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateRequest(RESOLVABLE_REFERENCE, "Trying anyway")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.INTEGRATION_INSTALLATION_MANAGE.code());
        assertThat(secretReferenceOf(INSTALLATION))
                .isEqualTo("horecaos:local:provider_notification:platform:telegram-original");
    }

    // ------------------------------------------------------------------ fixtures

    private static String rotatePath() {
        return "/api/v1/control-plane/tenants/" + TENANT + "/integrations/" + INSTALLATION + "/secret-rotations";
    }

    private static String rotateRequest(String newSecretReference, String reason) {
        return "{\"newSecretReference\":\"" + newSecretReference + "\",\"reason\":\"" + reason + "\"}";
    }

    private String secretReferenceOf(UUID installationId) {
        return jdbc.sql("SELECT secret_reference FROM integration.installations WHERE id = :id")
                .param("id", installationId)
                .query(String.class)
                .single();
    }

    private void seedProviderEnvironment() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('rotation-endpoint-env', 'NOTIFICATION', 'TELEGRAM_BOT_API', :baseUrl, false, '127.0.0.1')
                ON CONFLICT DO NOTHING
                """).param("baseUrl", bot.baseUrl()).update();
    }

    private void insertTenantAndInstallation(String secretReference) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'secret-rotation-endpoint', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'rotation-endpoint-env',
                        'Pilot bot', 'ACTIVE', :secretReference, :secretReference)
                """)
                .param("id", INSTALLATION)
                .param("tenantId", TENANT)
                .param("secretReference", secretReference)
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'secret rotation endpoint test', :validFrom)
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
                // momentarily skew against it (see the value-rotation sibling).
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
