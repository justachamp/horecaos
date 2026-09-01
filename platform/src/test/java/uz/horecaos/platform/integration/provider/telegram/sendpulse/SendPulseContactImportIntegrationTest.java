package uz.horecaos.platform.integration.provider.telegram.sendpulse;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.api.CustomerImportDirectory;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.customers.application.CustomerIdentityService;
import uz.horecaos.platform.customers.application.CustomerImportDirectoryService;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.infrastructure.persistence.ConfiguredCustomerPolicyLookup;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.integration.camel.notification.CamelNotificationTransport;
import uz.horecaos.platform.integration.camel.notification.NotificationGateway;
import uz.horecaos.platform.integration.camel.notification.NotificationProcessor;
import uz.horecaos.platform.integration.camel.notification.NotificationRouteBuilder;
import uz.horecaos.platform.integration.camel.notification.telegram.FakeTelegramBotApi;
import uz.horecaos.platform.integration.camel.notification.telegram.TelegramChannelAdapter;
import uz.horecaos.platform.integration.camel.notification.telegram.TelegramCircuitBreakers;
import uz.horecaos.platform.integration.provider.JdbcProviderInstallationLookup;
import uz.horecaos.platform.integration.provider.telegram.BotActionTokenStore;
import uz.horecaos.platform.integration.provider.telegram.TelegramBindingStore;
import uz.horecaos.platform.integration.provider.telegram.TelegramBotApiClient;
import uz.horecaos.platform.integration.provider.telegram.TelegramChatLockService;
import uz.horecaos.platform.integration.provider.telegram.TelegramCustomerLinkService;
import uz.horecaos.platform.integration.provider.telegram.TelegramInstallationBrandLookup;
import uz.horecaos.platform.integration.provider.telegram.TelegramMessageTracker;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup;
import uz.horecaos.platform.notifications.api.CustomerProviderBindingSync;
import uz.horecaos.platform.notifications.application.CustomerProviderBindingSyncService;
import uz.horecaos.platform.notifications.application.NotificationDispatchService;
import uz.horecaos.platform.notifications.application.NotificationEligibilityService;
import uz.horecaos.platform.notifications.application.NotificationPreferenceService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.application.NotificationWorker;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0059 stage 3, end to end against real PostgreSQL: a SendPulse export
 * becomes real customers, real ADR 0058 CUSTOMER-audience Telegram bindings,
 * and real ADR 0020 consent — proven not by inspecting rows alone but by
 * routing a real notification through the real eligibility/worker/Telegram
 * pipeline and watching it arrive (or not) at {@link FakeTelegramBotApi}.
 *
 * <p>The fixture ({@code src/test/resources/sendpulse/sendpulse-contacts-fixture.csv})
 * carries one row of each required shape: subscribed, unsubscribed, no phone,
 * a duplicate chat id (row 4 repeats row 1's chat), and two malformed rows
 * (a missing chat id, an unrecognised status).
 */
class SendPulseContactImportIntegrationTest {

    private static final Path FIXTURE = Path.of("src/test/resources/sendpulse/sendpulse-contacts-fixture.csv");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private Clock clock;
    private ObjectMapper objectMapper;
    private AuditRecorder audit;
    private FieldProtection protection;
    private FakeTelegramBotApi bot;

    private SendPulseContactImportService importService;
    private ConsentService consent;
    private JdbcCustomerStore customerStore;
    private JdbcNotificationStore notifications;
    private StubOrderDirectory orderSummaries;
    private NotificationTemplateService templates;
    private NotificationEligibilityService eligibility;
    private NotificationDispatchService dispatch;
    private NotificationWorker worker;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        bot = FakeTelegramBotApi.start();
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        truncate();

        objectMapper = JsonMapper.builder().build();
        clock = Clock.fixed(Instant.parse("2026-09-01T09:00:00Z"), ZoneOffset.UTC);
        audit = new JdbcAuditRecorder(jdbc, objectMapper);
        protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get,
                        clock),
                "local"));

        customerStore = new JdbcCustomerStore(jdbc);
        CustomerIdentityService identity =
                new CustomerIdentityService(customerStore, new ConfiguredCustomerPolicyLookup(jdbc), clock);
        CustomerProfileService profiles =
                new CustomerProfileService(customerStore, protection, objectMapper, clock, audit);
        consent = new ConsentService(customerStore, clock);
        CustomerImportDirectory customerImports = new CustomerImportDirectoryService(identity, profiles, consent);

        notifications = new JdbcNotificationStore(jdbc);
        NotificationPreferenceService preferences = new NotificationPreferenceService(notifications, clock);
        CustomerProviderBindingSync bindingSync = new CustomerProviderBindingSyncService(notifications, preferences);
        TelegramBindingStore bindingStore = new TelegramBindingStore(jdbc, clock, audit);
        TelegramCustomerLinkService customerLinks =
                new TelegramCustomerLinkService(jdbc, clock, Duration.ofMinutes(15), bindingStore, bindingSync, audit);
        BotActionTokenStore actionTokens = new BotActionTokenStore(jdbc, clock);

        SendPulseContactFileParser parser = new SendPulseContactFileParser(objectMapper);
        SendPulseContactImportRowService rowService =
                new SendPulseContactImportRowService(bindingStore, customerLinks, customerImports, clock);
        JdbcSendPulseImportStore importStore = new JdbcSendPulseImportStore(jdbc);
        TelegramWebhookInstallationLookup webhookInstallations = new TelegramWebhookInstallationLookup(jdbc);
        TelegramInstallationBrandLookup brandLookup = new TelegramInstallationBrandLookup(jdbc);
        importService = new SendPulseContactImportService(
                parser, rowService, importStore, webhookInstallations, brandLookup, clock);

        // ---------------------------------------------------- notification pipeline
        // The exact wiring TelegramInteractiveBotIntegrationTest's own
        // notificationGateway() helper uses, narrowed to what proving one
        // real send needs — no bot-command handling here.
        TelegramBotApiClient botApiClient = new TelegramBotApiClient(objectMapper);
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
                botApiClient,
                bindingStore,
                new TelegramChatLockService(jdbc, clock),
                new TelegramMessageTracker(jdbc, clock),
                new TelegramCircuitBreakers(new SimpleMeterRegistry(), clock),
                actionTokens,
                bindingSync,
                clock,
                Duration.ofSeconds(20),
                Duration.ofHours(6),
                "en");
        NotificationGateway gateway = new NotificationGateway(
                List.of(adapter), new JdbcProviderInstallationLookup(jdbc, clock), secretResolver());
        CamelContext camel = new DefaultCamelContext();
        camel.addRoutes(new NotificationRouteBuilder(new NotificationProcessor(gateway, new SimpleMeterRegistry())));
        camel.start();
        CamelNotificationTransport transport = new CamelNotificationTransport(camel.createProducerTemplate(), gateway);

        JdbcTemplateStore templateStore = new JdbcTemplateStore(jdbc);
        templates = new NotificationTemplateService(templateStore, objectMapper, clock);
        orderSummaries = new StubOrderDirectory();
        eligibility = new NotificationEligibilityService(
                notifications,
                templates,
                consent,
                new NoOpContactDirectory(),
                orderSummaries,
                transport,
                objectMapper,
                clock,
                "en");
        dispatch = new NotificationDispatchService(
                notifications,
                templateStore,
                new NoOpContactDirectory(),
                transport,
                objectMapper,
                clock,
                8,
                Duration.ofSeconds(30));
        worker = new NotificationWorker(notifications, eligibility, dispatch, clock, 50, Duration.ofMinutes(2));
    }

    @AfterEach
    void tearDown() {
        bot.close();
    }

    @Test
    void aDryRunReportsExactlyAgainstAFreshDatabaseAndWritesNoCustomerData() throws IOException {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        UUID installationId = seedTelegramInstallation(tenant, brand, "sendpulse-dry-run");

        SendPulseImportReport report = importService.run(
                tenant, installationId, SendPulseImportFormat.CSV, fixtureContent(), "fixture.csv", true, "operator");

        assertThat(report.dryRun()).isTrue();
        assertThat(report.counts().total()).isEqualTo(6);
        // Row 4 duplicates row 1's chat id, but nothing was actually written
        // by row 1 in a dry run, so row 4 independently plans a create too —
        // a dry run reports what each row would do against today's database,
        // not against the other rows of the same in-flight dry run.
        assertThat(report.counts().createdCustomer()).isEqualTo(4);
        assertThat(report.counts().matchedCustomer()).isZero();
        assertThat(report.counts().skippedAlreadyLinked()).isZero();
        assertThat(report.counts().rejected()).isEqualTo(2);
        assertThat(report.counts().subscribed()).isEqualTo(3);
        assertThat(report.counts().unsubscribed()).isEqualTo(1);

        assertThat(report.rows()).hasSize(6);
        assertThat(report.rows().get(4).outcome()).isEqualTo("REJECTED");
        assertThat(report.rows().get(4).rejectReason()).isEqualTo("MISSING_CHAT_ID");
        assertThat(report.rows().get(5).rejectReason()).isEqualTo("UNRECOGNIZED_SUBSCRIPTION_STATUS");
        // A dry run's own "created" rows carry no account id: nothing was created.
        assertThat(report.rows().get(0).customerAccountId()).isNull();

        assertThat(customerAccountCount(tenant)).isZero();
        assertThat(telegramBindingCount(tenant)).isZero();
        assertThat(consentDecisionCount(tenant)).isZero();
        // The dry run itself is still durable bookkeeping.
        assertThat(runRowCount(report.runId())).isEqualTo(6);
    }

    @Test
    void aRealImportCreatesCustomersBindingsEndpointsAndConsentWithProvenance() throws IOException {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        UUID installationId = seedTelegramInstallation(tenant, brand, "sendpulse-real-run");

        SendPulseImportReport report = importService.run(
                tenant,
                installationId,
                SendPulseImportFormat.CSV,
                fixtureContent(),
                "fixture.csv",
                false,
                "operator-1");

        assertThat(report.dryRun()).isFalse();
        assertThat(report.counts().total()).isEqualTo(6);
        assertThat(report.counts().createdCustomer()).isEqualTo(3);
        assertThat(report.counts().matchedCustomer()).isZero();
        assertThat(report.counts().skippedAlreadyLinked())
                .as("row 4 repeats row 1's chat id, and row 1 already committed by the time row 4 runs")
                .isEqualTo(1);
        assertThat(report.counts().rejected()).isEqualTo(2);

        assertThat(customerAccountCount(tenant)).isEqualTo(3);
        assertThat(telegramBindingCount(tenant)).isEqualTo(3);

        UUID subscribedAccount =
                java.util.Objects.requireNonNull(report.rows().get(0).customerAccountId());
        UUID unsubscribedAccount =
                java.util.Objects.requireNonNull(report.rows().get(1).customerAccountId());
        UUID noPhoneAccount =
                java.util.Objects.requireNonNull(report.rows().get(2).customerAccountId());
        // Row 4 (the duplicate) resolved to row 1's own account.
        assertThat(report.rows().get(3).customerAccountId()).isEqualTo(subscribedAccount);
        assertThat(report.rows().get(3).outcome()).isEqualTo("SKIPPED_ALREADY_LINKED");

        // The no-phone contact is a real, identity-less account: no principal
        // link, no phone contact point, reachable only through its binding.
        assertThat(principalLinkCount(noPhoneAccount)).isZero();
        assertThat(contactPointCount(noPhoneAccount)).isZero();
        assertThat(contactPointCount(subscribedAccount)).isEqualTo(1);

        // Consent, explicit and provenanced — never a silent default.
        Map<String, Object> subscribedConsent = latestConsent(tenant, subscribedAccount);
        assertThat(subscribedConsent.get("decision")).isEqualTo("GRANTED");
        assertThat(subscribedConsent.get("source")).isEqualTo("IMPORT");
        assertThat(subscribedConsent.get("purpose")).isEqualTo("MARKETING");
        assertThat(subscribedConsent.get("channel")).isEqualTo("TELEGRAM");
        assertThat((String) subscribedConsent.get("evidence_reference"))
                .contains(report.runId().toString());
        // The fixture's own subscribed_at date carried through, not the run's clock.
        assertThat(String.valueOf(subscribedConsent.get("decided_at"))).contains("2026-06-01");

        Map<String, Object> unsubscribedConsent = latestConsent(tenant, unsubscribedAccount);
        assertThat(unsubscribedConsent.get("decision")).isEqualTo("WITHDRAWN");

        // The TELEGRAM preference an unsubscribed import writes explicitly —
        // the same shape a customer's own 403-triggered revocation writes.
        List<Map<String, Object>> unsubscribedPreferences = jdbc.sql("""
                SELECT notification_class, enabled FROM notifications.notification_preferences
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId AND channel = 'TELEGRAM'
                """)
                .param("tenantId", tenant)
                .param("accountId", unsubscribedAccount)
                .query()
                .listOfRows();
        assertThat(unsubscribedPreferences)
                .extracting(row -> row.get("notification_class"))
                .containsExactlyInAnyOrder("TRANSACTIONAL_OPTIONAL", "MARKETING");
        assertThat(unsubscribedPreferences)
                .allSatisfy(row -> assertThat(row.get("enabled")).isEqualTo(false));

        // ADR 0027: the import run's own audit-ready row.
        var run = importStoreRun(tenant, report.runId());
        assertThat(run.get("status")).isEqualTo("COMPLETE");
        assertThat(run.get("imported_by_principal_id")).isEqualTo("operator-1");
    }

    @Test
    void reimportingTheSameFileCreatesNothingTwice() throws IOException {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        UUID installationId = seedTelegramInstallation(tenant, brand, "sendpulse-reimport");

        importService.run(
                tenant, installationId, SendPulseImportFormat.CSV, fixtureContent(), "fixture.csv", false, "op");
        long accountsAfterFirst = customerAccountCount(tenant);
        long bindingsAfterFirst = telegramBindingCount(tenant);
        long consentAfterFirst = consentDecisionCount(tenant);

        SendPulseImportReport second = importService.run(
                tenant, installationId, SendPulseImportFormat.CSV, fixtureContent(), "fixture.csv", false, "op");

        assertThat(second.counts().createdCustomer()).isZero();
        assertThat(second.counts().matchedCustomer()).isZero();
        assertThat(second.counts().skippedAlreadyLinked())
                .as("every chat this bot already knows — rows 1, 2, 3 and the row-4 duplicate")
                .isEqualTo(4);
        assertThat(second.counts().rejected()).isEqualTo(2);

        assertThat(customerAccountCount(tenant)).isEqualTo(accountsAfterFirst);
        assertThat(telegramBindingCount(tenant)).isEqualTo(bindingsAfterFirst);
        assertThat(consentDecisionCount(tenant))
                .as("a re-import writes no new consent decision for an already-linked chat")
                .isEqualTo(consentAfterFirst);
    }

    @Test
    void anImportedSubscribedContactReceivesARealNotificationAndAnUnsubscribedOneDoesNot() throws IOException {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID installationId = seedTelegramInstallation(tenant, brand, "sendpulse-notify");

        SendPulseImportReport report = importService.run(
                tenant, installationId, SendPulseImportFormat.CSV, fixtureContent(), "fixture.csv", false, "op");
        UUID subscribedAccount =
                java.util.Objects.requireNonNull(report.rows().get(0).customerAccountId());
        UUID unsubscribedAccount =
                java.util.Objects.requireNonNull(report.rows().get(1).customerAccountId());
        long subscribedChatId = 500_001_001L;
        long unsubscribedChatId = 500_001_002L;

        activateTelegramMarketingTemplate(tenant, brand, "sendpulse-import-proof");

        UUID subscribedOrder = UUID.randomUUID();
        UUID unsubscribedOrder = UUID.randomUUID();
        orderSummaries.publish(new OrderDirectory.OrderSummary(
                subscribedOrder,
                tenant,
                brand,
                location,
                "A-1",
                subscribedAccount,
                null,
                "CONFIRMED",
                "UZS",
                50_000L,
                1));
        orderSummaries.publish(new OrderDirectory.OrderSummary(
                unsubscribedOrder,
                tenant,
                brand,
                location,
                "A-2",
                unsubscribedAccount,
                null,
                "CONFIRMED",
                "UZS",
                50_000L,
                1));

        createIntent(tenant, brand, subscribedOrder, "sendpulse-import-proof");
        createIntent(tenant, brand, unsubscribedOrder, "sendpulse-import-proof");
        drainUntilQuiet();

        assertThat(bot.messagesSentTo(subscribedChatId))
                .as("proves the import's binding is real: the message actually reached the bound chat")
                .hasSize(1);
        assertThat(bot.messagesSentTo(unsubscribedChatId))
                .as("the imported TELEGRAM preference, written off, suppresses delivery")
                .isEmpty();
    }

    // -------------------------------------------------------------- fixtures

    private String fixtureContent() throws IOException {
        return Files.readString(FIXTURE, StandardCharsets.UTF_8);
    }

    private UUID seedTelegramInstallation(UUID tenantId, UUID brandId, String environmentCode) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'SendPulse Import Fixture', 'SendPulse Import Fixture', 'UZS',
                    'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "sendpulse-" + tenantId.toString().substring(0, 8))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("slug", "brand-" + brandId.toString().substring(0, 8))
                .update();
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type, base_url,
                    is_production, egress_allowlist)
                VALUES (:code, 'NOTIFICATION', 'TELEGRAM_BOT_API', :baseUrl, false, '127.0.0.1')
                ON CONFLICT (code) DO UPDATE SET base_url = EXCLUDED.base_url
                """)
                .param("code", environmentCode)
                .param("baseUrl", bot.baseUrl())
                .update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, brand_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, :brandId, 'NOTIFICATION', 'TELEGRAM_BOT_API', :env,
                        'Test bot', 'ACTIVE', :secret, :secret)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("env", environmentCode)
                .param("secret", "horecaos:local:provider_notification:platform:telegram-bot")
                .update();
        return id;
    }

    private void activateTelegramMarketingTemplate(UUID tenantId, UUID brandId, String templateKey) {
        UUID templateId = templates.createTemplate(
                tenantId,
                brandId,
                templateKey,
                NotificationClass.TRANSACTIONAL_OPTIONAL,
                NotificationChannel.TELEGRAM,
                SendPulseContactImportRowService.CONSENT_PURPOSE);
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required().forEach(locale -> wordings.put(locale, new Wording(null, "SendPulse import proof")));
        int version = templates.addVersion(tenantId, templateId, wordings, Map.of());
        templates.activate(tenantId, templateId, version, "test");
    }

    private void createIntent(UUID tenantId, UUID brandId, UUID orderId, String templateKey) {
        boolean created = notifications.createIntent(new NewNotification(
                UUID.randomUUID(),
                tenantId,
                brandId,
                null,
                NotificationClass.TRANSACTIONAL_OPTIONAL.name(),
                NotificationChannel.TELEGRAM.name(),
                templateKey,
                "ORDER",
                orderId,
                null,
                UUID.randomUUID(),
                "idem-" + orderId,
                objectMapper.writeValueAsString(Map.of()),
                clock.instant(),
                clock.instant().plus(Duration.ofHours(6)),
                clock.instant()));
        assertThat(created).isTrue();
    }

    private void drainUntilQuiet() {
        for (int round = 0; round < 5 && nonTerminalTelegramCount() > 0; round++) {
            forceAllDue();
            worker.drain();
        }
    }

    private long nonTerminalTelegramCount() {
        Long count = jdbc.sql("""
                SELECT count(*) FROM notifications.notifications
                WHERE channel = 'TELEGRAM'
                  AND status NOT IN ('DELIVERED', 'FAILED_TERMINAL', 'EXPIRED', 'SUPPRESSED', 'MANUAL_REVIEW')
                """).query(Long.class).single();
        return count == null ? 0 : count;
    }

    private void forceAllDue() {
        jdbc.sql("UPDATE notifications.notifications SET next_attempt_at = :now WHERE channel = 'TELEGRAM'")
                .param("now", java.time.OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE integration.sendpulse_import_run_rows, integration.sendpulse_import_runs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.telegram_tracked_messages, integration.telegram_chat_locks, "
                        + "integration.telegram_binding_events, integration.telegram_bindings, "
                        + "integration.telegram_pending_links, integration.bot_action_tokens CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE notifications.delivery_status_events, notifications.delivery_attempts, "
                        + "notifications.notifications, notifications.recipient_endpoints, "
                        + "notifications.notification_preferences, "
                        + "notifications.template_versions, notifications.templates CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions, customer.contact_points, "
                        + "customer.principal_links, customer.brand_profiles, customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.binding_capabilities, integration.bindings, "
                        + "integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
    }

    // ----------------------------------------------------------------- reads

    private long customerAccountCount(UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM customer.customer_accounts WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();
    }

    private long telegramBindingCount(UUID tenantId) {
        return jdbc.sql("""
                SELECT count(*) FROM integration.telegram_bindings WHERE tenant_id = :tenantId AND retired_at IS NULL
                """).param("tenantId", tenantId).query(Long.class).single();
    }

    private long consentDecisionCount(UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM customer.consent_decisions WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();
    }

    private long principalLinkCount(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM customer.principal_links WHERE customer_account_id = :accountId")
                .param("accountId", accountId)
                .query(Long.class)
                .single();
    }

    private long contactPointCount(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM customer.contact_points WHERE customer_account_id = :accountId")
                .param("accountId", accountId)
                .query(Long.class)
                .single();
    }

    private long runRowCount(UUID runId) {
        return jdbc.sql("SELECT count(*) FROM integration.sendpulse_import_run_rows WHERE run_id = :runId")
                .param("runId", runId)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> latestConsent(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT decision, source, purpose, channel, evidence_reference, decided_at
                FROM customer.consent_decisions
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                ORDER BY decided_at DESC LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query()
                .singleRow();
    }

    private Map<String, Object> importStoreRun(UUID tenantId, UUID runId) {
        return jdbc.sql("""
                SELECT status, imported_by_principal_id FROM integration.sendpulse_import_runs
                WHERE tenant_id = :tenantId AND id = :runId
                """)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .query()
                .singleRow();
    }

    private SecretResolver secretResolver() {
        return new EnvironmentSecretResolver(key -> "a-test-bot-token", clock);
    }

    // ------------------------------------------------------------ test doubles

    private static final class NoOpContactDirectory
            implements uz.horecaos.platform.customers.api.RecipientContactDirectory {
        @Override
        public Optional<ContactEndpoint> primaryContact(UUID tenantId, UUID accountId, ContactMethod method) {
            return Optional.empty();
        }

        @Override
        public Optional<String> resolveValue(UUID tenantId, UUID contactPointId, String purpose) {
            return Optional.empty();
        }

        @Override
        public Optional<String> preferredLocale(UUID tenantId, UUID accountId) {
            return Optional.empty();
        }
    }

    private static final class StubOrderDirectory implements OrderDirectory {
        private final Map<UUID, OrderSummary> summaries = new ConcurrentHashMap<>();

        void publish(OrderSummary summary) {
            summaries.put(summary.orderId(), summary);
        }

        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return Optional.ofNullable(summaries.get(orderId))
                    .filter(row -> row.tenantId().equals(tenantId));
        }
    }
}
