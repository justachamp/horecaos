package uz.horecaos.platform.integration.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * Wave 53: {@link ProviderInstallationController} and {@link SecretIngressController}'s logic,
 * reachable at the operations-prefixed mirror the operations app actually calls
 * ({@link OperationsProviderInstallationController}, {@link OperationsSecretIngressController}),
 * and proof the original {@code /api/v1/control-plane/...} paths still answer.
 *
 * <p>Three things this class exists to prove, none of which follows from the existing
 * control-plane-prefixed suites passing: a tenant-scoped actor can actually reach the new
 * paths (the point of the move), a principal without the capability is refused there by name
 * (a capability check is a property of the URL Spring dispatches to, not of the class that
 * happens to implement it), and the write-only door's no-leak guarantee — response, audit
 * fact, and every captured log line — survives being reached through a forwarding controller
 * rather than assumed to carry over unexamined.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsProviderIntegrationsEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-7000-7000-8000-0000000000f1");
    private static final UUID TELEGRAM_INSTALLATION = UUID.fromString("018f9b20-7000-7000-8000-0000000000f2");

    private static final String OWNER = "operations-surface-owner";
    private static final String FINANCE = "operations-surface-finance";

    private static final String NEW_INTEGRATIONS = "/api/v1/operations/tenants/" + TENANT + "/integrations";
    private static final String NEW_SECRETS = NEW_INTEGRATIONS + "/secrets";
    private static final String OLD_INTEGRATIONS = "/api/v1/control-plane/tenants/" + TENANT + "/integrations";
    private static final String OLD_SECRETS = OLD_INTEGRATIONS + "/secrets";

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
        bot.setBotUsername("HorecaOsOperationsSurfaceBot");

        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenant();
        seedTelegramEnvironmentAndInstallation();
        roleRegistry.synchronize();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        // TENANT_ADMIN holds the same capabilities as the owner here, so
        // TENANT_FINANCE is the fixture that actually proves the refusal — the
        // same reasoning ProviderInstallationSecretRotationEndpointTests uses.
        grant(FINANCE, PlatformRole.TENANT_FINANCE);
    }

    @AfterEach
    void tearDown() {
        if (bot != null) {
            bot.close();
        }
    }

    @Test
    void aTenantOwnerReadsConnectFieldsAndInstallationsOnTheOperationsSurface() throws Exception {
        MvcResult connectFields = mvc.perform(
                        get(NEW_INTEGRATIONS + "/connect-fields").with(tokenFor(OWNER)))
                .andReturn();
        assertThat(connectFields.getResponse().getStatus()).isEqualTo(200);
        assertThat(connectFields.getResponse().getContentAsString()).contains("TELEGRAM_BOT_API");

        MvcResult list =
                mvc.perform(get(NEW_INTEGRATIONS).with(tokenFor(OWNER))).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(list.getResponse().getContentAsString()).contains(TELEGRAM_INSTALLATION.toString());
    }

    @Test
    void aPrincipalWithoutInstallationManageIsRefusedByNameOnTheOperationsSurface() throws Exception {
        MvcResult refused =
                mvc.perform(get(NEW_INTEGRATIONS).with(tokenFor(FINANCE))).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.INTEGRATION_INSTALLATION_MANAGE.code());
    }

    @Test
    void aTenantOwnerWritesASecretThroughTheOperationsDoorAndTheValueNeverLeaks() throws Exception {
        ListAppender<ILoggingEvent> lines = captureAllLogs();
        String secretValue = "operations-surface-secret-xyz789";
        try {
            MvcResult result = mvc.perform(post(NEW_SECRETS)
                            .with(tokenFor(OWNER))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "operations-door-write-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(writeRequest("PROVIDER_NOTIFICATION", "TELEGRAM_BOT_API", secretValue)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            String body = result.getResponse().getContentAsString();
            assertThat(body)
                    .as("only a reference leaves this endpoint, reached through the operations mirror")
                    .contains("\"reference\":\"horecaos:")
                    .doesNotContain(secretValue);

            List<Map<String, Object>> auditRows =
                    jdbc.sql("""
                    SELECT change_document FROM audit.audit_events
                    WHERE action_code = 'integration.secret_written' AND actor_subject = :subject
                    """).param("subject", OWNER).query().listOfRows();
            assertThat(auditRows).hasSize(1);
            assertThat(String.valueOf(auditRows.getFirst().get("change_document")))
                    .as("the audit fact names the reference, never the value")
                    .doesNotContain(secretValue);

            assertThat(lines.list)
                    .as("no captured log line carries the value through the operations-prefixed path either")
                    .noneMatch(event -> event.getFormattedMessage().contains(secretValue));
        } finally {
            releaseAllLogs(lines);
        }
    }

    @Test
    void aTenantOwnerRotatesAnInstallationsSecretByValueOnTheOperationsSurface() throws Exception {
        MvcResult result = mvc.perform(post(NEW_INTEGRATIONS + "/" + TELEGRAM_INSTALLATION + "/secret-rotations/value")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "operations-rotate-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"a-fresh-bot-token\",\"reason\":\"rotating on the operations surface\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"botUsername\":\"HorecaOsOperationsSurfaceBot\"")
                .doesNotContain("a-fresh-bot-token");

        String reference = jdbc.sql("SELECT secret_reference FROM integration.installations WHERE id = :id")
                .param("id", TELEGRAM_INSTALLATION)
                .query(String.class)
                .single();
        assertThat(reference).startsWith("horecaos:").contains("provider_notification");
    }

    @Test
    void theOriginalControlPlanePrefixedPathsStillAnswerAfterTheMove() throws Exception {
        MvcResult oldConnectFields = mvc.perform(
                        get(OLD_INTEGRATIONS + "/connect-fields").with(tokenFor(OWNER)))
                .andReturn();
        assertThat(oldConnectFields.getResponse().getStatus())
                .as("the published control-plane-prefixed path must keep answering; nothing calls it "
                        + "today, but OpenApiContractTests forbids a published path disappearing")
                .isEqualTo(200);

        MvcResult oldDoor = mvc.perform(post(OLD_SECRETS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "old-door-still-answers-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeRequest("PROVIDER_NOTIFICATION", "TELEGRAM_BOT_API", "old-path-still-works")))
                .andReturn();
        assertThat(oldDoor.getResponse().getStatus()).isEqualTo(200);
        assertThat(oldDoor.getResponse().getContentAsString())
                .contains("\"reference\":\"horecaos:")
                .doesNotContain("old-path-still-works");
    }

    // ------------------------------------------------------------------ fixtures

    private static String writeRequest(String category, String providerType, String value) {
        return "{\"category\":\"%s\",\"providerType\":\"%s\",\"value\":\"%s\"}"
                .formatted(category, providerType, value);
    }

    private void seedTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'operations-integrations-surface', 'Operations Surface', 'Operations Surface',
                        'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    private void seedTelegramEnvironmentAndInstallation() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('operations-surface-telegram-env', 'NOTIFICATION', 'TELEGRAM_BOT_API', :baseUrl,
                        false, '127.0.0.1')
                ON CONFLICT DO NOTHING
                """).param("baseUrl", bot.baseUrl()).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'operations-surface-telegram-env',
                        'Pilot bot', 'ACTIVE', 'horecaos:local:provider_notification:tenant-original:telegram')
                """)
                .param("id", TELEGRAM_INSTALLATION)
                .param("tenantId", TENANT)
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'operations surface endpoint test')
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    private static ListAppender<ILoggingEvent> captureAllLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger().addAppender(appender);
        return appender;
    }

    private static void releaseAllLogs(ListAppender<ILoggingEvent> appender) {
        rootLogger().detachAppender(appender);
        appender.stop();
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
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
