package uz.horecaos.platform.conversations.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import uz.horecaos.platform.assistant.application.AssistantTestWiring;
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
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver;

/**
 * ADR 0069 through the whole stack: a customer writes to a brand's Telegram bot,
 * the real {@code TelegramUpdateHandler} routes the text to the real conversations
 * engine, the engine offers it to the real assistant, and the answer comes back
 * out of the real bot client -- with the model provider the only stand-in.
 *
 * <p>What this proves that the unit suites cannot is the glue: that the handler's
 * gate lets a brand with no flow through, that the assistant's reply is recorded as
 * its own author beside the customer's words, that a handoff lands in the operator
 * inbox with its history, and that when the assistant is not entitled the chat is
 * exactly the silent no-op it was before this ADR.
 */
class AssistantConversationIntegrationTest {

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ObjectMapper objectMapper;
    private MutableClock clock;
    private FakeTelegramBotApi bot;
    private TogglableEntitlementService entitlements;
    private TelegramUpdateHandler updateHandler;
    private ConversationRepository conversations;
    private ConversationMessageStore messages;
    private FlowDocumentService flowDocuments;
    private AssistantTestWiring assistant;
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
        clock = new MutableClock(Instant.parse("2026-10-07T09:00:00Z"));
        AuditRecorder audit = new JdbcAuditRecorder(jdbc, objectMapper);

        protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get,
                        clock),
                "local"));

        conversations = new ConversationRepository(jdbc, clock);
        FlowRunRepository runs = new FlowRunRepository(jdbc, clock, protection, objectMapper);
        messages = new ConversationMessageStore(jdbc, clock, protection);
        flowDocuments = new FlowDocumentService(new FlowDocumentRepository(jdbc, clock), audit, clock);

        assistant = new AssistantTestWiring(jdbc, clock.instant())
                .seeded()
                .onlyOneBranch()
                .sellsPlovAt(45_000);

        SecretResolver secrets = new EnvironmentSecretResolver(key -> "a-test-bot-token", clock);
        TelegramWebhookInstallationLookup installationLookup = new TelegramWebhookInstallationLookup(jdbc);
        TelegramChatLockService locks = new TelegramChatLockService(jdbc, clock);
        TelegramBotApiClient botApiClient = new TelegramBotApiClient(objectMapper);
        TelegramConversationOutboundGateway outboundGateway = new TelegramConversationOutboundGateway(
                installationLookup, locks, botApiClient, secrets, Duration.ofSeconds(20));

        ConversationEngine engine = new ConversationEngine(
                conversations,
                runs,
                messages,
                flowDocuments,
                outboundGateway,
                clock,
                () -> List.of(assistant.participant()),
                "https://storefront.example");

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
                uz.horecaos.platform.support.InertCustomerBotActions.forTests(
                        actionTokens,
                        bindings,
                        entitlements,
                        new uz.horecaos.platform.web.cache.InProcessRateLimiter(clock),
                        audit,
                        clock),
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
                new JdbcConfigurationResolver(jdbc),
                Duration.ofHours(6));
    }

    private UUID tenant() {
        return assistant.tenantId();
    }

    private UUID brand() {
        return assistant.brandId();
    }

    @Test
    @DisplayName(
            "a customer asks what a dish costs in a brand with no flow at all, and is told the platform's price, in the bot, with the disclosure")
    void aPriceQuestionIsAnsweredInTheChat() {
        UUID installationId = seedTelegramInstallation(tenant(), brand());
        entitlements.entitle(tenant());
        long chat = 77_001L;

        updateHandler.handle(installation(installationId, tenant()), privateTextUpdate(1, chat, "Сколько стоит плов?"));

        List<String> said = bot.messagesSentTo(chat);
        assertThat(said).hasSize(1);
        assertThat(said.get(0)).contains("45 000 сум").contains("автоматический помощник");

        ConversationRepository.Row conversation = conversations
                .find(tenant(), brand(), uz.horecaos.platform.conversations.api.ChannelKind.TELEGRAM, chat)
                .orElseThrow();
        List<ConversationMessageStore.Row> thread = messages.history(tenant(), conversation.id());
        assertThat(thread).extracting(row -> row.direction().name()).containsExactly("INBOUND", "ASSISTANT");
        assertThat(thread.get(0).body()).isEqualTo("Сколько стоит плов?");
        assertThat(thread.get(1).body()).isEqualTo(said.get(0));
        assertThat(thread.get(1).assistantTurnId())
                .as("the message points at the turn that says why")
                .isNotNull();
        assertThat(assistant.ledger()).singleElement().satisfies(turn -> {
            assertThat(turn.get("outcome")).isEqualTo("ANSWERED");
            assertThat(turn.get("id")).isEqualTo(thread.get(1).assistantTurnId());
        });
        assertThat(conversation.state().name()).isEqualTo("IDLE");
    }

    @Test
    @DisplayName(
            "something the platform cannot answer is a refusal that hands the conversation to the operator inbox, with its history, and the assistant then stays quiet")
    void aRefusalHandsOverAndTheAssistantStaysQuiet() {
        UUID installationId = seedTelegramInstallation(tenant(), brand());
        entitlements.entitle(tenant());
        long chat = 77_002L;

        updateHandler.handle(
                installation(installationId, tenant()), privateTextUpdate(1, chat, "Сколько стоит бешбармак?"));

        assertThat(bot.messagesSentTo(chat)).hasSize(1);
        assertThat(bot.messagesSentTo(chat).get(0)).contains("не буду гадать").contains("нет онлайн");
        ConversationRepository.Row conversation = conversations
                .find(tenant(), brand(), uz.horecaos.platform.conversations.api.ChannelKind.TELEGRAM, chat)
                .orElseThrow();
        assertThat(conversation.state().name()).isEqualTo("HANDED_TO_OPERATOR");
        assertThat(conversations.listForBrand(tenant(), brand(), 10))
                .singleElement()
                .satisfies(row -> assertThat(row.needsReply()).isTrue());

        updateHandler.handle(installation(installationId, tenant()), privateTextUpdate(2, chat, "Сколько стоит плов?"));

        assertThat(bot.messagesSentTo(chat))
                .as("a person holds it now; the machine does not answer over them")
                .hasSize(1);
        assertThat(messages.history(tenant(), conversation.id()))
                .extracting(row -> row.direction().name())
                .containsExactly("INBOUND", "ASSISTANT", "INBOUND");
        assertThat(assistant.model().calls()).isZero();
    }

    @Test
    @DisplayName(
            "a complaint is handed over at once, a dishonest model never reaches a customer, and presence decides the wording")
    void complaintsAndDishonestModels() {
        UUID installationId = seedTelegramInstallation(tenant(), brand());
        entitlements.entitle(tenant());
        assistant.someoneIsOnline(true);

        updateHandler.handle(
                installation(installationId, tenant()), privateTextUpdate(1, 77_003L, "У меня жалоба, это ужасно"));
        assertThat(bot.messagesSentTo(77_003L).get(0)).contains("Сожалею").contains("ответит вам здесь");

        assistant.modelWillInvent("Плов стоит 12 000 сум", "f1");
        updateHandler.handle(
                installation(installationId, tenant()), privateTextUpdate(2, 77_004L, "Сколько стоит плов?"));
        assertThat(bot.messagesSentTo(77_004L)).hasSize(1);
        assertThat(bot.messagesSentTo(77_004L).get(0)).doesNotContain("12 000").doesNotContain("45 000");
    }

    @Test
    @DisplayName(
            "when the assistant is not entitled the chat is exactly what it was before: silence, and no conversation is even created")
    void withoutTheEntitlementNothingChanges() {
        UUID installationId = seedTelegramInstallation(tenant(), brand());
        entitlements.entitle(tenant());
        assistant.entitled(false);
        long chat = 77_005L;

        updateHandler.handle(installation(installationId, tenant()), privateTextUpdate(1, chat, "Сколько стоит плов?"));

        assertThat(bot.messagesSentTo(chat)).isEmpty();
        assertThat(conversations.find(
                        tenant(), brand(), uz.horecaos.platform.conversations.api.ChannelKind.TELEGRAM, chat))
                .isEmpty();
        assertThat(assistant.ledger()).isEmpty();
    }

    @Test
    @DisplayName("a redelivered update never makes the assistant answer, or spend, twice")
    void aRedeliveredUpdateIsAnsweredOnce() {
        UUID installationId = seedTelegramInstallation(tenant(), brand());
        entitlements.entitle(tenant());
        long chat = 77_006L;
        Map<String, Object> update = privateTextUpdate(1, chat, "Сколько стоит плов?");

        updateHandler.handle(installation(installationId, tenant()), update);
        updateHandler.handle(installation(installationId, tenant()), update);

        assertThat(bot.messagesSentTo(chat)).hasSize(1);
        assertThat(assistant.ledger()).hasSize(1);
        assertThat(assistant.model().calls()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "a location shared in the chat is answered with the branches that deliver there, and the coordinates are never stored")
    void aSharedLocationIsAnsweredForDelivery() {
        UUID installationId = seedTelegramInstallation(tenant(), brand());
        entitlements.entitle(tenant());
        assistant.deliversToSharedLocations();
        long chat = 77_007L;

        updateHandler.handle(installation(installationId, tenant()), privateTextUpdate(1, chat, "Вы доставляете?"));
        assertThat(bot.messagesSentTo(chat).get(0)).contains("location");

        updateHandler.handle(installation(installationId, tenant()), locationUpdate(2, chat, 41.2995, 69.2401));

        List<String> said = bot.messagesSentTo(chat);
        assertThat(said).hasSize(2);
        assertThat(said.get(1)).contains("Chilonzor");
        ConversationRepository.Row conversation = conversations
                .find(tenant(), brand(), uz.horecaos.platform.conversations.api.ChannelKind.TELEGRAM, chat)
                .orElseThrow();
        assertThat(messages.history(tenant(), conversation.id()))
                .extracting(ConversationMessageStore.Row::body)
                .noneMatch(body -> body.contains("41.2995") || body.contains("69.2401"))
                .contains("[location shared]");
    }

    // --------------------------------------------------------------- fixtures

    private static Map<String, Object> locationUpdate(long updateId, long chatId, double latitude, double longitude) {
        return Map.of(
                "update_id",
                updateId,
                "message",
                Map.of(
                        "location", Map.of("latitude", latitude, "longitude", longitude),
                        "chat", Map.of("id", chatId, "type", "private"),
                        "from", Map.of("id", chatId)));
    }

    private UUID seedTelegramInstallation(UUID tenantId, UUID brandId) {
        UUID id = UUID.randomUUID();
        String environmentCode = "assistant-flow-test-" + id;
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

    private void truncate() {
        jdbc.sql(
                        "TRUNCATE TABLE assistant.turns, assistant.knowledge_entry_versions, assistant.knowledge_entries CASCADE")
                .update();
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
