package uz.horecaos.platform.integration.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.integration.camel.notification.telegram.FakeTelegramBotApi;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The gap {@code TelegramWebhookController}'s own class doc names: nothing ever
 * wrote {@code integration.installations.webhook_secret_reference}, so a
 * deployed, {@code ACTIVE} Telegram installation could never receive an update
 * and ADR 0063's storefront "Continue with Telegram" sign-in could never
 * complete. This proves {@code TelegramWebhookRegistrationService}, reached
 * through {@link OperationsProviderInstallationController} and {@link
 * ProviderInstallationController}, actually closes it end to end: a fixture
 * must never pre-seed {@code webhook_secret_reference} (the "before register,
 * 403" case below is the proof the gap existed), and a rotation must leave the
 * old token refused and the new one accepted.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TelegramWebhookRegistrationEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-7000-7000-8000-0000000000e1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b20-7000-7000-8000-0000000000e9");
    private static final UUID TELEGRAM_INSTALLATION = UUID.fromString("018f9b20-7000-7000-8000-0000000000e2");
    private static final UUID DRAFT_INSTALLATION = UUID.fromString("018f9b20-7000-7000-8000-0000000000e3");
    private static final UUID CLOPOS_INSTALLATION = UUID.fromString("018f9b20-7000-7000-8000-0000000000e4");

    private static final String OWNER = "webhook-reg-owner";
    private static final String FINANCE = "webhook-reg-finance";
    private static final String OTHER_OWNER = "webhook-reg-other-owner";

    private static final String INTEGRATIONS = "/api/v1/operations/tenants/" + TENANT + "/integrations";
    private static final String OLD_INTEGRATIONS = "/api/v1/control-plane/tenants/" + TENANT + "/integrations";
    private static final String OTHER_TENANT_INTEGRATIONS =
            "/api/v1/operations/tenants/" + OTHER_TENANT + "/integrations";

    /** Telegram's own allowed alphabet for a webhook secret token. */
    private static final Pattern SECRET_TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9_-]{64}$");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private FakeTelegramBotApi bot;
    private String botTokenReference;

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
        // Deliberately carries a trailing slash: an ops typo this class must
        // not turn into a double-slash webhook URL. Every assertion in this
        // class that checks the built webhookUrl (below, and in the happy
        // path test) already expects a single slash, so this doubles as the
        // regression guard for that guard.
        registry.add("horecaos.public-api-origin", () -> "http://localhost:8080/");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private SecretIngressGateway door;

    @Autowired
    private SecretResolver secretResolver;

    @BeforeEach
    void setUp() throws IOException {
        bot = FakeTelegramBotApi.start();

        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        botTokenReference = door.write(
                        SecretCategory.PROVIDER_NOTIFICATION, "tenant-" + TENANT, SecretValue.of("bot-token-xyz-789"))
                .toString();

        seedTenants();
        seedEnvironment();
        seedInstallations();
        roleRegistry.synchronize();
        grant(OWNER, TENANT, PlatformRole.TENANT_OWNER);
        grant(FINANCE, TENANT, PlatformRole.TENANT_FINANCE);
        grant(OTHER_OWNER, OTHER_TENANT, PlatformRole.TENANT_OWNER);
    }

    @AfterEach
    void tearDown() {
        if (bot != null) {
            bot.close();
        }
    }

    // ------------------------------------------------------------------ (a) happy path

    @Test
    void theHappyPathRegistersAWebhookWhoseStoredReferenceResolvesToExactlyTheTokenTelegramReceived() throws Exception {
        ListAppender<ILoggingEvent> lines = captureAllLogs();
        try {
            MvcResult result = mvc.perform(post(INTEGRATIONS + "/" + TELEGRAM_INSTALLATION + "/webhook-registration")
                            .with(tokenFor(OWNER))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-1"))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString())
                    .isEqualTo(200);
            String body = result.getResponse().getContentAsString();
            assertThat(body)
                    .contains("\"installationId\":\"" + TELEGRAM_INSTALLATION + "\"")
                    .contains("\"webhookUrl\":\"http://localhost:8080/providers/telegram/" + TELEGRAM_INSTALLATION
                            + "/webhook\"");

            String sentToken = bot.lastWebhookSecretToken();
            assertThat(sentToken).isNotNull();
            assertThat(SECRET_TOKEN_SHAPE.matcher(sentToken).matches())
                    .as("a 64-char token from Telegram's own allowed alphabet: " + sentToken)
                    .isTrue();
            assertThat(bot.lastWebhookUrl())
                    .isEqualTo("http://localhost:8080/providers/telegram/" + TELEGRAM_INSTALLATION + "/webhook");
            assertThat(bot.lastWebhookAllowedUpdates())
                    .as("only update types TelegramUpdateHandler actually branches on")
                    .containsExactlyInAnyOrder("message", "callback_query");

            String storedReference = jdbc.sql(
                            "SELECT webhook_secret_reference FROM integration.installations WHERE id = :id")
                    .param("id", TELEGRAM_INSTALLATION)
                    .query(String.class)
                    .single();
            assertThat(storedReference).isNotNull().startsWith("horecaos:");
            assertThat(secretResolver
                            .resolve(SecretReference.parse(storedReference))
                            .reveal())
                    .as("the reference the row now holds resolves to exactly the token the fake received")
                    .isEqualTo(sentToken);

            assertThat(body).as("the response never carries the secret token").doesNotContain(sentToken);
            List<Map<String, Object>> auditRows =
                    jdbc.sql("""
                    SELECT change_document FROM audit.audit_events
                    WHERE action_code = 'integration.webhook_registered' AND actor_subject = :subject
                    """).param("subject", OWNER).query().listOfRows();
            assertThat(auditRows).hasSize(1);
            assertThat(String.valueOf(auditRows.getFirst().get("change_document")))
                    .as("the audit fact names the reference, never the token")
                    .doesNotContain(sentToken);
            assertThat(lines.list)
                    .as("no captured log line ever carries the webhook secret token")
                    .noneMatch(event -> event.getFormattedMessage().contains(sentToken));
        } finally {
            releaseAllLogs(lines);
        }
    }

    @Test
    void bothPublishedSurfacesReachTheSameRegistration() throws Exception {
        MvcResult onOldPath = mvc.perform(post(OLD_INTEGRATIONS + "/" + TELEGRAM_INSTALLATION + "/webhook-registration")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-old-surface-1"))
                .andReturn();
        assertThat(onOldPath.getResponse().getStatus())
                .as("OpenApiContractTests forbids a published path disappearing, and this one is a real caller too")
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ (b) end-to-end webhook gate

    @Test
    void theWebhookPathIsClosedUntilRegisteredThenOpensToTheRightTokenOnly() throws Exception {
        String webhookPath = "/providers/telegram/" + TELEGRAM_INSTALLATION + "/webhook";

        // Before register(): the fixture must not have pre-seeded
        // webhook_secret_reference. This 403 IS the proof the gap this class
        // closes actually existed.
        MvcResult beforeRegister = mvc.perform(post(webhookPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(beforeRegister.getResponse().getStatus()).isEqualTo(403);

        mvc.perform(post(INTEGRATIONS + "/" + TELEGRAM_INSTALLATION + "/webhook-registration")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-e2e-1"))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));

        String token = java.util.Objects.requireNonNull(bot.lastWebhookSecretToken());

        MvcResult wrongToken = mvc.perform(post(webhookPath)
                        .header("X-Telegram-Bot-Api-Secret-Token", "not-the-right-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(wrongToken.getResponse().getStatus()).isEqualTo(403);

        MvcResult rightToken = mvc.perform(post(webhookPath)
                        .header("X-Telegram-Bot-Api-Secret-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(rightToken.getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ (c) Telegram failure

    @Test
    void aTelegramRefusalChangesNothingInTheDatabase() throws Exception {
        bot.failNextSetWebhook("Bad Request: webhook registration refused");

        MvcResult result = mvc.perform(post(INTEGRATIONS + "/" + TELEGRAM_INSTALLATION + "/webhook-registration")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-fails-1"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isBetween(400, 599);

        String storedReference = jdbc.sql(
                        "SELECT webhook_secret_reference FROM integration.installations WHERE id = :id")
                .param("id", TELEGRAM_INSTALLATION)
                .query(String.class)
                .optional()
                .orElse(null);
        assertThat(storedReference)
                .as("nothing in the row changes on a Telegram refusal")
                .isNull();

        long successAudits = jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                WHERE action_code = 'integration.webhook_registered' AND target_id = :id
                """)
                .param("id", TELEGRAM_INSTALLATION)
                .query(Long.class)
                .single();
        assertThat(successAudits)
                .as("no audit row of a success that never happened")
                .isZero();
    }

    /**
     * Telegram has already accepted the new secret and URL by the time the
     * database write runs; only the local write can still fail (a transient
     * connection loss, a pool blip). {@code GlobalApiErrorHandler} only
     * special-cases {@code OptimisticLockingFailureException} and {@code
     * DataIntegrityViolationException} -- a bare {@code DataAccessException}
     * (what a real connection failure surfaces as) must still be reported
     * clearly and logged, not fall through to a silent, unexplained 500. A
     * trigger stands in for that connection failure: it is scoped to this
     * test's own private database and dropped again in the {@code finally}.
     */
    @Test
    void aDatabaseWriteFailureAfterTelegramAcceptsIsLoggedAndReportedClearly() throws Exception {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION test_force_webhook_update_failure() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'simulated persistence failure for a test';
                END;
                $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("""
                CREATE TRIGGER test_force_webhook_update_failure_trigger
                BEFORE UPDATE OF webhook_secret_reference ON integration.installations
                FOR EACH ROW EXECUTE FUNCTION test_force_webhook_update_failure()
                """).update();
        try {
            ListAppender<ILoggingEvent> lines = captureAllLogs();
            try {
                MvcResult result = mvc.perform(
                                post(INTEGRATIONS + "/" + TELEGRAM_INSTALLATION + "/webhook-registration")
                                        .with(tokenFor(OWNER))
                                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-db-fail-1"))
                        .andReturn();

                String body = result.getResponse().getContentAsString();
                assertThat(result.getResponse().getStatus()).as(body).isBetween(400, 599);
                assertThat(result.getResponse().getContentType())
                        .as("an ADR 0031 Problem Details response, not a bare unexplained 500")
                        .isEqualTo("application/problem+json");
                assertThat(body)
                        .as("tells the operator Telegram already accepted the new secret")
                        .containsIgnoringCase("retry");

                assertThat(lines.list)
                        .as("the desync (Telegram accepted, the platform did not record it) is logged for on-call")
                        .anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR
                                && event.getFormattedMessage().contains(TELEGRAM_INSTALLATION.toString()));
                assertThat(bot.setWebhookCallCount())
                        .as("Telegram really was called and really did accept before the write failed")
                        .isEqualTo(1);
            } finally {
                releaseAllLogs(lines);
            }
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS test_force_webhook_update_failure_trigger "
                            + "ON integration.installations")
                    .update();
            jdbc.sql("DROP FUNCTION IF EXISTS test_force_webhook_update_failure()")
                    .update();
        }
    }

    /**
     * {@code TelegramCallResult.Uncertain} means Telegram's own answer could
     * not be read -- it may or may not have already applied the new secret
     * and URL. Reporting that identically to a confirmed refusal ("Telegram
     * rejected...") sends an operator chasing the wrong cause (a bad bot
     * token or URL) instead of the right one: retry, because Telegram's own
     * setWebhook is unconditionally idempotent per bot and a retry
     * resynchronizes whichever state it is actually in.
     */
    @Test
    void aTelegramUncertainOutcomeIsNotReportedAsARejectionAndLeavesTheDatabaseUnwritten() throws Exception {
        bot.nextSetWebhookAnswersUnreadably();

        MvcResult result = mvc.perform(post(INTEGRATIONS + "/" + TELEGRAM_INSTALLATION + "/webhook-registration")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-uncertain-1"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(body).isBetween(400, 599);
        assertThat(body)
                .as("an unreadable Telegram answer is not a confirmed rejection")
                .doesNotContain("rejected");
        assertThat(body)
                .as("the operator is told the right remediation: retry, not fix the bot token/URL")
                .containsIgnoringCase("retry");

        // Telegram genuinely received this request (the fake recorded it,
        // exactly like a real acceptance would) even though its answer could
        // not be read -- so the database must stay exactly as it was rather
        // than committing to a reference no one confirmed Telegram accepted.
        assertThat(bot.lastWebhookSecretToken()).isNotNull();
        String storedReference = jdbc.sql(
                        "SELECT webhook_secret_reference FROM integration.installations WHERE id = :id")
                .param("id", TELEGRAM_INSTALLATION)
                .query(String.class)
                .optional()
                .orElse(null);
        assertThat(storedReference)
                .as("nothing in the row changes while the outcome is unconfirmed")
                .isNull();
    }

    /**
     * Two overlapping {@code register()} calls each mint their own secret and
     * call Telegram; whichever call reaches Telegram last is what Telegram
     * actually keeps. Without a compare-and-swap on the final UPDATE, a
     * caller whose own database write lands last can silently overwrite a
     * row that a second, concurrent registration already moved on -- leaving
     * the stored reference pointing at a secret Telegram no longer honors,
     * with both calls answering 200. {@code FakeTelegramBotApi#onNextSetWebhook}
     * lands a second writer's raw UPDATE exactly inside the first call's
     * outbound HTTP window, standing in for the real race without needing
     * threads or timing.
     */
    @Test
    void aConcurrentWriterThatMovesTheReferenceMidFlightWinsOverAStaleUpdate() throws Exception {
        String concurrentWriterReference = "horecaos:test:provider_notification:concurrent-writer:1";
        bot.onNextSetWebhook(() -> jdbc.sql("UPDATE integration.installations "
                        + "SET webhook_secret_reference = :reference, version = version + 1 "
                        + "WHERE id = :id")
                .param("reference", concurrentWriterReference)
                .param("id", TELEGRAM_INSTALLATION)
                .update());

        MvcResult result = mvc.perform(post(INTEGRATIONS + "/" + TELEGRAM_INSTALLATION + "/webhook-registration")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-race-1"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus())
                .as("a stale write is refused as a conflict, not silently accepted: " + body)
                .isEqualTo(409);

        String storedReference = jdbc.sql(
                        "SELECT webhook_secret_reference FROM integration.installations WHERE id = :id")
                .param("id", TELEGRAM_INSTALLATION)
                .query(String.class)
                .single();
        assertThat(storedReference)
                .as("the concurrent writer's value survives; this call's own (now-stale) reference never lands")
                .isEqualTo(concurrentWriterReference);

        long successAudits = jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                WHERE action_code = 'integration.webhook_registered' AND target_id = :id
                """)
                .param("id", TELEGRAM_INSTALLATION)
                .query(Long.class)
                .single();
        assertThat(successAudits)
                .as("no audit row claiming this call's write actually happened")
                .isZero();
    }

    // ------------------------------------------------------------------ (d) re-register rotates

    @Test
    void reRegisteringRotatesTheWebhookSecretAndRetiresTheOldToken() throws Exception {
        String webhookPath = "/providers/telegram/" + TELEGRAM_INSTALLATION + "/webhook";

        mvc.perform(post(INTEGRATIONS + "/" + TELEGRAM_INSTALLATION + "/webhook-registration")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-rotate-1"))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        String firstToken = java.util.Objects.requireNonNull(bot.lastWebhookSecretToken());
        String firstReference = jdbc.sql(
                        "SELECT webhook_secret_reference FROM integration.installations WHERE id = :id")
                .param("id", TELEGRAM_INSTALLATION)
                .query(String.class)
                .single();

        mvc.perform(post(INTEGRATIONS + "/" + TELEGRAM_INSTALLATION + "/webhook-registration")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-rotate-2"))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        String secondToken = java.util.Objects.requireNonNull(bot.lastWebhookSecretToken());
        String secondReference = jdbc.sql(
                        "SELECT webhook_secret_reference FROM integration.installations WHERE id = :id")
                .param("id", TELEGRAM_INSTALLATION)
                .query(String.class)
                .single();

        assertThat(secondToken).isNotEqualTo(firstToken);
        assertThat(secondReference).isNotEqualTo(firstReference);
        assertThat(bot.setWebhookCallCount()).isEqualTo(2);

        MvcResult oldTokenNow = mvc.perform(post(webhookPath)
                        .header("X-Telegram-Bot-Api-Secret-Token", firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(oldTokenNow.getResponse().getStatus())
                .as("the old token is retired the instant the new one lands")
                .isEqualTo(403);

        MvcResult newTokenNow = mvc.perform(post(webhookPath)
                        .header("X-Telegram-Bot-Api-Secret-Token", secondToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(newTokenNow.getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ (e) status/provider-type refusals

    @Test
    void aDraftInstallationIsRefused() throws Exception {
        MvcResult result = mvc.perform(post(INTEGRATIONS + "/" + DRAFT_INSTALLATION + "/webhook-registration")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-draft-1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(bot.setWebhookCallCount())
                .as("Telegram is never called for a DRAFT installation")
                .isZero();
    }

    @Test
    void aNonTelegramInstallationIsRefused() throws Exception {
        MvcResult result = mvc.perform(post(INTEGRATIONS + "/" + CLOPOS_INSTALLATION + "/webhook-registration")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-clopos-1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    // ------------------------------------------------------------------ (f) authorization / tenant isolation

    @Test
    void aPrincipalWithoutInstallationManageIsRefused() throws Exception {
        MvcResult result = mvc.perform(post(INTEGRATIONS + "/" + TELEGRAM_INSTALLATION + "/webhook-registration")
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-finance-1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.INTEGRATION_INSTALLATION_MANAGE.code());
        assertThat(bot.setWebhookCallCount()).isZero();
    }

    @Test
    void anotherTenantsInstallationIsNotFound() throws Exception {
        MvcResult result = mvc.perform(
                        post(OTHER_TENANT_INTEGRATIONS + "/" + TELEGRAM_INSTALLATION + "/webhook-registration")
                                .with(tokenFor(OTHER_OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-cross-tenant-1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(bot.setWebhookCallCount())
                .as("tenant isolation is checked before Telegram is ever called")
                .isZero();
    }

    // ------------------------------------------------------------------ fixtures

    private void seedTenants() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, :name, :name, 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", TENANT)
                .param("slug", "webhook-registration")
                .param("name", "Webhook Registration")
                .update();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, :name, :name, 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", OTHER_TENANT)
                .param("slug", "webhook-registration-other")
                .param("name", "Webhook Registration Other")
                .update();
    }

    private void seedEnvironment() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('webhook-registration-telegram', 'NOTIFICATION', 'TELEGRAM_BOT_API', :baseUrl,
                        false, '127.0.0.1')
                ON CONFLICT DO NOTHING
                """).param("baseUrl", bot.baseUrl()).update();
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('webhook-registration-clopos', 'POS', 'clopos', 'https://api.clopos.com',
                        false, 'api.clopos.com')
                ON CONFLICT DO NOTHING
                """).update();
    }

    private void seedInstallations() {
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'webhook-registration-telegram',
                        'Sign-in bot', 'ACTIVE', :secretReference)
                """)
                .param("id", TELEGRAM_INSTALLATION)
                .param("tenantId", TENANT)
                .param("secretReference", botTokenReference)
                .update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'webhook-registration-telegram',
                        'Never connected bot', 'DRAFT', :secretReference)
                """)
                .param("id", DRAFT_INSTALLATION)
                .param("tenantId", TENANT)
                .param("secretReference", botTokenReference)
                .update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'POS', 'clopos', 'webhook-registration-clopos',
                        'Till', 'ACTIVE', 'horecaos:test:provider_pos:tenant-original:clopos')
                """).param("id", CLOPOS_INSTALLATION).param("tenantId", TENANT).update();
    }

    private void grant(String subject, UUID tenantId, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'webhook registration endpoint test')
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", tenantId)
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
