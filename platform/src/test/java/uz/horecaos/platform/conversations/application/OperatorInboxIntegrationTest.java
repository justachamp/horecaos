package uz.horecaos.platform.conversations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
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
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0059 stage 2's proving suite, in the {@link WelcomeFlowIntegrationTest}
 * genre: the same hand-wired {@code TelegramUpdateHandler} entry point, with
 * {@link ConversationInboxService} wired in exactly as production wires it,
 * proving the whole operator loop end to end — handoff, list, audited
 * history, reply, staying quiet while parked, return-to-flow, close, and
 * reopening.
 */
class OperatorInboxIntegrationTest {

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ObjectMapper objectMapper;
    private MutableClock clock;
    private FakeTelegramBotApi bot;
    private FlowDocumentService flowDocuments;
    private ConversationRepository conversations;
    private FlowRunRepository runs;
    private ConversationMessageStore messages;
    private ConversationInboxService inbox;
    private TogglableEntitlementService entitlements;
    private TelegramUpdateHandler updateHandler;

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
        clock = new MutableClock(Instant.parse("2026-09-01T09:00:00Z"));
        AuditRecorder audit = new JdbcAuditRecorder(jdbc, objectMapper);

        FieldProtection protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get,
                        clock),
                "local"));

        conversations = new ConversationRepository(jdbc, clock);
        runs = new FlowRunRepository(jdbc, clock, protection, objectMapper);
        messages = new ConversationMessageStore(jdbc, clock, protection);
        flowDocuments = new FlowDocumentService(new FlowDocumentRepository(jdbc, clock), audit, clock);

        SecretResolver secrets = new EnvironmentSecretResolver(key -> "a-test-bot-token", clock);
        TelegramWebhookInstallationLookup installationLookup = new TelegramWebhookInstallationLookup(jdbc);
        TelegramChatLockService locks = new TelegramChatLockService(jdbc, clock);
        TelegramBotApiClient botApiClient = new TelegramBotApiClient(objectMapper);
        TelegramConversationOutboundGateway outboundGateway = new TelegramConversationOutboundGateway(
                installationLookup, locks, botApiClient, secrets, Duration.ofSeconds(20));

        ConversationEngine engine = new ConversationEngine(
                conversations, runs, messages, flowDocuments, outboundGateway, clock, "https://storefront.example");
        entitlements = new TogglableEntitlementService();
        inbox = new ConversationInboxService(
                conversations, runs, messages, flowDocuments, outboundGateway, engine, audit, entitlements, clock);

        TelegramBindingStore bindings = new TelegramBindingStore(jdbc, clock, audit);
        TelegramStaffLinkService staffLinks = new TelegramStaffLinkService(jdbc, clock, Duration.ofMinutes(15));
        CustomerProviderBindingSync bindingSync = new CustomerProviderBindingSyncService(
                new JdbcNotificationStore(jdbc),
                new NotificationPreferenceService(new JdbcNotificationStore(jdbc), clock));
        TelegramCustomerLinkService customerLinks =
                new TelegramCustomerLinkService(jdbc, clock, Duration.ofMinutes(15), bindings, bindingSync, audit);
        BotActionTokenStore actionTokens = new BotActionTokenStore(jdbc, clock);
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
                "^\\+?998\\d{9}$");
    }

    private static final String HANDOFF_WITH_RETURN_YAML = """
            flowKey: welcome-series
            name: Handoff with return
            startState: greeting
            states:
              greeting:
                type: operator-handoff
                message: "Connecting you to a team member."
                next: welcome_back
              welcome_back:
                type: message
                text: "Welcome back! Thanks for waiting."
            """;

    @Test
    @DisplayName("handoff -> needs-attention list -> audited history -> reply -> parked message recorded -> "
            + "return-to-flow resumes and answers -> close -> a new message reopens it")
    void theFullOperatorLoop() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        long chatId = 77_001L;

        seedTenancy(tenant, brand);
        UUID installationId = seedTelegramInstallation(tenant, brand);
        entitlements.entitle(tenant);
        flowDocuments.author(
                tenant, brand, "welcome-series", HANDOFF_WITH_RETURN_YAML, "Handoff", true, "author", "publish");

        // ---- the flow hands off immediately (startState is the handoff itself)
        updateHandler.handle(installationRef(installationId, tenant), bareStartUpdate(1, chatId));
        assertThat(bot.messagesSentTo(chatId)).hasSize(1);
        assertThat(bot.messagesSentTo(chatId).get(0)).contains("Connecting");
        assertThat(conversationState(tenant, brand, chatId)).isEqualTo("HANDED_TO_OPERATOR");

        // ---- it appears in the needs-attention list, and the list carries no body
        List<ConversationSummaryView> firstList = inbox.list(tenant, brand, 100);
        assertThat(firstList).hasSize(1);
        ConversationSummaryView row = firstList.get(0);
        assertThat(row.state()).isEqualTo("HANDED_TO_OPERATOR");
        assertThat(row.needsReply()).isTrue();
        assertThat(row.toString())
                .as("the list payload must never carry a decrypted message body (ADR 0059 stage 2)")
                .doesNotContain("Connecting you");
        UUID conversationId = row.id();

        // ---- a customer message while parked is recorded, and the engine stays quiet
        updateHandler.handle(
                installationRef(installationId, tenant), privateTextUpdate(2, chatId, "Hello, anyone there?"));
        assertThat(bot.messagesSentTo(chatId))
                .as("the engine must not answer a parked conversation")
                .hasSize(1);

        // ---- the first open of the history by one operator writes exactly one audit fact
        ConversationInboxService.ConversationHistory firstOpen =
                inbox.history(tenant, brand, conversationId, "operator-a");
        assertThat(firstOpen.messages())
                .extracting(ConversationMessageView::body)
                .contains("Hello, anyone there?", "Connecting you to a team member.");
        assertThat(auditFactCount(tenant, "conversation.history.read", "operator-a"))
                .isEqualTo(1);

        // ---- polling the same open thread by the same operator does not repeat the fact
        inbox.history(tenant, brand, conversationId, "operator-a");
        assertThat(auditFactCount(tenant, "conversation.history.read", "operator-a"))
                .isEqualTo(1);

        // ---- a different operator's first open writes its own fact
        inbox.history(tenant, brand, conversationId, "operator-b");
        assertThat(auditFactCount(tenant, "conversation.history.read", "operator-b"))
                .isEqualTo(1);

        // ---- the operator replies; it reaches the chat and lands in history with the real actor
        ConversationMessageView sent =
                inbox.reply(tenant, brand, conversationId, "operator-a", "We're here, how can we help?");
        assertThat(sent.direction()).isEqualTo("OPERATOR");
        assertThat(sent.actorPrincipalId()).isEqualTo("operator-a");
        assertThat(bot.messagesSentTo(chatId)).hasSize(2);
        assertThat(bot.messagesSentTo(chatId).get(1)).isEqualTo("We're here, how can we help?");
        assertThat(auditFactCount(tenant, "conversation.reply.sent", "operator-a"))
                .isEqualTo(1);

        // ---- return-to-flow resumes at the handoff's declared next and the engine answers again
        long versionBeforeReturn =
                conversations.findById(tenant, conversationId).orElseThrow().version();
        inbox.returnToFlow(tenant, brand, conversationId, versionBeforeReturn, "operator-a");
        assertThat(bot.messagesSentTo(chatId)).hasSize(3);
        assertThat(bot.messagesSentTo(chatId).get(2)).contains("Welcome back");
        // welcome_back has no next of its own, so the flow completes and the
        // conversation settles at IDLE rather than staying FLOW_ACTIVE forever.
        assertThat(conversationState(tenant, brand, chatId)).isEqualTo("IDLE");
        assertThat(auditFactCount(tenant, "conversation.returned_to_flow", "operator-a"))
                .isEqualTo(1);

        // ---- close, then a new inbound message reopens it rather than being met with silence
        long versionBeforeClose =
                conversations.findById(tenant, conversationId).orElseThrow().version();
        ConversationView closed =
                inbox.close(tenant, brand, conversationId, versionBeforeClose, "operator-a", "resolved");
        assertThat(closed.state()).isEqualTo("CLOSED");
        assertThat(auditFactCount(tenant, "conversation.closed", "operator-a")).isEqualTo(1);

        updateHandler.handle(installationRef(installationId, tenant), privateTextUpdate(3, chatId, "Still there?"));
        assertThat(conversationState(tenant, brand, chatId))
                .as("a message on a closed conversation must reopen it rather than vanish")
                .isEqualTo("HANDED_TO_OPERATOR");
        ConversationInboxService.ConversationHistory afterReopen =
                inbox.history(tenant, brand, conversationId, "operator-a");
        assertThat(afterReopen.messages())
                .extracting(ConversationMessageView::body)
                .contains("Still there?");
    }

    @Test
    @DisplayName("takeover parks a FLOW_ACTIVE conversation and assigns it; the engine then stays quiet")
    void takeoverParksAMidFlowConversation() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        long chatId = 77_002L;

        seedTenancy(tenant, brand);
        UUID installationId = seedTelegramInstallation(tenant, brand);
        entitlements.entitle(tenant);
        flowDocuments.author(tenant, brand, "welcome-series", """
                flowKey: welcome-series
                name: Waiting on a tap
                startState: greeting
                states:
                  greeting:
                    type: buttons
                    text: "Welcome! Tell us what you'd like."
                    buttons:
                      - label: "Talk to someone"
                        kind: callback
                        key: help
                        next: routed
                  routed:
                    type: message
                    text: "Routing you now."
                """, "Waiting", true, "author", "publish");

        updateHandler.handle(installationRef(installationId, tenant), bareStartUpdate(1, chatId));
        assertThat(conversationState(tenant, brand, chatId)).isEqualTo("FLOW_ACTIVE");
        assertThat(flowRunStatus(tenant)).isEqualTo("ACTIVE");

        UUID conversationId = conversations
                .find(tenant, brand, uz.horecaos.platform.conversations.api.ChannelKind.TELEGRAM, chatId)
                .orElseThrow()
                .id();
        long version =
                conversations.findById(tenant, conversationId).orElseThrow().version();

        ConversationView takenOver =
                inbox.takeover(tenant, brand, conversationId, version, "operator-c", "stepping in");
        assertThat(takenOver.state()).isEqualTo("HANDED_TO_OPERATOR");
        assertThat(takenOver.assignedTo()).isEqualTo("operator-c");
        assertThat(flowRunStatus(tenant)).isEqualTo("HANDED_TO_OPERATOR");
        assertThat(auditFactCount(tenant, "conversation.takeover", "operator-c"))
                .isEqualTo(1);

        // The engine must not advance the flow underneath the takeover.
        updateHandler.handle(
                installationRef(installationId, tenant),
                callbackQueryUpdate(2, "cbq-taken", "cvb:help", chatId, lastMessageId(chatId), chatId));
        assertThat(conversationState(tenant, brand, chatId)).isEqualTo("HANDED_TO_OPERATOR");
    }

    @Test
    @DisplayName("takeover is refused on a conversation that is not FLOW_ACTIVE")
    void takeoverRefusedOutsideFlowActive() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        seedTenancy(tenant, brand);
        UUID installationId = seedTelegramInstallation(tenant, brand);
        UUID conversationId = conversations
                .getOrCreate(new uz.horecaos.platform.conversations.api.ConversationChannelRef(
                        tenant,
                        brand,
                        installationId,
                        uz.horecaos.platform.conversations.api.ChannelKind.TELEGRAM,
                        77_003L,
                        null))
                .id();

        assertThatThrownBy(() -> inbox.takeover(tenant, brand, conversationId, 0, "operator-d", null))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    @DisplayName("reply is refused on a conversation not currently HANDED_TO_OPERATOR")
    void replyRefusedOutsideHandedToOperator() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        seedTenancy(tenant, brand);
        UUID installationId = seedTelegramInstallation(tenant, brand);
        entitlements.entitle(tenant);
        UUID conversationId = conversations
                .getOrCreate(new uz.horecaos.platform.conversations.api.ConversationChannelRef(
                        tenant,
                        brand,
                        installationId,
                        uz.horecaos.platform.conversations.api.ChannelKind.TELEGRAM,
                        77_004L,
                        null))
                .id();

        assertThatThrownBy(() -> inbox.reply(tenant, brand, conversationId, "operator-e", "hi"))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    @DisplayName("an unentitled tenant's inbox is simply empty, and a reply is refused once the entitlement is off")
    void unentitledTenantBehavesDeliberately() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        long chatId = 77_005L;
        seedTenancy(tenant, brand);
        UUID installationId = seedTelegramInstallation(tenant, brand);
        // Deliberately not entitled.
        flowDocuments.author(
                tenant, brand, "welcome-series", HANDOFF_WITH_RETURN_YAML, "Handoff", true, "author", "publish");

        updateHandler.handle(installationRef(installationId, tenant), bareStartUpdate(1, chatId));
        assertThat(bot.messagesSentTo(chatId)).isEmpty();
        assertThat(inbox.list(tenant, brand, 100))
                .as("no conversation was ever created for an unentitled tenant, so the inbox is simply empty")
                .isEmpty();

        // Entitle just long enough to reach HANDED_TO_OPERATOR, then withdraw the
        // entitlement and confirm a reply is refused rather than silently sent.
        entitlements.entitle(tenant);
        updateHandler.handle(installationRef(installationId, tenant), bareStartUpdate(2, chatId));
        assertThat(conversationState(tenant, brand, chatId)).isEqualTo("HANDED_TO_OPERATOR");
        UUID conversationId = conversations
                .find(tenant, brand, uz.horecaos.platform.conversations.api.ChannelKind.TELEGRAM, chatId)
                .orElseThrow()
                .id();
        entitlements.disentitle(tenant);

        assertThatThrownBy(() -> inbox.reply(tenant, brand, conversationId, "operator-f", "hello"))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).errorCode())
                .isEqualTo(ErrorCode.ENTITLEMENT_REQUIRED);
    }

    // ------------------------------------------------------------------- helpers

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

    private long auditFactCount(UUID tenantId, String actionCode, String actorSubject) {
        return jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                WHERE tenant_id = :tenantId AND action_code = :actionCode AND actor_subject = :actor
                """)
                .param("tenantId", tenantId)
                .param("actionCode", actionCode)
                .param("actor", actorSubject)
                .query(Long.class)
                .single();
    }

    private long lastMessageId(long chatId) {
        Long id = bot.lastMessageIdSentTo(chatId);
        assertThat(id).isNotNull();
        return java.util.Objects.requireNonNull(id);
    }

    private WebhookInstallation installationRef(UUID installationId, UUID tenantId) {
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
        Map<String, Object> message = new java.util.LinkedHashMap<>();
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

    private UUID seedTelegramInstallation(UUID tenantId, UUID brandId) {
        UUID id = UUID.randomUUID();
        String environmentCode = "operator-inbox-test-" + id;
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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
    }

    // ---------------------------------------------------------------- fakes

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
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

    /** Real ADR 0021 gating behaviour for {@code featureEnabled}/{@code requireFeature}; everything else is unexercised. */
    private static final class TogglableEntitlementService implements EntitlementService {
        private final Set<UUID> entitledTenants = ConcurrentHashMap.newKeySet();

        void entitle(UUID tenantId) {
            entitledTenants.add(tenantId);
        }

        void disentitle(UUID tenantId) {
            entitledTenants.remove(tenantId);
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
        public void requireFeature(UUID tenantId, EntitlementKey<Boolean> key) {
            if (key == EntitlementKeys.TELEGRAM_CONVERSATIONS_ENABLED && !entitledTenants.contains(tenantId)) {
                throw new ApiException(
                        ErrorCode.ENTITLEMENT_REQUIRED, "The current plan does not include " + key.code());
            }
        }
    }
}
