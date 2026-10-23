package uz.horecaos.platform.integration.provider.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.api.StopListPort;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.conversations.api.ConversationInboundPort;
import uz.horecaos.platform.customers.api.CustomerTelegramSignIn;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.integration.camel.notification.telegram.FakeTelegramBotApi;
import uz.horecaos.platform.integration.provider.telegram.TelegramCustomerLinkService.PendingCustomerLink;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup.WebhookInstallation;
import uz.horecaos.platform.inventory.api.StockAvailabilityPort;
import uz.horecaos.platform.marketing.application.AttributionLinkClickService;
import uz.horecaos.platform.marketing.application.AttributionLinkService;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAttributionLinkStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.RejectReasonDirectory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStorefrontHostnames;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTelegramChannelSettingsStore;
import uz.horecaos.platform.web.cache.InProcessRateLimiter;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * What the bot does when a customer opens it by a link (ADR 0183; gap-map rows {@code 6.6a} and {@code 7.6c}), through the
 * real {@link TelegramUpdateHandler#handle} entry point, the real click counter and a real Bot API socket.
 *
 * <p>Two things are proved and one is refused. A {@code /start <token>} for a live link of the bot's brand counts one click
 * and is then answered exactly as a bare {@code /start} (the channel's own welcome, or the silence an unconfigured bot always
 * kept); a token that is not a live link of this brand is refused as it always was and counts nothing. And what the click
 * leaves behind is a number: the Telegram user id is stored nowhere.
 */
class TelegramBotLandingTests {

    private static final AtomicLong UPDATE_IDS = new AtomicLong(1);
    private static final String SECRET_REFERENCE = "horecaos:local:provider_notification:platform:telegram-bot";
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");
    private static final String REFUSAL = "This link is invalid or has expired";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private FakeTelegramBotApi bot;
    private Clock clock;
    private TelegramUpdateHandler handler;
    private AttributionLinkService links;
    private TelegramCustomerLinkService customerLinks;
    private ConversationInboundPort conversations;
    private EntitlementService entitlements;

    private UUID tenant;
    private UUID brandA;
    private UUID brandB;
    private UUID installationId;

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
        jdbc = JdbcClient.create(db.dataSource());
        truncate();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        tenant = UUID.randomUUID();
        brandA = UUID.randomUUID();
        brandB = UUID.randomUUID();
        installationId = UUID.randomUUID();
        seedTenancy();

        JsonMapper objectMapper = JsonMapper.builder().build();
        AuditRecorder audit = new JdbcAuditRecorder(jdbc, objectMapper);
        links = new AttributionLinkService(
                new JdbcAttributionLinkStore(jdbc), new JdbcCampaignStore(jdbc), audit, clock);

        TelegramBotApiClient botApi = new TelegramBotApiClient(objectMapper);
        TelegramCustomerLanding landing = new TelegramCustomerLanding(
                new AttributionLinkClickService(
                        new JdbcAttributionLinkStore(jdbc), new InProcessRateLimiter(clock), clock),
                new JdbcTelegramChannelSettingsStore(jdbc),
                new JdbcStorefrontHostnames(jdbc),
                BrandLocaleLookup.platformFallback(),
                new TelegramInstallationBrandLookup(jdbc),
                botApi);

        customerLinks = mock(TelegramCustomerLinkService.class);
        conversations = mock(ConversationInboundPort.class);
        entitlements = mock(EntitlementService.class);
        SecretResolver secrets = new EnvironmentSecretResolver(key -> "a-test-bot-token", clock);

        handler = new TelegramUpdateHandler(
                mock(TelegramLinkService.class),
                mock(TelegramStaffLinkService.class),
                customerLinks,
                mock(TelegramAuthLinkService.class),
                mock(CustomerTelegramSignIn.class),
                mock(TelegramRightsVerifier.class),
                mock(TelegramBindingStore.class),
                mock(BotActionTokenStore.class),
                mock(BotCallbackAuthorizer.class),
                mock(CustomerBotActionAuthorizer.class),
                mock(AuthorizationService.class),
                entitlements,
                mock(OrderDirectory.class),
                mock(RejectReasonDirectory.class),
                mock(RecipientContactDirectory.class),
                mock(StopListPort.class),
                mock(StockAvailabilityPort.class),
                botApi,
                secrets,
                audit,
                clock,
                "ru",
                conversations,
                new TelegramInstallationBrandLookup(jdbc),
                landing,
                new TelegramUpdateDedupStore(jdbc, clock),
                mock(RateLimiter.class),
                mock(ConfigurationResolver.class),
                Duration.ofHours(6));
    }

    @AfterEach
    void tearDown() {
        bot.close();
    }

    // ------------------------------------------------------------------ the click

    @Test
    @DisplayName("a live link of the bot's brand is counted once, and the customer is greeted like any /start")
    void aLiveLinkIsCountedAndAnsweredLikeABareStart() {
        seedBot(brandA);
        configureChannel(brandA, true, "Welcome to Tandir House", null);
        String token = mintLink(brandA, "TELEGRAM_BOT");

        deliver(start(token, 5001L, "ru"));

        assertThat(clicks(token)).isEqualTo(1);
        assertThat(bot.messagesSentTo(5001L)).containsExactly("Welcome to Tandir House");
    }

    @Test
    @DisplayName("a token that names no live link is refused as before and counts nothing")
    void anUnknownTokenIsRefusedAndNothingIsCounted() {
        seedBot(brandA);
        String token = mintLink(brandA, "TELEGRAM_BOT");

        deliver(start("nosuchtoken", 5002L, "ru"));

        assertThat(bot.messagesSentTo(5002L)).singleElement().asString().contains(REFUSAL);
        assertThat(clicks(token)).isZero();
    }

    @Test
    @DisplayName("an archived link counts nothing and is refused the same way")
    void anArchivedLinkCountsNothing() {
        seedBot(brandA);
        String token = mintLink(brandA, "TELEGRAM_BOT");
        UUID linkId = linkId(token);
        links.archive(tenant, linkId, UUID.randomUUID());

        deliver(start(token, 5003L, "ru"));

        assertThat(bot.messagesSentTo(5003L)).singleElement().asString().contains(REFUSAL);
        assertThat(clicks(token)).isZero();
    }

    @Test
    @DisplayName("a link of another brand is not counted by this brand's bot")
    void anotherBrandsLinkIsNotCounted() {
        seedBot(brandA);
        String token = mintLink(brandB, "TELEGRAM_BOT");

        deliver(start(token, 5004L, "ru"));

        assertThat(bot.messagesSentTo(5004L)).singleElement().asString().contains(REFUSAL);
        assertThat(clicks(token)).isZero();
    }

    @Test
    @DisplayName("a bot installed for the whole tenant counts a link of any of its brands, under the link's own brand")
    void aTenantWideBotCountsTheLinksOwnBrand() {
        seedBot(null);
        String token = mintLink(brandB, "TELEGRAM_BOT");

        deliver(start(token, 5005L, "ru"));

        assertThat(clicks(token)).isEqualTo(1);
        assertThat(bot.messagesSentTo(5005L))
                .as("an unconfigured bot stays as silent as it was")
                .isEmpty();
    }

    @Test
    @DisplayName("a customer link code is never read as a click, even if a token looks the same")
    void aCustomerLinkCodeIsNotAClick() {
        seedBot(brandA);
        String token = mintLink(brandA, "TELEGRAM_BOT");
        when(customerLinks.resolve(token))
                .thenReturn(Optional.of(new PendingCustomerLink(UUID.randomUUID(), tenant, brandA, UUID.randomUUID())));

        deliver(start(token, 5006L, "ru"));

        assertThat(clicks(token)).as("the customer-link family is tried first").isZero();
    }

    @Test
    @DisplayName("a visitor who opens the link again and again is limited, and the bot still answers every time")
    void aRepeatingVisitorIsLimitedButNeverRefused() {
        seedBot(brandA);
        configureChannel(brandA, false, "Hello", null);
        String token = mintLink(brandA, "TELEGRAM_BOT");

        for (int i = 0; i < 6; i++) {
            deliver(start(token, 5007L, "ru"));
        }

        assertThat(clicks(token)).as("three a minute per visitor and link").isEqualTo(3);
        assertThat(bot.messagesSentTo(5007L)).hasSize(6).allMatch("Hello"::equals);
    }

    @Test
    @DisplayName("what a click leaves behind is a number: the Telegram user id is stored in no marketing row")
    void theTelegramUserIdIsStoredNowhere() {
        seedBot(brandA);
        String token = mintLink(brandA, "TELEGRAM_BOT");

        deliver(start(token, 777_000_123L, "ru"));

        assertThat(clicks(token)).isEqualTo(1);
        Integer rowsMentioningTheUser = jdbc.sql("""
                        SELECT (SELECT count(*) FROM marketing.attribution_links x WHERE row_to_json(x)::text LIKE '%777000123%')
                             + (SELECT count(*) FROM marketing.attribution_link_clicks_day x WHERE row_to_json(x)::text LIKE '%777000123%')
                        """).query(Integer.class).single();
        assertThat(rowsMentioningTheUser).isZero();
    }

    // -------------------------------------------------------------------- the welcome

    @Test
    @DisplayName("a bare /start of a bot whose Telegram channel nobody configured is as silent as it always was")
    void anUnconfiguredBotStaysSilent() {
        seedBot(brandA);

        deliver(bareStart(5010L, "ru"));

        assertThat(bot.messagesSentTo(5010L)).isEmpty();
    }

    @Test
    @DisplayName("the welcome is in the customer's own language, then the brand's default, then the platform's")
    void theWelcomeFollowsTheCustomersLanguage() {
        seedBot(brandA);
        configureChannel(brandA, false, Map.of("ru", "Добро пожаловать", "uz-Latn", "Xush kelibsiz"), Map.of(), false);

        deliver(bareStart(5011L, "uz"));
        deliver(bareStart(5012L, "ru"));
        deliver(bareStart(5013L, "de"));
        deliver(bareStart(5014L, "en-GB"));

        assertThat(bot.messagesSentTo(5011L)).containsExactly("Xush kelibsiz");
        assertThat(bot.messagesSentTo(5012L)).containsExactly("Добро пожаловать");
        assertThat(bot.messagesSentTo(5013L))
                .as("an unsupported language falls back to the brand's default (ru), which has a text")
                .containsExactly("Добро пожаловать");
        assertThat(bot.messagesSentTo(5014L))
                .as("English has no text of its own, so the brand's default answers")
                .containsExactly("Добро пожаловать");
    }

    @Test
    @DisplayName(
            "with no text written in any language the platform's own greeting answers, and names the menu button only for a Mini App")
    void thePlatformGreetingIsTheLastFallback() {
        seedBot(brandA);
        seedStorefront(brandA, "shop.example.uz", true);
        configureChannel(brandA, true, Map.of(), Map.of(), false);

        deliver(bareStart(5015L, "en"));

        assertThat(bot.messagesSentTo(5015L))
                .singleElement()
                .asString()
                .isEqualTo("Welcome! Tap the menu button below to order.");
    }

    @Test
    @DisplayName(
            "the public offer is the tenant's terms page on the verified hostname, and is left out when there is none")
    void theOfferLinkNeedsAVerifiedHostname() {
        seedBot(brandA);
        configureChannel(brandA, false, Map.of("en", "Hello"), Map.of(), true);

        deliver(bareStart(5016L, "en"));
        assertThat(bot.messagesSentTo(5016L)).containsExactly("Hello");

        seedStorefront(brandA, "shop.example.uz", true);
        deliver(bareStart(5017L, "en"));
        assertThat(bot.messagesSentTo(5017L))
                .singleElement()
                .asString()
                .isEqualTo("Hello\n\nPublic offer: https://shop.example.uz/terms");
    }

    @Test
    @DisplayName("a Mini App channel sets this chat's menu button in the customer's language, opening the storefront")
    void theMenuButtonIsSetInTheCustomersLanguage() {
        seedBot(brandA);
        seedStorefront(brandA, "shop.example.uz", true);
        configureChannel(brandA, true, Map.of("en", "Hello"), Map.of("ru", "Заказать", "en", "Order"), false);

        deliver(bareStart(5018L, "en"));
        deliver(bareStart(5019L, "ru"));

        assertThat(bot.menuButtonFor(5018L))
                .containsEntry("type", "web_app")
                .containsEntry("text", "Order")
                .containsEntry("web_app", Map.of("url", "https://shop.example.uz/"));
        assertThat(bot.menuButtonFor(5019L)).containsEntry("text", "Заказать");
    }

    @Test
    @DisplayName("a Mini App with no verified hostname sets no button and sends the greeting without it")
    void noHostnameNoButton() {
        seedBot(brandA);
        seedStorefront(brandA, "pending.example.uz", false);
        configureChannel(brandA, true, Map.of("en", "Hello"), Map.of("en", "Order"), false);

        deliver(bareStart(5020L, "en"));

        assertThat(bot.messagesSentTo(5020L)).containsExactly("Hello");
        assertThat(bot.menuButtonFor(5020L)).isNull();
    }

    @Test
    @DisplayName(
            "a chat that holds a Mini App button is put back to Telegram's default once the Mini App is switched off")
    void aMiniAppSwitchedOffTakesTheChatsButtonOff() {
        seedBot(brandA);
        seedStorefront(brandA, "shop.example.uz", true);
        configureChannel(brandA, true, Map.of("en", "Hello"), Map.of("en", "Order"), false);
        deliver(bareStart(5030L, "en"));
        assertThat(bot.menuButtonFor(5030L)).containsEntry("type", "web_app");

        jdbc.sql("UPDATE tenant.telegram_channel_settings SET mini_app_enabled = false WHERE tenant_id = :t")
                .param("t", tenant)
                .update();
        deliver(bareStart(5030L, "en"));

        assertThat(bot.menuButtonFor(5030L))
                .as("the button that opened the storefront no longer does")
                .isEqualTo(Map.of("type", "default"));
        assertThat(bot.messagesSentTo(5030L)).hasSize(2);
    }

    @Test
    @DisplayName("a storefront address that stops being verified takes the chat's Mini App button off the next time")
    void aLapsedAddressTakesTheChatsButtonOff() {
        seedBot(brandA);
        seedStorefront(brandA, "shop.example.uz", true);
        configureChannel(brandA, true, Map.of("en", "Hello"), Map.of("en", "Order"), false);
        deliver(bareStart(5031L, "en"));
        jdbc.sql("UPDATE tenant.channel_hostnames SET verified = false WHERE tenant_id = :t")
                .param("t", tenant)
                .update();

        deliver(bareStart(5031L, "en"));

        assertThat(bot.menuButtonFor(5031L)).isEqualTo(Map.of("type", "default"));
    }

    @Test
    @DisplayName(
            "a chat that holds no Mini App button, or a bot whose Mini App was never configured, is not written to")
    void aChatWithNoMiniAppButtonIsLeftAlone() {
        seedBot(brandA);
        seedStorefront(brandA, "shop.example.uz", true);
        configureChannel(brandA, false, Map.of("en", "Hello"), Map.of(), false);
        deliver(bareStart(5032L, "en"));
        assertThat(bot.setChatMenuButtonCallCount())
                .as("a Mini App that was never configured is never asked about")
                .isZero();

        jdbc.sql("UPDATE tenant.telegram_channel_settings SET mini_app_short_name = 'order' WHERE tenant_id = :t")
                .param("t", tenant)
                .update();
        deliver(bareStart(5033L, "en"));

        assertThat(bot.menuButtonFor(5033L)).isNull();
        assertThat(bot.setChatMenuButtonCallCount())
                .as("the chat holds nothing to take off, so nothing is written")
                .isZero();
    }

    @Test
    @DisplayName("a running welcome flow keeps the conversation, and the channel's welcome is not sent on top of it")
    void aRunningFlowIsNotGreetedTwice() {
        seedBot(brandA);
        configureChannel(brandA, false, Map.of("en", "Hello"), Map.of(), false);
        when(entitlements.featureEnabled(tenant, EntitlementKeys.TELEGRAM_CONVERSATIONS_ENABLED))
                .thenReturn(true);
        when(conversations.hasActiveFlow(tenant, brandA)).thenReturn(true);

        deliver(bareStart(5021L, "en"));

        verify(conversations).handleStart(any());
        assertThat(bot.messagesSentTo(5021L)).isEmpty();
    }

    @Test
    @DisplayName("a link opened while no flow is running still reaches the welcome, and never the conversations engine")
    void aLinkWithNoFlowReachesTheWelcome() {
        seedBot(brandA);
        configureChannel(brandA, false, Map.of("en", "Hello"), Map.of(), false);
        String token = mintLink(brandA, "TELEGRAM_BOT");

        deliver(start(token, 5022L, "en"));

        verify(conversations, never()).handleStart(any());
        assertThat(bot.messagesSentTo(5022L)).containsExactly("Hello");
    }

    // -------------------------------------------------------------------- fixtures

    private void deliver(Map<String, Object> update) {
        handler.handle(
                new WebhookInstallation(
                        installationId,
                        tenant,
                        "TELEGRAM_BOT_API",
                        "ACTIVE",
                        bot.baseUrl(),
                        SECRET_REFERENCE,
                        SECRET_REFERENCE),
                update);
    }

    private static Map<String, Object> start(String payload, long userId, @Nullable String languageCode) {
        return update("/start " + payload, userId, languageCode);
    }

    private static Map<String, Object> bareStart(long userId, @Nullable String languageCode) {
        return update("/start", userId, languageCode);
    }

    private static Map<String, Object> update(String text, long userId, @Nullable String languageCode) {
        Map<String, Object> from = new LinkedHashMap<>();
        from.put("id", userId);
        if (languageCode != null) {
            from.put("language_code", languageCode);
        }
        return Map.of(
                "update_id",
                UPDATE_IDS.getAndIncrement(),
                "message",
                Map.of("text", text, "chat", Map.of("id", userId, "type", "private"), "from", from));
    }

    private String mintLink(UUID brand, String channel) {
        UUID id = links.mint(
                tenant, brand, "Instagram: Aziza", null, channel, "INFLUENCER", null, null, UUID.randomUUID());
        return links.require(tenant, id).token();
    }

    private UUID linkId(String token) {
        return jdbc.sql("SELECT id FROM marketing.attribution_links WHERE tenant_id = :t AND token = :token")
                .param("t", tenant)
                .param("token", token)
                .query(UUID.class)
                .single();
    }

    private int clicks(String token) {
        Integer total = jdbc.sql(
                        "SELECT click_count FROM marketing.attribution_links WHERE tenant_id = :t AND token = :token")
                .param("t", tenant)
                .param("token", token)
                .query(Integer.class)
                .single();
        Integer series = jdbc.sql("""
                        SELECT coalesce(sum(c.clicks), 0)::int FROM marketing.attribution_link_clicks_day c
                        JOIN marketing.attribution_links l ON l.id = c.link_id AND l.tenant_id = c.tenant_id
                        WHERE l.tenant_id = :t AND l.token = :token
                        """)
                .param("t", tenant)
                .param("token", token)
                .query(Integer.class)
                .single();
        assertThat(series)
                .as("the running total and the day's series cannot disagree")
                .isEqualTo(total);
        return total;
    }

    private void configureChannel(UUID brand, boolean miniApp, String welcome, @Nullable String label) {
        configureChannel(brand, miniApp, Map.of("ru", welcome), label == null ? Map.of() : Map.of("ru", label), false);
    }

    private void configureChannel(
            UUID brand, boolean miniApp, Map<String, String> welcomes, Map<String, String> labels, boolean offer) {
        UUID channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status,
                    provider_installation_id, version)
                VALUES (:id, :t, 'BOT', 'TELEGRAM', 'Bot', 'ACTIVE', :installation, 1)
                """)
                .param("id", channelId)
                .param("t", tenant)
                .param("installation", installationId)
                .update();
        new JdbcTelegramChannelSettingsStore(jdbc)
                .replace(tenant, channelId, brand, miniApp, miniApp ? "order" : null, offer, labels, welcomes, 1, NOW);
    }

    private void seedStorefront(UUID brand, String hostname, boolean verified) {
        UUID channelId = UUID.randomUUID();
        UUID location = jdbc.sql("SELECT id FROM tenant.locations WHERE tenant_id = :t AND brand_id = :b")
                .param("t", tenant)
                .param("b", brand)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status, version)
                VALUES (:id, :t, :code, 'WEB', 'Web', 'ACTIVE', 1)
                """)
                .param("id", channelId)
                .param("t", tenant)
                .param("code", "WEB" + channelId.toString().substring(0, 4).toUpperCase())
                .update();
        jdbc.sql("""
                INSERT INTO tenant.channel_hostnames (tenant_id, channel_id, hostname, verified)
                VALUES (:t, :c, :h, :v)
                """)
                .param("t", tenant)
                .param("c", channelId)
                .param("h", hostname)
                .param("v", verified)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                VALUES (:t, :c, :l, 'ACTIVE')
                """)
                .param("t", tenant)
                .param("c", channelId)
                .param("l", location)
                .update();
    }

    private void seedBot(@Nullable UUID brand) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('landing-test-env', 'NOTIFICATION', 'TELEGRAM_BOT_API', :baseUrl, false, '127.0.0.1')
                ON CONFLICT (code) DO UPDATE SET base_url = EXCLUDED.base_url
                """).param("baseUrl", bot.baseUrl()).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference, brand_id)
                VALUES (:id, :t, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'landing-test-env',
                        'Bot', 'ACTIVE', :secret, :secret, :brand)
                """)
                .param("id", installationId)
                .param("t", tenant)
                .param("secret", SECRET_REFERENCE)
                .param("brand", brand)
                .update();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenant)
                .param("slug", "landing-" + tenant.toString().substring(0, 8))
                .update();
        for (UUID brand : new UUID[] {brandA, brandB}) {
            jdbc.sql("""
                    INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                    VALUES (:id, :t, :code, :slug, 'Brand', 'ACTIVE', 0)
                    """)
                    .param("id", brand)
                    .param("t", tenant)
                    .param("code", "B" + brand.toString().substring(0, 6).toUpperCase())
                    .param("slug", "brand-" + brand.toString().substring(0, 8))
                    .update();
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                    VALUES (:id, :t, :b, 'MAIN01', :slug, 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("t", tenant)
                    .param("b", brand)
                    .param("slug", "loc-" + brand.toString().substring(0, 8))
                    .update();
        }
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE marketing.attribution_link_clicks_day, marketing.attribution_links CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.telegram_processed_updates, integration.binding_capabilities, "
                        + "integration.bindings, integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }
}
