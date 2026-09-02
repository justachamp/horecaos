package uz.horecaos.platform.conversations.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.api.StopListPort;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.EntitlementSnapshot;
import uz.horecaos.platform.commercial.api.LimitCheck;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.integration.camel.notification.telegram.FakeTelegramBotApi;
import uz.horecaos.platform.integration.provider.telegram.BotActionTokenStore;
import uz.horecaos.platform.integration.provider.telegram.BotCallbackAuthorizer;
import uz.horecaos.platform.integration.provider.telegram.TelegramAuthLinkService;
import uz.horecaos.platform.integration.provider.telegram.TelegramBindingStore;
import uz.horecaos.platform.integration.provider.telegram.TelegramBotApiClient;
import uz.horecaos.platform.integration.provider.telegram.TelegramChatLockService;
import uz.horecaos.platform.integration.provider.telegram.TelegramConversationOutboundGateway;
import uz.horecaos.platform.integration.provider.telegram.TelegramCustomerLinkService;
import uz.horecaos.platform.integration.provider.telegram.TelegramInstallationBrandLookup;
import uz.horecaos.platform.integration.provider.telegram.TelegramLinkService;
import uz.horecaos.platform.integration.provider.telegram.TelegramRightsVerifier;
import uz.horecaos.platform.integration.provider.telegram.TelegramStaffLinkService;
import uz.horecaos.platform.integration.provider.telegram.TelegramUpdateDedupStore;
import uz.horecaos.platform.integration.provider.telegram.TelegramUpdateHandler;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup.WebhookInstallation;
import uz.horecaos.platform.inventory.api.StockAvailabilityPort;
import uz.horecaos.platform.notifications.api.CustomerProviderBindingSync;
import uz.horecaos.platform.notifications.application.CustomerProviderBindingSyncService;
import uz.horecaos.platform.notifications.application.NotificationPreferenceService;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.ordering.api.OrderDecisionPort;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0059 stage 1's proving suite: the welcome series, end to end, through
 * the real {@code TelegramUpdateHandler} entry point (not a mock of it) —
 * the same {@code TelegramInteractiveBotIntegrationTest}/{@code
 * FakeTelegramBotApi} genre ADR 0058/0060 established, extended with the
 * conversations engine wired in exactly the way {@code integration} wires it
 * in production, minus the parts (staff linking, order decisions, typed
 * commands) this suite does not exercise and stubs out honestly.
 */
class WelcomeFlowIntegrationTest {

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ObjectMapper objectMapper;
    private MutableClock clock;
    private FakeTelegramBotApi bot;
    private FlowDocumentService flowDocuments;
    private TogglableEntitlementService entitlements;
    private TelegramUpdateHandler updateHandler;
    private FlowRunResumeSweeper sweeper;
    private FieldProtection protection;

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
        clock = new MutableClock(Instant.parse("2026-08-31T09:00:00Z"));
        AuditRecorder audit = new JdbcAuditRecorder(jdbc, objectMapper);

        protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get,
                        clock),
                "local"));

        ConversationRepository conversations = new ConversationRepository(jdbc, clock);
        FlowRunRepository runs = new FlowRunRepository(jdbc, clock, protection, objectMapper);
        ConversationMessageStore messages = new ConversationMessageStore(jdbc, clock, protection);
        flowDocuments = new FlowDocumentService(new FlowDocumentRepository(jdbc, clock), audit, clock);

        SecretResolver secrets = new EnvironmentSecretResolver(key -> "a-test-bot-token", clock);
        TelegramWebhookInstallationLookup installationLookup = new TelegramWebhookInstallationLookup(jdbc);
        TelegramChatLockService locks = new TelegramChatLockService(jdbc, clock);
        TelegramBotApiClient botApiClient = new TelegramBotApiClient(objectMapper);
        TelegramConversationOutboundGateway outboundGateway = new TelegramConversationOutboundGateway(
                installationLookup, locks, botApiClient, secrets, Duration.ofSeconds(20));

        ConversationEngine engine = new ConversationEngine(
                conversations, runs, messages, flowDocuments, outboundGateway, clock, "https://storefront.example");
        sweeper = new FlowRunResumeSweeper(runs, conversations, flowDocuments, engine, clock, 100);

        TelegramBindingStore bindings = new TelegramBindingStore(jdbc, clock, audit);
        TelegramStaffLinkService staffLinks = new TelegramStaffLinkService(jdbc, clock, Duration.ofMinutes(15));
        CustomerProviderBindingSync bindingSync = new CustomerProviderBindingSyncService(
                new JdbcNotificationStore(jdbc),
                new NotificationPreferenceService(new JdbcNotificationStore(jdbc), clock));
        TelegramCustomerLinkService customerLinks =
                new TelegramCustomerLinkService(jdbc, clock, Duration.ofMinutes(15), bindings, bindingSync, audit);
        BotActionTokenStore actionTokens = new BotActionTokenStore(jdbc, clock);
        entitlements = new TogglableEntitlementService();
        BotCallbackAuthorizer callbackAuthorizer = new BotCallbackAuthorizer(
                actionTokens,
                staffLinks,
                new ThrowingAuthorizationService(),
                entitlements,
                new ThrowingOrderDecisionPort(),
                clock);

        updateHandler = new TelegramUpdateHandler(
                new TelegramLinkService(jdbc, clock, Duration.ofMinutes(15)),
                staffLinks,
                customerLinks,
                new TelegramAuthLinkService(jdbc, clock, Duration.ofMinutes(15)),
                new uz.horecaos.platform.customers.NoOpCustomerTelegramSignIn(),
                new TelegramRightsVerifier(botApiClient),
                bindings,
                actionTokens,
                callbackAuthorizer,
                new ThrowingAuthorizationService(),
                entitlements,
                new NoSummaryOrderDirectory(),
                () -> java.util.List.of(),
                new NoOpContactDirectory(),
                new NoOpStopListPort(),
                new ThrowingStockAvailabilityPort(),
                botApiClient,
                secrets,
                audit,
                clock,
                "en",
                engine,
                new TelegramInstallationBrandLookup(jdbc),
                new TelegramUpdateDedupStore(jdbc, clock),
                new uz.horecaos.platform.web.cache.InProcessRateLimiter(clock),
                "^\\+?998\\d{9}$",
                Duration.ofHours(6));
    }

    // The welcome series itself, reproduced from the ADR's observed SendPulse
    // flow, with {{storefrontUrl}} folded into the greeting's own text (not
    // just the button) so a plain assertion on the sent message body proves
    // template-variable rendering — the message-classification-style check
    // this suite's own genre asks for.
    private static final String WELCOME_FLOW_YAML = """
            flowKey: welcome-series
            name: Welcome series
            startState: greeting
            states:
              greeting:
                type: buttons
                text: "Welcome! Order at {{storefrontUrl}}, or tell us what you think."
                buttons:
                  - label: "Order now"
                    kind: url
                    url: "{{storefrontUrl}}"
                  - label: "Leave feedback"
                    kind: callback
                    key: feedback
                    next: awaiting_feedback
              awaiting_feedback:
                type: input-to-field
                prompt: "Please type your feedback."
                field: feedback
                next: thank_you
              thank_you:
                type: message
                text: "Thank you for your feedback!"
            """;

    @Test
    @DisplayName(
            "a fresh /start gets the welcome series; tapping feedback then texting captures it, encrypted, and the thank-you arrives")
    void theWelcomeSeriesEndToEnd() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        long customerChatId = 55_501L;

        seedTenancy(tenant, brand);
        UUID installationId = seedTelegramInstallation(tenant, brand);
        entitlements.entitle(tenant);
        flowDocuments.author(tenant, brand, "welcome-series", WELCOME_FLOW_YAML, "Welcome", true, "author", "publish");

        updateHandler.handle(installation(installationId, tenant), bareStartUpdate(1, customerChatId));

        List<String> firstTurn = bot.messagesSentTo(customerChatId);
        assertThat(firstTurn).hasSize(1);
        assertThat(firstTurn.get(0))
                .as("the {{storefrontUrl}} placeholder is resolved, not sent verbatim")
                .contains("https://storefront.example")
                .doesNotContain("{{storefrontUrl}}");

        long messageId = requireNonNull(bot.lastMessageIdSentTo(customerChatId));
        List<String> tokens = bot.callbackDataOn(messageId);
        assertThat(tokens).hasSize(2);
        String feedbackToken = tokens.stream()
                .filter(token -> token.startsWith("cvb:"))
                .findFirst()
                .orElseThrow();
        assertThat(feedbackToken).isEqualTo("cvb:feedback");

        updateHandler.handle(
                installation(installationId, tenant),
                callbackQueryUpdate(2, "cbq-1", feedbackToken, customerChatId, messageId, customerChatId));

        assertThat(bot.answeredCallbackQueryIds()).contains("cbq-1");
        assertThat(bot.messagesSentTo(customerChatId)).hasSize(2);
        assertThat(bot.messagesSentTo(customerChatId).get(1)).contains("feedback");

        updateHandler.handle(
                installation(installationId, tenant), privateTextUpdate(3, customerChatId, "The plov was great!"));

        List<String> allMessages = bot.messagesSentTo(customerChatId);
        assertThat(allMessages).hasSize(3);
        assertThat(allMessages.get(2)).contains("Thank you");

        // The run completed and the conversation went back to IDLE.
        assertThat(flowRunStatus(tenant)).isEqualTo("COMPLETED");
        assertThat(conversationState(tenant, brand, customerChatId)).isEqualTo("IDLE");

        // The captured field is stored envelope-encrypted, never as plaintext.
        // Two INBOUND rows exist (the button tap, then the free text); the
        // clock does not advance within this test, so occurred_at alone
        // cannot order them — block_id, recorded from the state the engine
        // was actually at, disambiguates unambiguously.
        Map<String, Object> inboundRow =
                jdbc.sql("""
                        SELECT id, body_protected FROM conversations.conversation_messages
                        WHERE tenant_id = :tenantId AND direction = 'INBOUND' AND block_id = 'awaiting_feedback'
                        """).param("tenantId", tenant).query().singleRow();
        String protectedBody = java.util.Objects.requireNonNull((String) inboundRow.get("body_protected"));
        assertThat(protectedBody).doesNotContain("plov");
        UUID messageRowId = java.util.Objects.requireNonNull((UUID) inboundRow.get("id"));
        String revealed = protection.reveal(
                tenant,
                uz.horecaos.platform.iam.api.protection.ProtectedValue.deserialize(protectedBody),
                new FieldProtection.RecordRef(
                        ConversationMessageStore.TABLE, ConversationMessageStore.BODY_COLUMN, messageRowId),
                "test");
        assertThat(revealed).isEqualTo("The plov was great!");
    }

    @Test
    @DisplayName("a redelivered update_id sends no second message (ADR 0032 dedup)")
    void duplicateUpdateIdSendsNothingTwice() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        long chatId = 55_502L;

        seedTenancy(tenant, brand);
        UUID installationId = seedTelegramInstallation(tenant, brand);
        entitlements.entitle(tenant);
        flowDocuments.author(tenant, brand, "welcome-series", WELCOME_FLOW_YAML, "Welcome", true, "author", "publish");

        Map<String, Object> update = bareStartUpdate(1, chatId);
        updateHandler.handle(installation(installationId, tenant), update);
        updateHandler.handle(installation(installationId, tenant), update);

        assertThat(bot.messagesSentTo(chatId)).hasSize(1);
    }

    @Test
    @DisplayName("re-executing a block via a repeat trigger sends no second message (idempotent block execution)")
    void redeliveredTriggerSendsNoSecondMessage() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        long chatId = 55_503L;

        seedTenancy(tenant, brand);
        UUID installationId = seedTelegramInstallation(tenant, brand);
        entitlements.entitle(tenant);
        flowDocuments.author(tenant, brand, "welcome-series", WELCOME_FLOW_YAML, "Welcome", true, "author", "publish");

        // Two distinct update_ids (dedup does not catch this), both a bare
        // /start on the same chat — the engine's own CAS on flow_runs is
        // what has to stop the second one from restarting the flow.
        updateHandler.handle(installation(installationId, tenant), bareStartUpdate(1, chatId));
        updateHandler.handle(installation(installationId, tenant), bareStartUpdate(2, chatId));

        assertThat(bot.messagesSentTo(chatId))
                .as("a repeat bare /start on an already-running flow must not restart it")
                .hasSize(1);
    }

    @Test
    @DisplayName("re-executing the exact same run transition loses the race and sends nothing (CAS mechanism)")
    void staleVersionAdvanceLosesTheRace() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        seedTenancy(tenant, brand);
        UUID installationId = seedTelegramInstallation(tenant, brand);

        FlowDocumentRepository documentRepo = new FlowDocumentRepository(jdbc, clock);
        FlowDocumentRepository.Row document =
                documentRepo.insert(tenant, brand, "test-flow", 1, "irrelevant", null, "author", true);

        ConversationRepository conversations = new ConversationRepository(jdbc, clock);
        ConversationRepository.Row conversation =
                conversations.getOrCreate(new uz.horecaos.platform.conversations.api.ConversationChannelRef(
                        tenant,
                        brand,
                        installationId,
                        uz.horecaos.platform.conversations.api.ChannelKind.TELEGRAM,
                        999_001L,
                        null));

        FlowRunRepository runs = new FlowRunRepository(jdbc, clock, protection, objectMapper);
        FlowRunRepository.Row run = runs.start(tenant, conversation.id(), document.id(), 1, "start");

        boolean first = runs.advance(tenant, run.id(), run.version(), "next", null, null, null, Map.of());
        boolean second = runs.advance(tenant, run.id(), run.version(), "next", null, null, null, Map.of());

        assertThat(first).as("the first attempt at this transition wins").isTrue();
        assertThat(second)
                .as("a redelivered attempt at the same transition, now stale, loses")
                .isFalse();
    }

    @Test
    @DisplayName("an unentitled tenant's /start is today's unchanged silence")
    void unentitledTenantGetsTodaysSilence() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        long chatId = 55_504L;

        seedTenancy(tenant, brand);
        UUID installationId = seedTelegramInstallation(tenant, brand);
        // Deliberately not entitled.
        flowDocuments.author(tenant, brand, "welcome-series", WELCOME_FLOW_YAML, "Welcome", true, "author", "publish");

        updateHandler.handle(installation(installationId, tenant), bareStartUpdate(1, chatId));

        assertThat(bot.messagesSentTo(chatId)).isEmpty();
    }

    @Test
    @DisplayName("an operator-handoff block parks the conversation and the engine stops answering")
    void operatorHandoffParksTheConversation() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        long chatId = 55_505L;

        seedTenancy(tenant, brand);
        UUID installationId = seedTelegramInstallation(tenant, brand);
        entitlements.entitle(tenant);
        flowDocuments.author(tenant, brand, "welcome-series", """
                flowKey: welcome-series
                name: Handoff
                startState: greeting
                states:
                  greeting:
                    type: operator-handoff
                    message: "Connecting you to a team member."
                """, "Handoff", true, "author", "publish");

        updateHandler.handle(installation(installationId, tenant), bareStartUpdate(1, chatId));

        assertThat(bot.messagesSentTo(chatId)).hasSize(1);
        assertThat(bot.messagesSentTo(chatId).get(0)).contains("Connecting");
        assertThat(conversationState(tenant, brand, chatId)).isEqualTo("HANDED_TO_OPERATOR");
        assertThat(flowRunStatus(tenant)).isEqualTo("HANDED_TO_OPERATOR");

        // The engine stops answering: free text after a handoff gets nothing.
        updateHandler.handle(installation(installationId, tenant), privateTextUpdate(2, chatId, "hello?"));
        assertThat(bot.messagesSentTo(chatId)).hasSize(1);
    }

    @Test
    @DisplayName("a delay block resumes only once the clock has actually advanced past it")
    void delayBlockResumesAfterTheClockAdvances() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        long chatId = 55_506L;

        seedTenancy(tenant, brand);
        UUID installationId = seedTelegramInstallation(tenant, brand);
        entitlements.entitle(tenant);
        flowDocuments.author(tenant, brand, "welcome-series", """
                flowKey: welcome-series
                name: Delayed
                startState: greeting
                states:
                  greeting:
                    type: message
                    text: "Hold on a moment."
                    next: waiting
                  waiting:
                    type: delay
                    duration: PT1H
                    next: later
                  later:
                    type: message
                    text: "Thanks for waiting!"
                """, "Delayed", true, "author", "publish");

        updateHandler.handle(installation(installationId, tenant), bareStartUpdate(1, chatId));
        assertThat(bot.messagesSentTo(chatId))
                .as("the delay block arms and waits — it does not itself send")
                .hasSize(1);

        int resumedBeforeDue = sweeper.runOnce();
        assertThat(resumedBeforeDue).isZero();
        assertThat(bot.messagesSentTo(chatId)).hasSize(1);

        clock.advance(Duration.ofHours(1).plusSeconds(1));

        int resumedAfterDue = sweeper.runOnce();
        assertThat(resumedAfterDue).isEqualTo(1);
        List<String> messages = bot.messagesSentTo(chatId);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1)).contains("Thanks for waiting");
        assertThat(flowRunStatus(tenant)).isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------- helpers

    private static long requireNonNull(@Nullable Long value) {
        assertThat(value).isNotNull();
        return java.util.Objects.requireNonNull(value);
    }

    private String flowRunStatus(UUID tenantId) {
        return jdbc.sql("SELECT status FROM conversations.flow_runs WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .single();
    }

    private String conversationState(UUID tenantId, UUID brandId, long chatId) {
        return jdbc.sql("""
                SELECT state FROM conversations.conversations
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND channel_chat_id = :chatId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("chatId", chatId)
                .query(String.class)
                .single();
    }

    private WebhookInstallation installation(UUID installationId, UUID tenantId) {
        return new WebhookInstallation(
                installationId, tenantId, "TELEGRAM_BOT_API", "ACTIVE", bot.baseUrl(), secretRef(), secretRef());
    }

    private static String secretRef() {
        return "horecaos:local:provider_notification:platform:telegram-bot";
    }

    private static Map<String, Object> bareStartUpdate(long updateId, long userId) {
        return Map.of(
                "update_id",
                updateId,
                "message",
                Map.of(
                        "text", "/start",
                        "chat", Map.of("id", userId, "type", "private"),
                        "from", Map.of("id", userId)));
    }

    private static Map<String, Object> privateTextUpdate(long updateId, long userId, String text) {
        return Map.of(
                "update_id",
                updateId,
                "message",
                Map.of(
                        "text", text,
                        "chat", Map.of("id", userId, "type", "private"),
                        "from", Map.of("id", userId)));
    }

    private static Map<String, Object> callbackQueryUpdate(
            long updateId, String callbackQueryId, String token, long chatId, long messageId, long fromUserId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("message_id", messageId);
        message.put("chat", Map.of("id", chatId));
        return Map.of(
                "update_id",
                updateId,
                "callback_query",
                Map.of(
                        "id", callbackQueryId,
                        "from", Map.of("id", fromUserId),
                        "message", message,
                        "data", token));
    }

    private void seedTenancy(UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", "tenant-" + tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'BRAND', :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("slug", "brand-" + brandId)
                .update();
    }

    /** Sets {@code installations.brand_id} — the V0108 fallback a chat with no binding yet resolves through. */
    private UUID seedTelegramInstallation(UUID tenantId, UUID brandId) {
        UUID id = UUID.randomUUID();
        String environmentCode = "welcome-flow-test-" + id;
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'NOTIFICATION', 'TELEGRAM_BOT_API', :baseUrl, false, '127.0.0.1')
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
                .param("secret", secretRef())
                .update();
        return id;
    }

    private void truncate() {
        // A single cascading truncate is sufficient here: this suite never
        // calls RoleRegistrySynchronizer (nothing exercises real
        // authorization) and uses an in-memory entitlement stub rather than
        // the commercial schema, so tenant.tenants is not precious the way
        // it is in the wave-6/7 suites that seed platform roles once.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }

    // ---------------------------------------------------------------- fakes

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class NoOpContactDirectory implements RecipientContactDirectory {
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

    private static final class NoSummaryOrderDirectory implements OrderDirectory {
        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return Optional.empty();
        }
    }

    private static final class NoOpStopListPort implements StopListPort {
        @Override
        public List<Item> listAtLocation(UUID tenantId, UUID brandId, UUID locationId) {
            return List.of();
        }
    }

    private static final class ThrowingStockAvailabilityPort implements StockAvailabilityPort {
        @Override
        public void toggle(
                UUID tenantId,
                UUID locationId,
                UUID variantId,
                boolean available,
                String reasonCode,
                String actorSubject) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }
    }

    private static final class ThrowingOrderDecisionPort implements OrderDecisionPort {
        @Override
        public Decision decide(UUID tenantId, UUID orderId, DecisionCommand command) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }
    }

    private static final class ThrowingAuthorizationService implements AuthorizationService {
        @Override
        public boolean has(String subject, Capability capability, ResourceScope scope) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public void require(String subject, Capability capability, ResourceScope scope) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public CapabilityView viewFor(String subject, UUID tenantId) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }
    }

    /** Real ADR 0021 gating behaviour for {@code featureEnabled}; every other method is unexercised by this suite. */
    private static final class TogglableEntitlementService implements EntitlementService {
        private final Set<UUID> entitledTenants = ConcurrentHashMap.newKeySet();

        void entitle(UUID tenantId) {
            entitledTenants.add(tenantId);
        }

        @Override
        public EntitlementSnapshot snapshot(UUID tenantId) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public LimitCheck check(UUID tenantId, EntitlementKey<Long> key, long requested) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public LimitCheck require(UUID tenantId, EntitlementKey<Long> key, long requested) {
            throw new UnsupportedOperationException("not exercised by this suite");
        }

        @Override
        public boolean featureEnabled(UUID tenantId, EntitlementKey<Boolean> key) {
            if (key == EntitlementKeys.TELEGRAM_CONVERSATIONS_ENABLED) {
                return entitledTenants.contains(tenantId);
            }
            return true;
        }

        @Override
        public void requireFeature(UUID tenantId, EntitlementKey<Boolean> key) {}
    }
}
