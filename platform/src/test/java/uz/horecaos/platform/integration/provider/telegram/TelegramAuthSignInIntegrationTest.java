package uz.horecaos.platform.integration.provider.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.api.StopListPort;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.EntitlementSnapshot;
import uz.horecaos.platform.commercial.api.LimitCheck;
import uz.horecaos.platform.conversations.api.ConversationChannelRef;
import uz.horecaos.platform.conversations.api.ConversationInboundPort;
import uz.horecaos.platform.customers.api.CustomerConfigurationKeys;
import uz.horecaos.platform.customers.api.CustomerTelegramSignIn;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.customers.api.RecipientContactDirectory.ContactEndpoint;
import uz.horecaos.platform.customers.api.RecipientContactDirectory.ContactMethod;
import uz.horecaos.platform.customers.application.CodeProtection;
import uz.horecaos.platform.customers.application.CustomerBlacklistService;
import uz.horecaos.platform.customers.application.CustomerIdentityService;
import uz.horecaos.platform.customers.application.CustomerPolicyLookup;
import uz.horecaos.platform.customers.application.CustomerPolicyLookup.ResolvedIdentityPolicy;
import uz.horecaos.platform.customers.application.CustomerSessionService;
import uz.horecaos.platform.customers.application.CustomerSessionService.Resolution;
import uz.horecaos.platform.customers.application.CustomerTelegramSignInService;
import uz.horecaos.platform.customers.application.CustomerVerificationService;
import uz.horecaos.platform.customers.application.VerificationChallengeIssuer;
import uz.horecaos.platform.customers.application.VerificationChallengeStore;
import uz.horecaos.platform.customers.application.VerificationCodeSource;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerSessionStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.spi.VerificationCodeTransport;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.integration.camel.notification.telegram.FakeTelegramBotApi;
import uz.horecaos.platform.integration.provider.telegram.TelegramAuthLinkService.ClaimResult;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup.WebhookInstallation;
import uz.horecaos.platform.inventory.api.StockAvailabilityPort;
import uz.horecaos.platform.notifications.api.CustomerProviderBindingSync;
import uz.horecaos.platform.notifications.application.CustomerProviderBindingSyncService;
import uz.horecaos.platform.notifications.application.NotificationPreferenceService;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationValueAuthor;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.cache.InProcessRateLimiter;

/**
 * ADR 0063's share-contact sign-in, end to end against real PostgreSQL and a
 * real Bot API socket: a customer with no account taps "Continue with
 * Telegram", the storefront mints an AUTH code, {@code /start auth_<code>}
 * answers with the {@code request_contact} keyboard, sharing the own contact
 * resolves-or-creates the account with a verified {@code TELEGRAM_CONTACT}
 * phone and the wave-7 CUSTOMER binding, and the storefront's own poll mints
 * and receives the session.
 *
 * <p>Own-contact refusal, pattern refusal, expiry, single-use and the poll's
 * single-claim guard are exercised the same way — through
 * {@link TelegramUpdateHandler#handle} and {@link TelegramAuthLinkService#claimSession},
 * never by calling either side's internals directly.
 */
class TelegramAuthSignInIntegrationTest {

    private static TestDatabase.Handle db;
    private static final AtomicLong nextUpdateId = new AtomicLong(1);

    private JdbcClient jdbc;
    private Clock clock;
    private FakeTelegramBotApi bot;
    private AuditRecorder audit;
    private TelegramAuthLinkService authLinks;
    private TelegramCustomerLinkService customerLinks;
    private TelegramUpdateHandler updateHandler;
    private CustomerTelegramSignIn telegramSignIn;
    private CustomerSessionService sessions;
    private UUID tenantId;
    private UUID brandId;
    private UUID installationId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
        new RoleRegistrySynchronizer(JdbcClient.create(db.dataSource())).synchronize();
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

        ObjectMapper objectMapper = JsonMapper.builder().build();
        clock = Clock.fixed(Instant.parse("2026-09-02T09:00:00Z"), ZoneOffset.UTC);
        audit = new JdbcAuditRecorder(jdbc, objectMapper);

