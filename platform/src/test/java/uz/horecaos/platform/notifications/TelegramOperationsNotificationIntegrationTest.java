package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.integration.camel.notification.CamelNotificationTransport;
import uz.horecaos.platform.integration.camel.notification.NotificationGateway;
import uz.horecaos.platform.integration.camel.notification.NotificationProcessor;
import uz.horecaos.platform.integration.camel.notification.NotificationRouteBuilder;
import uz.horecaos.platform.integration.camel.notification.telegram.FakeTelegramBotApi;
import uz.horecaos.platform.integration.camel.notification.telegram.TelegramChannelAdapter;
import uz.horecaos.platform.integration.camel.notification.telegram.TelegramCircuitBreakers;
import uz.horecaos.platform.integration.provider.JdbcProviderInstallationLookup;
import uz.horecaos.platform.integration.provider.telegram.TelegramBindingStore;
import uz.horecaos.platform.integration.provider.telegram.TelegramBotApiClient;
import uz.horecaos.platform.integration.provider.telegram.TelegramChatLockService;
import uz.horecaos.platform.integration.provider.telegram.TelegramLinkService;
import uz.horecaos.platform.integration.provider.telegram.TelegramMessageTracker;
import uz.horecaos.platform.integration.provider.telegram.TelegramOperationsSubscriptionDirectory;
import uz.horecaos.platform.integration.provider.telegram.TelegramRightsVerifier;
import uz.horecaos.platform.integration.provider.telegram.TelegramUpdateHandler;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup;
import uz.horecaos.platform.notifications.application.NotificationDispatchService;
import uz.horecaos.platform.notifications.application.NotificationEligibilityService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.application.NotificationWorker;
import uz.horecaos.platform.notifications.application.OperationsAlertFanoutService;
import uz.horecaos.platform.notifications.application.OrderNotificationTrigger;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * ADR 0058, stage 1 rollout, end to end: bind a group through the {@code /link}
 * handshake, confirm an order, see the message land in FIFO order; kick the
 * bot and see the binding retire; upgrade the group and see the rewrite.
 *
 * <p>Wired the same way {@code NotificationDeliveryTests} wires the SMS slice —
 * every real class against a real PostgreSQL, with {@link FakeTelegramBotApi}
 * standing in for {@code api.telegram.org} — because the property under test is
 * whether the whole path (handshake, trigger fan-out, worker, gateway, adapter,
 * lease, tracker, binding lifecycle) agrees with itself, not whether one class's
 * unit contract holds in isolation.
 */
class TelegramOperationsNotificationIntegrationTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    // Every stored order has a location (ordering.orders.location_id is NOT
    // NULL); the brand-wide fan-out under test is a property of the Telegram
    // binding's scope, not of the order.
    private static final UUID LOCATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-30T09:00:00Z");
    private static final String SECRET_REFERENCE = "horecaos:local:provider_notification:platform:telegram-bot";

    private static TestDatabase.Handle db;

    private FakeTelegramBotApi bot;
    private CamelContext camel;
    private JdbcClient jdbc;
    private JdbcNotificationStore notifications;
    private NotificationWorker worker;
    private OrderNotificationTrigger trigger;
    private TelegramLinkService links;
    private TelegramUpdateHandler updateHandler;
    private TelegramBindingStore bindingStore;
    private TelegramWebhookInstallationLookup webhookInstallations;
    private UUID installationId;
    private MutableClock clock;

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

        clock = new MutableClock(NOW);
        ObjectMapper objectMapper = JsonMapper.builder().build();

        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of("horecaos.secrets.provider_notification.platform.telegram-bot", "a-test-bot-token")::get, clock);

        seedTenant();
        installationId = seedTelegramInstallation();

        notifications = new JdbcNotificationStore(jdbc);
        JdbcTemplateStore templateStore = new JdbcTemplateStore(jdbc);
        NotificationTemplateService templates = new NotificationTemplateService(templateStore, objectMapper, clock);

        AuditRecorder audit = new JdbcAuditRecorder(jdbc, objectMapper);
        TelegramBotApiClient botApiClient = new TelegramBotApiClient(objectMapper);
        bindingStore = new TelegramBindingStore(jdbc, clock, audit);
        TelegramChatLockService locks = new TelegramChatLockService(jdbc, clock);
        TelegramMessageTracker tracker = new TelegramMessageTracker(jdbc, clock);
        TelegramRightsVerifier rights = new TelegramRightsVerifier(botApiClient);
        links = new TelegramLinkService(jdbc, clock, Duration.ofMinutes(15));
        webhookInstallations = new TelegramWebhookInstallationLookup(jdbc);
        updateHandler =
                new TelegramUpdateHandler(links, rights, bindingStore, botApiClient, secrets, audit, clock, "ru");

        JdbcProviderInstallationLookup installationLookup = new JdbcProviderInstallationLookup(jdbc, clock);
        NotificationGateway gateway = new NotificationGateway(
                List.of(new TelegramChannelAdapter(
                        botApiClient,
                        bindingStore,
                        locks,
                        tracker,
                        new TelegramCircuitBreakers(new SimpleMeterRegistry(), clock),
                        Duration.ofSeconds(20))),
                installationLookup,
                secrets);

        camel = new DefaultCamelContext();
        camel.addRoutes(new NotificationRouteBuilder(new NotificationProcessor(gateway, new SimpleMeterRegistry())));
        camel.start();
        CamelNotificationTransport transport = new CamelNotificationTransport(camel.createProducerTemplate(), gateway);

        orderOne = UUID.randomUUID();
        orderTwo = UUID.randomUUID();
        orderThree = UUID.randomUUID();
        StubOrderDirectory orders = new StubOrderDirectory();
        orders.publish(new OrderDirectory.OrderSummary(
                orderOne, TENANT, BRAND, LOCATION, "A-1", null, null, "CONFIRMED", "UZS", 100_000L, 1));
        orders.publish(new OrderDirectory.OrderSummary(
                orderTwo, TENANT, BRAND, LOCATION, "A-2", null, null, "CONFIRMED", "UZS", 200_000L, 1));
        orders.publish(new OrderDirectory.OrderSummary(
                orderThree, TENANT, BRAND, LOCATION, "A-3", null, null, "CONFIRMED", "UZS", 300_000L, 1));

        NoOpContactDirectory contacts = new NoOpContactDirectory();
        NotificationEligibilityService eligibility = new NotificationEligibilityService(
                notifications,
                templates,
                (t, a, b, p, c) -> Optional.empty(),
                contacts,
                orders,
                transport,
                objectMapper,
                clock,
                "ru");
        NotificationDispatchService dispatch = new NotificationDispatchService(
                notifications, templateStore, contacts, transport, objectMapper, clock, 8, Duration.ofSeconds(30));
        worker = new NotificationWorker(notifications, eligibility, dispatch, clock, 50, Duration.ofMinutes(2));

        OperationsAlertFanoutService fanout = new OperationsAlertFanoutService(
                new TelegramOperationsSubscriptionDirectory(bindingStore), notifications, objectMapper, clock);
        trigger = new OrderNotificationTrigger(notifications, fanout, objectMapper, clock, "SMS", Duration.ofHours(6));

        activateTelegramTemplate();
    }

    private UUID orderOne;
    private UUID orderTwo;
    private UUID orderThree;

    @AfterEach
    void tearDown() {
        if (camel != null) {
            camel.stop();
        }
        if (bot != null) {
            bot.close();
        }
    }

    @Test
    @DisplayName("link, confirm, kick, migrate — the stage 1 story")
    void theWholeStory() {
        long chatOne = -100111L;
        String codeOne = links.issueCode(TENANT, BRAND, null, "operator-1");
        updateHandler.handle(
                webhookInstallations.find(installationId).orElseThrow(), linkUpdate(codeOne, chatOne, null, 555L));

        UUID maybeBindingOne = onlyBindingFor(chatOne);
        assertThat(maybeBindingOne).isNotNull();
        UUID bindingOne = Objects.requireNonNull(maybeBindingOne);
        assertThat(bot.messagesSentTo(chatOne)).hasSize(1); // the handshake's own confirmation

        // Two order confirmations, fired a beat apart (as two real ones always
        // are) so their created_at genuinely orders them, then drained as one
        // batch. NotificationDispatchService's ordering precondition is what
        // turns "created_at orders them" into "delivered in that order" even
        // when a worker happens to reach the newer one first within a batch —
        // drainUntilQuiet drives that deferral to completion.
        trigger.onOrderingEvent(orderConfirmed(orderOne));
        clock.advance(Duration.ofMillis(5));
        trigger.onOrderingEvent(orderConfirmed(orderTwo));
        drainUntilQuiet();

        List<String> businessMessages = bot.messagesSentTo(chatOne)
                .subList(1, bot.messagesSentTo(chatOne).size());
        assertThat(businessMessages).hasSize(2);
        assertThat(businessMessages.get(0)).contains("A-1");
        assertThat(businessMessages.get(1)).contains("A-2");

        // Kick: the next order confirmed to this chat is refused, and the
        // binding retires rather than queuing forever.
        bot.kick(chatOne, true);
        trigger.onOrderingEvent(orderConfirmed(orderThree));
        drainUntilQuiet();

        assertThat(retiredReasonFor(bindingOne)).isEqualTo("BOT_KICKED");
        assertThat(bindingStatus(bindingOne)).isEqualTo("SUSPENDED");

        // Link a second, fresh chat — bindingOne is retired now, so it alone
        // receives what follows.
        long chatTwoOld = -100222L;
        long chatTwoNew = -100333L;
        String codeTwo = links.issueCode(TENANT, BRAND, null, "operator-1");
        updateHandler.handle(
                webhookInstallations.find(installationId).orElseThrow(), linkUpdate(codeTwo, chatTwoOld, null, 555L));
        UUID maybeBindingTwo = onlyBindingFor(chatTwoOld);
        assertThat(maybeBindingTwo).isNotNull();
        UUID bindingTwo = Objects.requireNonNull(maybeBindingTwo);
        assertThat(bot.messagesSentTo(chatTwoOld)).hasSize(1); // the handshake's own confirmation

        // Arm the migrate answer on the *next* send only — a real business
        // notification through the adapter, not the handshake's own reply,
        // because migrate_to_chat_id's rewrite-and-replay-once lives in
        // TelegramChannelAdapter, not in the one-shot bot reply.
        bot.migrateOnNextSend(chatTwoOld, chatTwoNew);
        trigger.onOrderingEvent(orderConfirmed(orderThree));
        drainUntilQuiet();

        assertThat(chatIdFor(bindingTwo)).isEqualTo(chatTwoNew);
        assertThat(bot.messagesSentTo(chatTwoNew)).anyMatch(text -> text.contains("A-3"));
        // Nothing further ever reached the pre-migration chat id.
        assertThat(bot.messagesSentTo(chatTwoOld)).hasSize(1);
    }

    @Test
    @DisplayName("a binding cannot be inserted under a tenant that does not own its ADR 0026 row")
    void crossTenantBindingReferenceIsRejectedByTheDatabase() {
        UUID otherTenant = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'telegram-pilot-other', 'Legal', 'Other', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", otherTenant).update();

        UUID bindingId = bindingStore.createBinding(TENANT, installationId, BRAND, null, -100999L, null, 1L);

        // fk_telegram_binding requires (tenant_id, binding_id) to match an
        // integration.bindings row of the SAME tenant. otherTenant owns no such
        // row — TENANT does — so this must fail at the database, not merely go
        // unenforced by application code.
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO integration.telegram_bindings (
                            binding_id, tenant_id, chat_id, audience, created_at, updated_at)
                        VALUES (:bindingId, :otherTenant, -100998, 'OPERATIONS', now(), now())
                        """)
                        .param("bindingId", bindingId)
                        .param("otherTenant", otherTenant)
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // Same law, at the recipient_endpoints boundary: an operations endpoint
        // cannot point a different tenant's notification pipeline at this chat.
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO notifications.recipient_endpoints (
                            id, tenant_id, endpoint_type, provider_binding_id, status, created_at, updated_at)
                        VALUES (:id, :otherTenant, 'PROVIDER_BINDING', :bindingId, 'ACTIVE', now(), now())
                        """)
                        .param("id", UUID.randomUUID())
                        .param("otherTenant", otherTenant)
                        .param("bindingId", bindingId)
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------- setup

    private Map<String, Object> linkUpdate(String code, long chatId, @Nullable Integer topicId, long fromUserId) {
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("id", chatId);
        chat.put("type", "supergroup");

        Map<String, Object> from = Map.of("id", fromUserId);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("chat", chat);
        message.put("from", from);
        message.put("text", "/link " + code);
        if (topicId != null) {
            message.put("message_thread_id", topicId);
        }

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("update_id", 1);
        update.put("message", message);
        return update;
    }

    private OrderConfirmed orderConfirmed(UUID orderId) {
        return new OrderConfirmed(
                UUID.randomUUID(),
                new TenantId(TENANT),
                orderId,
                NOW,
                BRAND,
                LOCATION,
                "AUTO_ACCEPT",
                null,
                NOW,
                "UZS",
                100_000L,
                "CONFIRMED",
                1);
    }

    private void activateTelegramTemplate() {
        JdbcTemplateStore templateStore = new JdbcTemplateStore(jdbc);
        NotificationTemplateService templates = new NotificationTemplateService(
                templateStore, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
        UUID templateId = templates.createTemplate(
                TENANT,
                BRAND,
                OrderNotificationTrigger.ORDER_CONFIRMED,
                NotificationClass.OPERATIONS_ALERT,
                NotificationChannel.TELEGRAM,
                null);

        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        MessageLocale.required()
                .forEach(locale -> wordings.put(locale, new Wording(null, "Order {{orderNumber}} confirmed")));
        int version = templates.addVersion(
                TENANT,
                templateId,
                wordings,
                Map.of("orderNumber", "string", "amount", "string", "currency", "string", "reasonCode", "string"));
        templates.activate(TENANT, templateId, version, "test");
    }

    private void seedTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'telegram-pilot', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', 'telegram-pilot-brand', 'Pilot brand', 'ACTIVE')
                """).param("id", BRAND).param("tenantId", TENANT).update();
        // Every stored order names a real location (fk_notification_location and
        // ordering.orders.location_id NOT NULL); the brand-wide Telegram binding
        // under test is what makes location scope irrelevant to the fan-out, not
        // the absence of one on the order.
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'PILOT-1', 'telegram-pilot-location', 'Pilot location',
                    'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    private UUID seedTelegramInstallation() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('telegram-fake', 'NOTIFICATION', 'TELEGRAM_BOT_API', :baseUrl, false, '127.0.0.1')
                """).param("baseUrl", bot.baseUrl()).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'telegram-fake',
                        'Pilot bot', 'ACTIVE', :secret, :secret)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("secret", SECRET_REFERENCE)
                .update();
        return id;
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE integration.telegram_tracked_messages, integration.telegram_chat_locks, "
                        + "integration.telegram_binding_events, integration.telegram_bindings, "
                        + "integration.telegram_pending_links CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE notifications.delivery_status_events, notifications.delivery_attempts, "
                        + "notifications.notifications, notifications.recipient_endpoints, "
                        + "notifications.template_versions, notifications.templates CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.binding_capabilities, integration.bindings, "
                        + "integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }

    /**
     * Drains until nothing telegram-channel is left in flight, forcing every
     * backed-off row due between rounds since the clock under test never
     * advances on its own. Bounded rather than a {@code while(true)}: a bug
     * that leaves a row perpetually non-terminal must fail the test, not hang
     * the build.
     */
    private void drainUntilQuiet() {
        for (int round = 0; round < 5 && nonTerminalTelegramCount() > 0; round++) {
            forceAllDue();
            worker.drain();
        }
    }

    private void forceAllDue() {
        jdbc.sql("UPDATE notifications.notifications SET next_attempt_at = :now WHERE channel = 'TELEGRAM'")
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }

    private long nonTerminalTelegramCount() {
        return jdbc.sql("""
                SELECT count(*) FROM notifications.notifications
                WHERE channel = 'TELEGRAM'
                  AND status NOT IN ('DELIVERED', 'FAILED_TERMINAL', 'EXPIRED', 'SUPPRESSED', 'MANUAL_REVIEW')
                """).query(Long.class).single();
    }

    private @Nullable UUID onlyBindingFor(long chatId) {
        return jdbc.sql("SELECT binding_id FROM integration.telegram_bindings WHERE chat_id = :chatId")
                .param("chatId", chatId)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private long chatIdFor(UUID bindingId) {
        return jdbc.sql("SELECT chat_id FROM integration.telegram_bindings WHERE binding_id = :id")
                .param("id", bindingId)
                .query(Long.class)
                .single();
    }

    private String retiredReasonFor(UUID bindingId) {
        return jdbc.sql("SELECT retired_reason FROM integration.telegram_bindings WHERE binding_id = :id")
                .param("id", bindingId)
                .query(String.class)
                .single();
    }

    private String bindingStatus(UUID bindingId) {
        return jdbc.sql("SELECT status FROM integration.bindings WHERE id = :id")
                .param("id", bindingId)
                .query(String.class)
                .single();
    }

    /** A clock the test advances, so two events a beat apart genuinely order by created_at. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
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

    /**
     * No operations-audience notification ever reaches this port — see
     * {@code NotificationEligibilityService}'s {@code operationsAudience}
     * branch — so a stub that answers nothing proves the Telegram path never
     * calls it, which is exactly the guarantee ADR 0058 asks for: a group
     * message is never resolved through the customer contact directory.
     */
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

    private static final class StubOrderDirectory implements OrderDirectory {
        private final Map<UUID, OrderSummary> summaries = new LinkedHashMap<>();

        void publish(OrderSummary summary) {
            summaries.put(summary.orderId(), summary);
        }

        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return Optional.ofNullable(summaries.get(orderId))
                    .filter(summary -> summary.tenantId().equals(tenantId));
        }
    }
}
