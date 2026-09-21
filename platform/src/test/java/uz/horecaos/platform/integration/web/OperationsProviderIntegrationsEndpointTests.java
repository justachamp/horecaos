package uz.horecaos.platform.integration.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final UUID CLOPOS_INSTALLATION = UUID.fromString("018f9b20-7000-7000-8000-0000000000f3");

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
        seedCloposEnvironmentAndInstallation();
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

    @Test
    void aTenantOwnerReadsAndTogglesTheCloposClerkApprovalSettingOnTheOperationsSurface() throws Exception {
        String path = NEW_INTEGRATIONS + "/" + CLOPOS_INSTALLATION + "/settings";

        MvcResult defaultRead = mvc.perform(get(path).with(tokenFor(OWNER))).andReturn();
        assertThat(defaultRead.getResponse().getStatus()).isEqualTo(200);
        assertThat(defaultRead.getResponse().getContentAsString())
                .as("nothing has been set yet, so the safe posture — the clerk decides — applies")
                .contains("\"requireClerkApproval\":true");

        MvcResult disabled = mvc.perform(post(path)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "operations-clopos-settings-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireClerkApproval\":false}"))
                .andReturn();
        assertThat(disabled.getResponse().getStatus()).isEqualTo(200);
        assertThat(disabled.getResponse().getContentAsString()).contains("\"requireClerkApproval\":false");

        String stored = jdbc.sql("""
                SELECT non_sensitive_config ->> 'clopos.requireClerkApproval' AS value
                  FROM integration.installations WHERE id = :id
                """)
                .param("id", CLOPOS_INSTALLATION)
                .query(String.class)
                .single();
        assertThat(stored)
                .as("the export path (CloposAdapter#exportOrder) reads exactly this stored key")
                .isEqualTo("false");

        MvcResult reread = mvc.perform(get(path).with(tokenFor(OWNER))).andReturn();
        assertThat(reread.getResponse().getContentAsString()).contains("\"requireClerkApproval\":false");
    }

    @Test
    void aPrincipalWithoutInstallationManageIsRefusedTheCloposSettingsEndpoint() throws Exception {
        MvcResult refused = mvc.perform(get(NEW_INTEGRATIONS + "/" + CLOPOS_INSTALLATION + "/settings")
                        .with(tokenFor(FINANCE)))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * The connect flow's second call, against the catalogue a deployed host really
     * has. Every other test here installs nothing and seeds its own environment row
     * pointed at the fake bot, which is how the Telegram endpoint went unapproved
     * from V0099 to V0374: on pre-production (2026-09-19) the secret door stored the
     * bot token and this call answered 400 for any code an operator could type.
     * {@link #setUp()} truncates the catalogue, so the migration's own statement is
     * replayed from the classpath — the row under test is byte-for-byte the one
     * Flyway applies, not a copy of it written here.
     */
    @Test
    void aTenantOwnerInstallsATelegramBotAgainstTheEnvironmentTheMigrationApproves() throws Exception {
        String migration = new String(
                new org.springframework.core.io.ClassPathResource(
                                "db/migration/V0374__approve_the_telegram_bot_api_endpoint.sql")
                        .getInputStream()
                        .readAllBytes(),
                UTF_8);
        jdbc.sql(migration).update();

        assertThat(jdbc.sql("""
                        SELECT provider_category || '|' || provider_type || '|' || base_url || '|' || egress_allowlist
                          FROM integration.provider_environments WHERE code = 'telegram-prod'
                        """).query(String.class).single())
                .as("TelegramBotApiClient appends /bot<token>/<method>, so the base URL carries neither")
                .isEqualTo("NOTIFICATION|TELEGRAM_BOT_API|https://api.telegram.org|api.telegram.org");

        MvcResult installed = mvc.perform(post(NEW_INTEGRATIONS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "operations-install-telegram-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(installRequest("telegram-prod")))
                .andReturn();

        assertThat(installed.getResponse().getStatus())
                .as(installed.getResponse().getContentAsString())
                .isBetween(200, 201);
        assertThat(jdbc.sql("""
                        SELECT status || '|' || environment_code FROM integration.installations
                         WHERE tenant_id = :tenantId AND display_name = 'Storefront sign-in bot'
                        """).param("tenantId", TENANT).query(String.class).single())
                .isEqualTo("DRAFT|telegram-prod");

        MvcResult refused = mvc.perform(post(NEW_INTEGRATIONS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "operations-install-telegram-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(installRequest("telegram-production")))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("a code outside the catalogue is still refused: the seed widened the catalogue, not the check")
                .isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("telegram-production");
    }

    /**
     * The connect-fields response's new {@code environments} field (ADR 0106 gap
     * map row X.19): a tenant picks from this list rather than typing a code it
     * has no way to know, and the join proving it out must not leak an
     * environment seeded for a different provider of the same category into a
     * declaration that never approved it.
     */
    @Test
    void connectFieldsListsExactlyTheSeededEnvironmentsPerProviderAndNoneFromAnother() throws Exception {
        // Two providers sharing PAYMENT, each with its own environment, plus a
        // second Telegram-category row that must not appear anywhere but
        // TELEGRAM_BOT_API. Click's non-production code sorts after its
        // production one alphabetically, proving "production first" is a real
        // ordering rule rather than incidental code order.
        environment("click-sandbox-env", "PAYMENT", "CLICK", false);
        environment("click-prod-env", "PAYMENT", "CLICK", true);
        environment("payme-sandbox-env", "PAYMENT", "PAYME", false);

        MvcResult result = mvc.perform(get(NEW_INTEGRATIONS + "/connect-fields").with(tokenFor(OWNER)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode declarations = JSON.readTree(result.getResponse().getContentAsString());

        JsonNode click = declarationFor(declarations, "CLICK");
        assertThat(codesOf(click.path("environments")))
                .as("production-first, then code — never the sibling PAYME environment")
                .containsExactly("click-prod-env", "click-sandbox-env");

        JsonNode payme = declarationFor(declarations, "PAYME");
        assertThat(codesOf(payme.path("environments")))
                .as("PAYME sees only its own row, never CLICK's")
                .containsExactly("payme-sandbox-env");

        JsonNode telegram = declarationFor(declarations, "TELEGRAM_BOT_API");
        assertThat(codesOf(telegram.path("environments")))
                .as("only the fixture's own Telegram row, never a PAYMENT-category one")
                .containsExactly("operations-surface-telegram-env");

        JsonNode hostedPbx = declarationFor(declarations, "HOSTED_PBX");
        assertThat(codesOf(hostedPbx.path("environments")))
                .as("no row seeded for this provider: an empty list, not an absent field or an error")
                .isEmpty();
    }

    @Test
    void installRefusesAnEnvironmentSeededForAnotherProviderOfTheSameCategory() throws Exception {
        environment("cross-provider-click-env", "PAYMENT", "CLICK", false);
        environment("cross-provider-payme-env", "PAYMENT", "PAYME", false);

        MvcResult refused = mvc.perform(post(NEW_INTEGRATIONS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cross-provider-refusal-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(installRequest("PAYMENT", "CLICK", "cross-provider-payme-env", "Click via Payme env")))
                .andReturn();
        assertThat(refused.getResponse().getStatus())
                .as("PAYME's environment is a PAYMENT row too, but not CLICK's")
                .isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INVALID_REQUEST")
                .contains("cross-provider-payme-env");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM integration.installations
                         WHERE tenant_id = :tenantId AND display_name = 'Click via Payme env'
                        """).param("tenantId", TENANT).query(Integer.class).single())
                .as("the refused install must not have written a row")
                .isZero();

        MvcResult installed = mvc.perform(post(NEW_INTEGRATIONS)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cross-provider-success-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(installRequest(
                                "PAYMENT", "CLICK", "cross-provider-click-env", "Click via its own env")))
                .andReturn();
        assertThat(installed.getResponse().getStatus())
                .as(installed.getResponse().getContentAsString())
                .isBetween(200, 201);
    }

    private static JsonNode declarationFor(JsonNode declarations, String providerType) {
        for (JsonNode declaration : declarations) {
            if (providerType.equals(declaration.path("providerType").asText())) {
                return declaration;
            }
        }
        throw new AssertionError("No connect-fields declaration for " + providerType + " in " + declarations);
    }

    private static List<String> codesOf(JsonNode environments) {
        List<String> codes = new java.util.ArrayList<>();
        for (JsonNode environment : environments) {
            codes.add(environment.path("code").asText());
        }
        return codes;
    }

    private void environment(String code, String category, String providerType, boolean production) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, :category, :providerType, 'https://example.test', :production, '')
                ON CONFLICT (code) DO NOTHING
                """)
                .param("code", code)
                .param("category", category)
                .param("providerType", providerType)
                .param("production", production)
                .update();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String installRequest(
            String category, String providerType, String environmentCode, String displayName) {
        return """
                {"category":"%s","providerType":"%s","environmentCode":"%s","displayName":"%s"}
                """.formatted(category, providerType, environmentCode, displayName);
    }

    private static String installRequest(String environmentCode) {
        return """
                {"category":"NOTIFICATION","providerType":"TELEGRAM_BOT_API","environmentCode":"%s",
                 "displayName":"Storefront sign-in bot",
                 "secretReference":"horecaos:local:provider_notification:tenant-%s:install-test"}
                """.formatted(environmentCode, TENANT);
    }

    /**
     * The connect flow's third call, with the body the console really sends. No
     * test had ever put a bind request through HTTP — the controller was called
     * as a Java object, where a missing {@code priority} cannot be expressed —
     * so nothing noticed that this JSON stack refuses an absent primitive: every
     * console bind answered 400 MALFORMED_BODY (pre-production, 2026-09-21).
     */
    @Test
    void theConsolesBindRequestWithNoPriorityBindsAtTheColumnDefault() throws Exception {
        UUID brand = UUID.fromString("018f9b20-7000-7000-8000-0000000000f9");
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'BIND', 'bind-brand', 'Bind Brand', 'ACTIVE', 0)
                """).param("id", brand).param("tenantId", TENANT).update();
        String bindings = NEW_INTEGRATIONS + "/" + TELEGRAM_INSTALLATION + "/bindings";

        MvcResult omitted = mvc.perform(post(bindings)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "operations-bind-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"brandId\":\"%s\",\"locationId\":null,\"capabilities\":[],\"primaryCapabilities\":[]}"
                                        .formatted(brand)))
                .andReturn();
        assertThat(omitted.getResponse().getStatus())
                .as(omitted.getResponse().getContentAsString())
                .isEqualTo(200);

        MvcResult explicit = mvc.perform(post(bindings)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "operations-bind-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"brandId\":\"%s\",\"locationId\":null,\"priority\":7,\"capabilities\":[],\"primaryCapabilities\":[]}"
                                        .formatted(brand)))
                .andReturn();
        assertThat(explicit.getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbc.sql("""
                        SELECT priority FROM integration.bindings
                         WHERE tenant_id = :tenantId AND installation_id = :installationId ORDER BY priority
                        """)
                        .param("tenantId", TENANT)
                        .param("installationId", TELEGRAM_INSTALLATION)
                        .query(Integer.class)
                        .list())
                .as("an explicit priority is kept; an absent one is the column default, not zero")
                .containsExactly(7, 100);
    }

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

    private void seedCloposEnvironmentAndInstallation() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('operations-surface-clopos-env', 'POS', 'clopos', 'https://api.clopos.com',
                        false, 'api.clopos.com')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'POS', 'clopos', 'operations-surface-clopos-env',
                        'Pilot till', 'DRAFT', 'horecaos:local:provider_pos:tenant-original:clopos')
                """).param("id", CLOPOS_INSTALLATION).param("tenantId", TENANT).update();
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