        tenantId = UUID.randomUUID();
        brandId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        installationId = UUID.randomUUID();
        seedTenancy(tenantId, brandId, locationId);
        seedTelegramInstallation(tenantId, "telegram-auth-test-env");

        FieldProtection protection = fieldProtection("auth-sign-in-test-kek");
        JdbcCustomerStore customers = new JdbcCustomerStore(jdbc);
        CustomerPolicyLookup policies = (tenant, at) -> ResolvedIdentityPolicy.unconfigured();
        CustomerBlacklistService blacklist = new CustomerBlacklistService(customers, protection, clock, audit);
        CustomerIdentityService identity = new CustomerIdentityService(customers, policies, clock, blacklist, audit);

        CustomerVerificationService verification = new CustomerVerificationService(
                mock(VerificationChallengeIssuer.class),
                mock(VerificationChallengeStore.class),
                mock(CodeProtection.class),
                customers,
                identity,
                protection,
                new InProcessRateLimiter(clock),
                mock(VerificationCodeSource.class),
                noTransport(),
                audit,
                clock,
                Duration.ofMinutes(10));

        sessions = new CustomerSessionService(
                verification,
                new JdbcCustomerSessionStore(jdbc),
                identity,
                customers,
                audit,
                clock,
                Duration.ofDays(30));

        telegramSignIn = new CustomerTelegramSignInService(verification, sessions);

        authLinks = new TelegramAuthLinkService(jdbc, clock, Duration.ofMinutes(15));
        TelegramBindingStore bindings = new TelegramBindingStore(jdbc, clock, audit);
        CustomerProviderBindingSync bindingSync = new CustomerProviderBindingSyncService(
                new JdbcNotificationStore(jdbc),
                new NotificationPreferenceService(new JdbcNotificationStore(jdbc), clock));
        customerLinks =
                new TelegramCustomerLinkService(jdbc, clock, Duration.ofMinutes(15), bindings, bindingSync, audit);

        updateHandler = new TelegramUpdateHandler(
                new TelegramLinkService(jdbc, clock, Duration.ofMinutes(15)),
                new TelegramStaffLinkService(jdbc, clock, Duration.ofMinutes(15)),
                customerLinks,
                authLinks,
                telegramSignIn,
                new TelegramRightsVerifier(new TelegramBotApiClient(objectMapper)),
                bindings,
                new BotActionTokenStore(jdbc, clock),
                new BotCallbackAuthorizer(
                        new BotActionTokenStore(jdbc, clock),
                        new TelegramStaffLinkService(jdbc, clock, Duration.ofMinutes(15)),
                        new ThrowingAuthorizationService(),
                        new AlwaysEntitledService(),
                        throwingOrderDecisionPort(),
                        clock),
                uz.horecaos.platform.support.InertCustomerBotActions.forTests(
                        new BotActionTokenStore(jdbc, clock),
                        bindings,
                        new AlwaysEntitledService(),
                        new InProcessRateLimiter(clock),
                        audit,
                        clock),
                new ThrowingAuthorizationService(),
                new AlwaysEntitledService(),
                new NoSummaryOrderDirectory(),
                () -> java.util.List.of(),
                new NoOpContactDirectory(),
                new NoOpStopListPort(),
                new ThrowingStockAvailabilityPort(),
                new TelegramBotApiClient(objectMapper),
                secretResolver(),
                audit,
                clock,
                "en",
                new NoOpConversationInboundPort(),
                new TelegramInstallationBrandLookup(jdbc),
                new TelegramUpdateDedupStore(jdbc, clock),
                new InProcessRateLimiter(clock),
                new JdbcConfigurationResolver(jdbc),
                Duration.ofHours(6));
    }

    // -------------------------------------------------------------- happy path

    @Test
    @DisplayName("mint, /start, share own contact, and the account, contact, binding, and session all land")
    void theWholeShareContactStoryLands() {
        String code = authLinks.issueCode(tenantId, brandId);
        long chatId = 55001L;

        deliver(privateStartUpdate("auth_" + code, chatId));

        assertThat(bot.messagesSentTo(chatId)).hasSize(1);
        assertThat(bot.replyMarkupOf(lastMessageId(chatId))).isNotNull();

        deliver(contactUpdate(chatId, chatId, "+998901234567"));

        // A confirmation, and the keyboard is gone.
        assertThat(bot.messagesSentTo(chatId)).hasSize(2);
        Object markup = java.util.Objects.requireNonNull(bot.replyMarkupOf(lastMessageId(chatId)));
        assertThat(markup).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) markup).get("remove_keyboard")).isEqualTo(Boolean.TRUE);

        Map<String, Object> contact = jdbc.sql(
                        "SELECT source, verification_status FROM customer.contact_points WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query((row, n) -> Map.<String, Object>of(
                        "source", row.getString("source"), "status", row.getString("verification_status")))
                .single();
        assertThat(contact.get("source")).isEqualTo("TELEGRAM_CONTACT");
        assertThat(contact.get("status")).isEqualTo("VERIFIED");

        Long bindingCount = jdbc.sql(
                        "SELECT count(*) FROM integration.telegram_bindings WHERE tenant_id = :tenantId AND audience = 'CUSTOMER'")
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();
        assertThat(bindingCount).isEqualTo(1L);

        ClaimResult claim = authLinks.claimSession(tenantId, brandId, code);
        assertThat(claim).isInstanceOf(ClaimResult.Ready.class);
        ClaimResult.Ready ready = (ClaimResult.Ready) claim;

        // The poll endpoint's own next step: mint the session for the account
        // claimSession resolved. StorefrontTelegramSignInController.poll makes
        // exactly this call.
        CustomerTelegramSignIn.Session session =
                telegramSignIn.establishSession(tenantId, brandId, ready.accountId(), ready.accountCreated());

        assertThat(sessions.resolve(session.token()).state()).isEqualTo(Resolution.State.ACTIVE);

        // A second poll must not mint a second session for the same redemption.
        assertThat(authLinks.claimSession(tenantId, brandId, code)).isInstanceOf(ClaimResult.AlreadyClaimed.class);

        Long sessionCount = jdbc.sql(
                        "SELECT count(*) FROM customer.customer_sessions WHERE tenant_id = :tenantId AND customer_account_id = :accountId")
                .param("tenantId", tenantId)
                .param("accountId", ready.accountId())
                .query(Long.class)
                .single();
        assertThat(sessionCount).isEqualTo(1L);
    }

    // ------------------------------------------------------------- refusals

    @Test
    @DisplayName("a forwarded stranger's contact is refused, and nothing is created")
    void aForwardedContactIsRefused() {
        String code = authLinks.issueCode(tenantId, brandId);
        long chatId = 55002L;
        deliver(privateStartUpdate("auth_" + code, chatId));

        // contact.user_id names somebody else entirely.
        deliver(contactUpdate(chatId, chatId, 999_888_777L, "+998901234567"));

        assertThat(customerAccountCount()).isZero();
        // The keyboard is still stripped, and a second, honest share still works.
        deliver(contactUpdate(chatId, chatId, "+998901234567"));
        assertThat(customerAccountCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a phone outside the configured pattern is refused politely, naming nothing")
    void aNonMatchingPhoneIsRefused() {
        String code = authLinks.issueCode(tenantId, brandId);
        long chatId = 55003L;
        deliver(privateStartUpdate("auth_" + code, chatId));

        deliver(contactUpdate(chatId, chatId, "+15551234567"));

        assertThat(customerAccountCount()).isZero();
        assertThat(bot.messagesSentTo(chatId).getLast())
                .doesNotContain("15551234567")
                .doesNotContain("pattern");
    }

    @Test
    @DisplayName("a brand-scoped pattern narrows this bot, and cannot widen past PhoneNumber")
    void aBrandScopedPatternNarrowsThisBotAndCannotWidenPastPhoneNumber() {
        // Widening first, because it is the one people will try. An operator sets
        // this brand's bot to admit a US number alongside the Uzbek form.
        JdbcConfigurationValueAuthor authoring =
                new JdbcConfigurationValueAuthor(jdbc, audit, clock, new JdbcConfigurationResolver(jdbc));
        authoring.set(
                CustomerConfigurationKeys.TELEGRAM_AUTH_PHONE_PATTERN,
                ResourceScope.brand(tenantId, brandId),
                "^\\+(?:998\\d{9}|1\\d{10})$",
                false,
                null,
                ActorRef.service("test-operator"),
                "attempt to pilot a US market on this brand's bot");

        String widened = authLinks.issueCode(tenantId, brandId);
        long widenedChat = 55011L;
        deliver(privateStartUpdate("auth_" + widened, widenedChat));

        assertThatThrownBy(() -> deliver(contactUpdate(widenedChat, widenedChat, "+15551234567")))
                .as("this key narrows and never widens: the number clears the brand pattern and is "
                        + "then refused by PhoneNumber, which is deliberately Uzbek-only because "
                        + "every OTP to a destination nobody decided to pay for is money reaching "
                        + "nobody we serve. Opening a market is a change to PhoneNumber and the "
                        + "pricing conversation its comment demands, not a regular expression an "
                        + "operator can set")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Uzbek mobile");

        // Asserted as a throw rather than a polite decline because that is what
        // it currently is, and the difference is worth seeing: a customer who
        // shares a foreign contact with a widened bot gets an error rather than
        // a message telling them why. Handling it kindly is a change to
        // TelegramUpdateHandler.handleContactShare, not to this rule.
        assertThat(customerAccountCount()).isZero();

        // Narrowing is what the key is for, and it works: this brand's bot now
        // accepts one operator prefix rather than every Uzbek mobile. The second
        // write names the version the first produced -- passing null again would
        // mean "create", and the author refuses that on a key that already has a
        // value at this scope, which is the concurrency guard doing its job.
        long currentVersion = jdbc.sql("""
                SELECT version FROM tenant.configuration_values
                 WHERE key_code = :code AND scope_type = 'BRAND' AND brand_id = :brandId
                """)
                .param("code", CustomerConfigurationKeys.TELEGRAM_AUTH_PHONE_PATTERN_CODE)
                .param("brandId", brandId)
                .query(Long.class)
                .single();

        authoring.set(
                CustomerConfigurationKeys.TELEGRAM_AUTH_PHONE_PATTERN,
                ResourceScope.brand(tenantId, brandId),
                "^\\+99890\\d{7}$",
                false,
                currentVersion,
                ActorRef.service("test-operator"),
                "this brand's bot serves one operator's subscribers");

        String offPrefix = authLinks.issueCode(tenantId, brandId);
        long offPrefixChat = 55012L;
        deliver(privateStartUpdate("auth_" + offPrefix, offPrefixChat));
        deliver(contactUpdate(offPrefixChat, offPrefixChat, "+998911234567"));
        assertThat(customerAccountCount())
                .as("a valid Uzbek mobile the brand's narrowed pattern excludes is refused, which "
                        + "is the whole point of making this settable per brand")
                .isZero();

        String onPrefix = authLinks.issueCode(tenantId, brandId);
        long onPrefixChat = 55013L;
        deliver(privateStartUpdate("auth_" + onPrefix, onPrefixChat));
        deliver(contactUpdate(onPrefixChat, onPrefixChat, "+998901234567"));
        assertThat(customerAccountCount())
                .as("and one inside it signs in, so the override reached TelegramUpdateHandler's "
                        + "resolution rather than the old deployment property")
                .isEqualTo(1L);
    }

    // -------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("an expired code is refused at /start and its poll answers EXPIRED")
    void anExpiredCodeIsRefused() {
        AdvanceableClock advanceable = new AdvanceableClock(clock);
        // Rebuild the parts that hold the clock reference, using the advanceable one.
        authLinks = new TelegramAuthLinkService(jdbc, advanceable, Duration.ofMinutes(15));
        String code = authLinks.issueCode(tenantId, brandId);

        advanceable.advance(Duration.ofMinutes(16));

        assertThat(authLinks.resolve(code)).isEmpty();
        assertThat(authLinks.claimSession(tenantId, brandId, code)).isInstanceOf(ClaimResult.Expired.class);
    }

    @Test
    @DisplayName("a code is single-use: redeeming it a second time changes nothing further")
    void aCodeIsSingleUse() {
        String code = authLinks.issueCode(tenantId, brandId);
        long chatId = 55004L;
        deliver(privateStartUpdate("auth_" + code, chatId));
        deliver(contactUpdate(chatId, chatId, "+998901234567"));
        assertThat(customerAccountCount()).isEqualTo(1L);

        // The same chat opens the identical deep link again -- resolve() no
        // longer finds an unconsumed row for this code.
        deliver(privateStartUpdate("auth_" + code, chatId));
        assertThat(bot.messagesSentTo(chatId).getLast()).contains("invalid or has expired");
    }

    @Test
    @DisplayName("rate limiting refuses a chat hammering contact shares")
    void repeatedContactSharesAreRateLimited() {
        String code = authLinks.issueCode(tenantId, brandId);
        long chatId = 55005L;
        deliver(privateStartUpdate("auth_" + code, chatId));

        // Every share is refused as a forwarded contact, so the code stays
        // pending and each one reaches the rate limiter again -- exactly what
        // makes AUTH_CONTACT_PER_CHAT's five-per-minute budget the thing that
        // eventually answers, rather than "already redeemed".
        for (int i = 0; i < 6; i++) {
            deliver(contactUpdate(chatId, chatId, 999_888_777L, "+998901234567"));
        }

        assertThat(bot.messagesSentTo(chatId))
                .anySatisfy(text -> assertThat(text).contains("Too many"));
        assertThat(customerAccountCount()).isZero();
    }

    // ----------------------------------------------------------------- helpers

    private void deliver(Map<String, Object> update) {
        updateHandler.handle(
                new WebhookInstallation(
                        installationId,
                        tenantId,
                        "TELEGRAM_BOT_API",
                        "ACTIVE",
                        bot.baseUrl(),
                        secretRef(),
                        secretRef()),
                update);
    }

    private long lastMessageId(long chatId) {
        return java.util.Objects.requireNonNull(bot.lastMessageIdSentTo(chatId), "No message was sent to " + chatId);
    }

    private long customerAccountCount() {
        Long count = jdbc.sql("SELECT count(*) FROM customer.customer_accounts WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    private static Map<String, Object> privateStartUpdate(String payload, long userId) {
        return Map.of(
                "update_id",
                nextUpdateId.getAndIncrement(),
                "message",
                Map.of(
                        "text", "/start " + payload,
                        "chat", Map.of("id", userId, "type", "private"),
                        "from", Map.of("id", userId)));
    }

    private static Map<String, Object> contactUpdate(long chatId, long fromUserId, String phone) {
        return contactUpdate(chatId, fromUserId, fromUserId, phone);
    }

    private static Map<String, Object> contactUpdate(long chatId, long fromUserId, long contactUserId, String phone) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("chat", Map.of("id", chatId, "type", "private"));
        message.put("from", Map.of("id", fromUserId));
        message.put("contact", Map.of("phone_number", phone, "user_id", contactUserId));
        return Map.of("update_id", nextUpdateId.getAndIncrement(), "message", message);
    }

    private FieldProtection fieldProtection(String kek) {
        return new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.data_encryption.platform.kek", kek)::get, Clock.systemUTC()),
                "local"));
    }

    private SecretResolver secretResolver() {
        return new EnvironmentSecretResolver(key -> "a-test-bot-token", clock);
    }

    private String secretRef() {
        return "horecaos:local:provider_notification:platform:telegram-bot";
    }

    /**
     * {@code CustomerVerificationService} needs a {@code VerificationCodeTransport}
     * provider to construct, but the AUTH share-contact path never sends an SMS
     * or Gateway message -- it is proven by Telegram already -- so nothing here
     * ever calls {@link org.springframework.beans.factory.ObjectProvider#getIfAvailable()}.
     */
    private static org.springframework.beans.factory.ObjectProvider<VerificationCodeTransport> noTransport() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public VerificationCodeTransport getObject() {
                throw new UnsupportedOperationException("not exercised by this suite");
            }

            @Override
            public VerificationCodeTransport getObject(Object... args) {
                return getObject();
            }

            @Override
            public @Nullable VerificationCodeTransport getIfAvailable() {
                return null;
            }

            @Override
            public @Nullable VerificationCodeTransport getIfUnique() {
                return null;
            }
        };
    }

    private void seedTelegramInstallation(UUID tenant, String environmentCode) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'NOTIFICATION', 'TELEGRAM_BOT_API', :baseUrl, false, '127.0.0.1')
                ON CONFLICT (code) DO UPDATE SET base_url = EXCLUDED.base_url
                """)
                .param("code", environmentCode)
                .param("baseUrl", bot.baseUrl())
                .update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'TELEGRAM_BOT_API', :env,
                        'Test bot', 'ACTIVE', :secret, :secret)
                """)
                .param("id", installationId)
                .param("tenantId", tenant)
                .param("env", environmentCode)
                .param("secret", secretRef())
                .update();
    }

    private void seedTenancy(UUID tenant, UUID brand, UUID locationId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenant)
                .param("slug", "auth-" + tenant.toString().substring(0, 8))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'AUTH', :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brand)
                .param("tenantId", tenant)
                .param("slug", "auth-brand-" + brand.toString().substring(0, 8))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', :slug, 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("slug", "auth-loc-" + locationId.toString().substring(0, 8))
                .update();
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE integration.telegram_tracked_messages, integration.telegram_chat_locks, "
                        + "integration.telegram_binding_events, integration.telegram_bindings, "
                        + "integration.telegram_pending_links, integration.telegram_staff_link_codes, "
                        + "integration.telegram_staff_links, integration.bot_action_tokens CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE notifications.delivery_status_events, notifications.delivery_attempts, "
                        + "notifications.notifications, notifications.recipient_endpoints, "
                        + "notifications.notification_preferences, "
                        + "notifications.template_versions, notifications.templates CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_sessions, customer.contact_points, "
                        + "customer.principal_links, customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.binding_capabilities, integration.bindings, "
                        + "integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.locations, tenant.brands, tenant.tenants CASCADE")
                .update();
    }

    private static uz.horecaos.platform.ordering.api.OrderDecisionPort throwingOrderDecisionPort() {
        return (tenantId, orderId, command) -> {
            throw new UnsupportedOperationException("not exercised by this suite");
        };
    }

    /** A {@link Clock} whose {@code instant()} can be moved forward mid-test, for expiry assertions. */
    private static final class AdvanceableClock extends Clock {
        private Instant instant;
        private final java.time.ZoneId zone;

        AdvanceableClock(Clock initial) {
            this.instant = initial.instant();
            this.zone = initial.getZone();
        }

        void advance(Duration by) {
            instant = instant.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
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

    private static final class NoOpConversationInboundPort implements ConversationInboundPort {
        @Override
        public boolean hasActiveFlow(UUID tenantId, UUID brandId) {
            return false;
        }

        @Override
        public void handleStart(ConversationChannelRef channel) {}

        @Override
        public void handleText(ConversationChannelRef channel, String text) {}

        @Override
        public void handleButtonTap(ConversationChannelRef channel, String buttonKey) {}
    }

    private static final class NoSummaryOrderDirectory implements OrderDirectory {
        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return Optional.empty();
        }
    }

    private static final class AlwaysEntitledService implements EntitlementService {
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
            return true;
        }

        @Override
        public void requireFeature(UUID tenantId, EntitlementKey<Boolean> key) {}
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
}
