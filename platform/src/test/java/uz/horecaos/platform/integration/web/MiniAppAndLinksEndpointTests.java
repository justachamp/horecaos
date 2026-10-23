package uz.horecaos.platform.integration.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.integration.camel.notification.telegram.FakeTelegramBotApi;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Acquisition links that work, and the guided Mini App setup (ADR 0183, gap-map row {@code 6.6a}), over HTTP and against a
 * real Bot API socket.
 *
 * <p>Two things are proved. A link's URL is built on the server from facts the platform holds (the brand's <em>verified</em>
 * storefront hostname, the bot's username, the Mini App's short name) and says what is missing when it cannot, with another
 * brand's hostname or bot never borrowed. And the setup flow derives every step from those facts and from what Telegram itself
 * reports: a menu button that Telegram accepts and does not show is not believed, and the bot's token never leaves the
 * secrets manager.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MiniAppAndLinksEndpointTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID TENANT = UUID.fromString("018f9b20-8000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b20-8000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-8000-7000-8000-0000000000b2");
    private static final UUID LOCATION = UUID.fromString("018f9b20-8000-7000-8000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9b20-8000-7000-8000-0000000000c2");
    private static final UUID WEB_CHANNEL = UUID.fromString("018f9b20-8000-7000-8000-0000000000d1");
    private static final UUID BOT_CHANNEL = UUID.fromString("018f9b20-8000-7000-8000-0000000000d2");
    private static final UUID SECOND_WEB_CHANNEL = UUID.fromString("018f9b20-8000-7000-8000-0000000000d3");
    private static final UUID INSTALLATION = UUID.fromString("018f9b20-8000-7000-8000-0000000000e1");

    /** The tenant's owner: holds {@code integration.installation.manage} at the tenant, which is what the setup needs. */
    private static final String OWNER = "018f9b20-8000-7000-8000-0000000000f1";

    /** A brand manager: mints the brand's links ({@code marketing-link.manage}) and has no say over integrations. */
    private static final String MARKETER = "018f9b20-8000-7000-8000-0000000000f2";

    private static final String BOT_TOKEN = "bot-token-never-in-a-response-123";

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
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private SecretIngressGateway door;

    @BeforeEach
    void setUp() throws IOException {
        bot = FakeTelegramBotApi.start();
        bot.setBotUsername("tandir_bot");

        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE marketing.attribution_link_clicks_day, marketing.attribution_links CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        botTokenReference = door.write(SecretCategory.PROVIDER_NOTIFICATION, "tenant-" + TENANT, secret(BOT_TOKEN))
                .toString();

        seedTenancy();
        roleRegistry.synchronize();
        grantBrandManager(MARKETER, BRAND);
        grantBrandManager(MARKETER, OTHER_BRAND);
        grantTenantOwner(OWNER);
    }

    @AfterEach
    void tearDown() {
        bot.close();
    }

    // ================================================================ the links

    @Test
    @DisplayName("a website link carries the brand's verified hostname, and no other brand's")
    void aWebLinkCarriesTheVerifiedHostname() throws Exception {
        seedWebHostname(WEB_CHANNEL, "shop.example.uz", true, LOCATION);
        String token = mint(BRAND, "WEB");
        String otherToken = mint(OTHER_BRAND, "WEB");

        JsonNode mine = linkOf(BRAND, token);
        JsonNode theirs = linkOf(OTHER_BRAND, otherToken);

        assertThat(mine.path("url").asText()).isEqualTo("https://shop.example.uz/?ref=" + token);
        assertThat(mine.path("unavailable").isNull()).isTrue();
        assertThat(theirs.path("url").isNull())
                .as("the other brand's branches sell on no website channel, and this brand's hostname is not borrowed")
                .isTrue();
        assertThat(theirs.path("unavailable").asText()).isEqualTo("WEB_HOSTNAME_NOT_CLAIMED");
    }

    @Test
    @DisplayName("a hostname the platform has not verified builds no link, and says so")
    void anUnverifiedHostnameBuildsNoLink() throws Exception {
        seedWebHostname(WEB_CHANNEL, "pending.example.uz", false, LOCATION);
        String token = mint(BRAND, "WEB");

        JsonNode link = linkOf(BRAND, token);

        assertThat(link.path("url").isNull()).isTrue();
        assertThat(link.path("unavailable").asText()).isEqualTo("WEB_HOSTNAME_NOT_VERIFIED");
    }

    @Test
    @DisplayName("two verified hostnames at one brand's branches name no storefront, and no link is guessed")
    void twoVerifiedHostnamesBuildNoLink() throws Exception {
        seedWebHostname(WEB_CHANNEL, "shop.example.uz", true, LOCATION);
        seedWebHostname(UUID.randomUUID(), "orders.example.uz", true, LOCATION);
        String token = mint(BRAND, "WEB");

        assertThat(linkOf(BRAND, token).path("unavailable").asText()).isEqualTo("WEB_HOSTNAME_AMBIGUOUS");
    }

    @Test
    @DisplayName("a bot link names the bot, and says the bot is not connected when it is not")
    void aBotLinkNamesTheBot() throws Exception {
        String token = mint(BRAND, "TELEGRAM_BOT");
        assertThat(linkOf(BRAND, token).path("unavailable").asText()).isEqualTo("TELEGRAM_BOT_NOT_CONNECTED");

        seedBot(null, false);

        JsonNode link = linkOf(BRAND, token);
        assertThat(link.path("url").asText()).isEqualTo("https://t.me/tandir_bot?start=" + token);
        assertThat(link.path("unavailable").isNull()).isTrue();
    }

    @Test
    @DisplayName("a bot provisioned for another brand is not this brand's bot")
    void anotherBrandsBotIsNotBorrowed() throws Exception {
        seedBot(OTHER_BRAND, false);
        String token = mint(BRAND, "TELEGRAM_BOT");

        assertThat(linkOf(BRAND, token).path("unavailable").asText()).isEqualTo("TELEGRAM_BOT_NOT_CONNECTED");
    }

    @Test
    @DisplayName("a Mini App link needs the short name BotFather gave, recorded for this brand's storefront")
    void aMiniAppLinkNeedsTheShortName() throws Exception {
        seedBot(null, false);
        String token = mint(BRAND, "TELEGRAM_MINI_APP");
        assertThat(linkOf(BRAND, token).path("unavailable").asText()).isEqualTo("TELEGRAM_MINI_APP_NOT_SET_UP");

        seedTelegramChannel(BRAND, true, "order");
        JsonNode ready = linkOf(BRAND, token);
        assertThat(ready.path("url").asText()).isEqualTo("https://t.me/tandir_bot/order?startapp=" + token);

        seedTelegramChannelFor(OTHER_BRAND);
        assertThat(linkOf(BRAND, token).path("unavailable").asText())
                .as("the Mini App now opens the other brand's storefront, so its short name is not this brand's")
                .isEqualTo("TELEGRAM_MINI_APP_NOT_SET_UP");
    }

    @Test
    @DisplayName("a bot that Telegram will not name just now is «unreachable», not «not connected»")
    void anUnreachableBotIsSaidSo() throws Exception {
        seedBot(null, false);
        bot.revokeToken();
        String token = mint(BRAND, "TELEGRAM_BOT");

        assertThat(linkOf(BRAND, token).path("unavailable").asText()).isEqualTo("TELEGRAM_BOT_UNREACHABLE");
    }

    @Test
    @DisplayName("a list of links asks Telegram for the bot's name once, not once per link")
    void theBotIsAskedOnce() throws Exception {
        seedBot(null, false);
        for (int i = 0; i < 4; i++) {
            mint(BRAND, "TELEGRAM_BOT");
        }

        MvcResult list =
                mvc.perform(get(linksPath(BRAND)).with(token(MARKETER))).andReturn();

        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(list.getResponse().getContentAsString())).hasSize(4);
        assertThat(bot.getMeCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a mobile app link can no longer be minted, and one minted before still reads, saying where it goes")
    void aMobileAppLinkIsNotOffered() throws Exception {
        MvcResult refused = mvc.perform(
                        post(linksPath(BRAND))
                                .with(token(MARKETER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "mobile-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"label\":\"App\",\"channel\":\"MOBILE_APP\",\"destinationType\":\"STOREFRONT_HOME\"}"))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);

        UUID legacy = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO marketing.attribution_links (id, tenant_id, brand_id, label, token, channel,
                    destination_type, status, valid_from, created_by)
                VALUES (:id, :t, :b, 'Old app link', 'legacyTok1', 'MOBILE_APP', 'STOREFRONT_HOME', 'ACTIVE', now(), :by)
                """)
                .param("id", legacy)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("by", UUID.randomUUID())
                .update();
        JsonNode link = linkOf(BRAND, "legacyTok1");
        assertThat(link.path("url").isNull()).isTrue();
        assertThat(link.path("unavailable").asText()).isEqualTo("MOBILE_APP_ON_HOLD");
    }

    // ================================================================ the setup flow

    @Test
    @DisplayName("a brand with nothing connected sees every step that depends on a fact still open")
    void aBrandWithNothingConnectedSeesTheStepsOpen() throws Exception {
        JsonNode status = status(OTHER_BRAND);

        assertThat(stateOf(status, "CONNECT_BOT")).isEqualTo("TODO");
        assertThat(stateOf(status, "TELEGRAM_CHANNEL")).isEqualTo("TODO");
        assertThat(stateOf(status, "STOREFRONT_ADDRESS")).isEqualTo("TODO");
        assertThat(detailOf(status, "STOREFRONT_ADDRESS")).isEqualTo("NOT_CLAIMED");
        assertThat(stateOf(status, "MENU_BUTTON")).isEqualTo("TODO");
        assertThat(status.path("steps")).hasSize(7);
    }

    @Test
    @DisplayName(
            "every step follows its fact, so the flow resumes where it was left and notices what changed meanwhile")
    void theStepsFollowTheFacts() throws Exception {
        seedWebHostname(WEB_CHANNEL, "shop.example.uz", true, LOCATION);
        seedBot(null, false);

        JsonNode first = status(BRAND);
        assertThat(stateOf(first, "CONNECT_BOT")).isEqualTo("DONE");
        assertThat(stateOf(first, "REGISTER_WEBHOOK")).isEqualTo("TODO");
        assertThat(stateOf(first, "TELEGRAM_CHANNEL")).isEqualTo("TODO");
        assertThat(stateOf(first, "STOREFRONT_ADDRESS")).isEqualTo("DONE");
        assertThat(stateOf(first, "BOTFATHER_APP")).isEqualTo("TODO");
        assertThat(first.path("botUsername").asText()).isEqualTo("tandir_bot");
        assertThat(first.path("miniAppUrl").asText()).isEqualTo("https://shop.example.uz/");

        jdbc.sql("UPDATE integration.installations SET webhook_secret_reference = :r WHERE id = :id")
                .param("r", botTokenReference)
                .param("id", INSTALLATION)
                .update();
        seedTelegramChannel(BRAND, true, "order");

        JsonNode later = status(BRAND);
        assertThat(stateOf(later, "REGISTER_WEBHOOK")).isEqualTo("DONE");
        assertThat(stateOf(later, "TELEGRAM_CHANNEL")).isEqualTo("DONE");
        assertThat(stateOf(later, "BOTFATHER_APP")).isEqualTo("DONE");
        assertThat(later.path("miniAppShortName").asText()).isEqualTo("order");
        assertThat(later.path("testLink").asText()).isEqualTo("https://t.me/tandir_bot/order");
        assertThat(stateOf(later, "MENU_BUTTON")).isEqualTo("TODO");
        assertThat(detailOf(later, "MENU_BUTTON")).isEqualTo("NOT_APPLIED");

        jdbc.sql("UPDATE tenant.channel_hostnames SET verified = false WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        assertThat(stateOf(status(BRAND), "STOREFRONT_ADDRESS"))
                .as("the hostname was un-verified in another screen: the flow believes what is true now")
                .isEqualTo("TODO");
    }

    @Test
    @DisplayName("setting the menu button reaches Telegram, is read back from it, and leaves one audit fact")
    void applyingTheMenuButtonIsReadBackAndAudited() throws Exception {
        readyToApply();

        MvcResult applied = applyMenuButton(BRAND);

        assertThat(applied.getResponse().getStatus()).isEqualTo(200);
        JsonNode status = JSON.readTree(applied.getResponse().getContentAsString());
        assertThat(stateOf(status, "MENU_BUTTON")).isEqualTo("DONE");
        assertThat(status.path("menuButtonUrl").asText()).isEqualTo("https://shop.example.uz/");
        assertThat(bot.defaultMenuButton())
                .containsEntry("type", "web_app")
                .containsEntry("text", "Меню")
                .containsEntry("web_app", Map.of("url", "https://shop.example.uz/"));
        assertThat(auditFacts("integration.telegram_menu_button_applied")).hasSize(1);

        assertThat(stateOf(status(BRAND), "MENU_BUTTON"))
                .as("a second look, with nothing remembered, derives the same answer from Telegram")
                .isEqualTo("DONE");
    }

    @Test
    @DisplayName("the label is the channel's own in the brand's default language")
    void theLabelIsTheChannelsOwn() throws Exception {
        readyToApply();
        jdbc.sql("""
                INSERT INTO tenant.telegram_channel_texts (tenant_id, channel_id, kind, locale, body)
                VALUES (:t, :c, 'MENU_BUTTON_LABEL', 'ru', 'Заказать')
                """).param("t", TENANT).param("c", BOT_CHANNEL).update();

        applyMenuButton(BRAND);

        assertThat(bot.defaultMenuButton()).containsEntry("text", "Заказать");
    }

    @Test
    @DisplayName("a Telegram that shows another button leaves the step open, and applying puts ours there")
    void aDifferentButtonIsNotOurs() throws Exception {
        readyToApply();
        applyMenuButton(BRAND);
        jdbc.sql("UPDATE tenant.channel_hostnames SET hostname = 'moved.example.uz' WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();

        JsonNode drifted = status(BRAND);
        assertThat(stateOf(drifted, "MENU_BUTTON")).isEqualTo("TODO");
        assertThat(drifted.path("menuButtonUrl").asText())
                .as("Telegram still shows the old address")
                .isEqualTo("https://shop.example.uz/");

        JsonNode fixed = JSON.readTree(applyMenuButton(BRAND).getResponse().getContentAsString());
        assertThat(stateOf(fixed, "MENU_BUTTON")).isEqualTo("DONE");
        assertThat(bot.defaultMenuButton()).containsEntry("web_app", Map.of("url", "https://moved.example.uz/"));
    }

    @Test
    @DisplayName("a Telegram that says ok and shows nothing new is not believed, and nothing is audited as done")
    void anAcceptedButIgnoredCallIsNotBelieved() throws Exception {
        readyToApply();
        bot.ignoreMenuButtonWrites();

        MvcResult applied = applyMenuButton(BRAND);

        assertThat(applied.getResponse().getStatus()).isEqualTo(200);
        assertThat(stateOf(JSON.readTree(applied.getResponse().getContentAsString()), "MENU_BUTTON"))
                .isEqualTo("TODO");
        assertThat(auditFacts("integration.telegram_menu_button_applied")).isEmpty();
    }

    @Test
    @DisplayName("a Telegram that refuses the call answers 422 and changes nothing here")
    void aRefusalIsReported() throws Exception {
        readyToApply();
        bot.failNextSetChatMenuButton();

        MvcResult applied = applyMenuButton(BRAND);

        assertThat(applied.getResponse().getStatus()).isEqualTo(422);
        assertThat(applied.getResponse().getContentAsString()).contains("UNPROCESSABLE_STATE");
        assertThat(auditFacts("integration.telegram_menu_button_applied")).isEmpty();
    }

    @Test
    @DisplayName(
            "nothing is sent to Telegram until the Mini App is on, the address verified, and the channel this brand's")
    void applyNeedsEveryPrecondition() throws Exception {
        seedWebHostname(WEB_CHANNEL, "shop.example.uz", true, LOCATION);
        seedBot(null, true);

        assertThat(applyMenuButton(BRAND).getResponse().getStatus())
                .as("no Telegram sales channel backs the bot yet")
                .isEqualTo(422);

        seedTelegramChannel(BRAND, false, null);
        assertThat(applyMenuButton(BRAND).getResponse().getStatus())
                .as("the Mini App is switched off")
                .isEqualTo(422);

        jdbc.sql("UPDATE tenant.telegram_channel_settings SET mini_app_enabled = true WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        seedWebHostname(SECOND_WEB_CHANNEL, "other.example.uz", true, OTHER_LOCATION);
        assertThat(applyMenuButton(OTHER_BRAND).getResponse().getStatus())
                .as("the other brand has an address, but the channel's Mini App opens this brand's storefront")
                .isEqualTo(422);

        jdbc.sql("UPDATE tenant.channel_hostnames SET verified = false WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        assertThat(applyMenuButton(BRAND).getResponse().getStatus())
                .as("an unverified address is no address")
                .isEqualTo(422);

        assertThat(bot.setChatMenuButtonCallCount()).isZero();
    }

    @Test
    @DisplayName(
            "only someone who may manage integrations can read or use the setup, and no response carries the bot's token")
    void capabilityAndSecrecy() throws Exception {
        readyToApply();

        MvcResult readByMarketer =
                mvc.perform(get(setupPath(BRAND)).with(token(MARKETER))).andReturn();
        MvcResult appliedByMarketer = mvc.perform(post(setupPath(BRAND) + "/menu-button")
                        .with(token(MARKETER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "marketer-1"))
                .andReturn();
        MvcResult read = mvc.perform(get(setupPath(BRAND)).with(token(OWNER))).andReturn();
        MvcResult applied = applyMenuButton(BRAND);

        assertThat(readByMarketer.getResponse().getStatus()).isEqualTo(403);
        assertThat(appliedByMarketer.getResponse().getStatus()).isEqualTo(403);
        assertThat(bot.setChatMenuButtonCallCount()).isEqualTo(1);
        assertThat(read.getResponse().getContentAsString()).doesNotContain(BOT_TOKEN);
        assertThat(applied.getResponse().getContentAsString()).doesNotContain(BOT_TOKEN);
    }

    // ------------------------------------------------------------------ fixtures

    private void readyToApply() {
        seedWebHostname(WEB_CHANNEL, "shop.example.uz", true, LOCATION);
        seedBot(null, true);
        seedTelegramChannel(BRAND, true, "order");
    }

    private MvcResult applyMenuButton(UUID brand) throws Exception {
        return mvc.perform(post(setupPath(brand) + "/menu-button")
                        .with(token(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "apply-" + UUID.randomUUID()))
                .andReturn();
    }

    private JsonNode status(UUID brand) throws Exception {
        MvcResult result = mvc.perform(get(setupPath(brand)).with(token(OWNER))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String stateOf(JsonNode status, String code) {
        for (JsonNode step : status.path("steps")) {
            if (code.equals(step.path("code").asText())) {
                return step.path("state").asText();
            }
        }
        throw new AssertionError("No step " + code);
    }

    private static @Nullable String detailOf(JsonNode status, String code) {
        for (JsonNode step : status.path("steps")) {
            if (code.equals(step.path("code").asText())) {
                return step.path("detail").isNull() ? null : step.path("detail").asText();
            }
        }
        throw new AssertionError("No step " + code);
    }

    private List<String> auditFacts(String actionCode) {
        return jdbc.sql("SELECT change_document::text FROM audit.audit_events WHERE action_code = :code")
                .param("code", actionCode)
                .query(String.class)
                .list();
    }

    private String mint(UUID brand, String channel) throws Exception {
        MvcResult minted = mvc.perform(post(linksPath(brand))
                        .with(token(MARKETER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "mint-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Influencer\",\"channel\":\"" + channel
                                + "\",\"destinationType\":\"INFLUENCER\"}"))
                .andReturn();
        assertThat(minted.getResponse().getStatus())
                .as(minted.getResponse().getContentAsString())
                .isEqualTo(201);
        return JSON.readTree(minted.getResponse().getContentAsString())
                .path("token")
                .asText();
    }

    private JsonNode linkOf(UUID brand, String token) throws Exception {
        MvcResult list =
                mvc.perform(get(linksPath(brand)).with(token(MARKETER))).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        for (JsonNode link : JSON.readTree(list.getResponse().getContentAsString())) {
            if (token.equals(link.path("token").asText())) {
                return link;
            }
        }
        throw new AssertionError("No link with token " + token);
    }

    private static String linksPath(UUID brand) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + brand + "/marketing/attribution-links";
    }

    private static String setupPath(UUID brand) {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + brand + "/telegram/mini-app-setup";
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'mini-app-links', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        seedBrand(BRAND, "MAIN", LOCATION);
        seedBrand(OTHER_BRAND, "OTHER", OTHER_LOCATION);
    }

    private void seedBrand(UUID brand, String code, UUID location) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", brand)
                .param("t", TENANT)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", location)
                .param("t", TENANT)
                .param("b", brand)
                .param("code", code + "01")
                .param("slug", code.toLowerCase(java.util.Locale.ROOT) + "-01")
                .update();
    }

    private void seedWebHostname(UUID channelId, String hostname, boolean verified, UUID location) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status, version)
                VALUES (:id, :t, :code, 'WEB', 'Web', 'ACTIVE', 1)
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", channelId)
                .param("t", TENANT)
                .param("code", "WEB" + channelId.toString().substring(30).toUpperCase())
                .update();
        jdbc.sql("""
                INSERT INTO tenant.channel_hostnames (tenant_id, channel_id, hostname, verified)
                VALUES (:t, :c, :h, :v)
                ON CONFLICT (tenant_id, channel_id) DO NOTHING
                """)
                .param("t", TENANT)
                .param("c", channelId)
                .param("h", hostname)
                .param("v", verified)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                VALUES (:t, :c, :l, 'ACTIVE')
                ON CONFLICT DO NOTHING
                """)
                .param("t", TENANT)
                .param("c", channelId)
                .param("l", location)
                .update();
    }

    private void seedBot(@Nullable UUID brand, boolean webhookRegistered) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('mini-app-links-env', 'NOTIFICATION', 'TELEGRAM_BOT_API', :baseUrl, false, '127.0.0.1')
                ON CONFLICT (code) DO UPDATE SET base_url = EXCLUDED.base_url
                """).param("baseUrl", bot.baseUrl()).update();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference, brand_id)
                VALUES (:id, :t, 'NOTIFICATION', 'TELEGRAM_BOT_API', 'mini-app-links-env',
                        'Bot', 'ACTIVE', :secret, :webhook, :brand)
                """)
                .param("id", INSTALLATION)
                .param("t", TENANT)
                .param("secret", botTokenReference)
                .param("webhook", webhookRegistered ? botTokenReference : null)
                .param("brand", brand)
                .update();
    }

    private void seedTelegramChannel(UUID brand, boolean miniApp, @Nullable String shortName) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status,
                    provider_installation_id, version)
                VALUES (:id, :t, 'BOT', 'TELEGRAM', 'Bot', 'ACTIVE', :installation, 1)
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", BOT_CHANNEL)
                .param("t", TENANT)
                .param("installation", INSTALLATION)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.telegram_channel_settings
                    (tenant_id, channel_id, brand_id, mini_app_enabled, mini_app_short_name, public_offer_link)
                VALUES (:t, :c, :b, :miniApp, :shortName, false)
                ON CONFLICT (tenant_id, channel_id)
                DO UPDATE SET brand_id = :b, mini_app_enabled = :miniApp, mini_app_short_name = :shortName
                """)
                .param("t", TENANT)
                .param("c", BOT_CHANNEL)
                .param("b", brand)
                .param("miniApp", miniApp)
                .param("shortName", shortName)
                .update();
    }

    /** Re-points the existing Telegram channel's Mini App at another brand, keeping the short name. */
    private void seedTelegramChannelFor(UUID brand) {
        jdbc.sql("UPDATE tenant.telegram_channel_settings SET brand_id = :b WHERE tenant_id = :t")
                .param("b", brand)
                .param("t", TENANT)
                .update();
    }

    private void grantBrandManager(String subject, UUID brand) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :t, :subject, :roleId, true, 'BRAND', :scope,
                        'ACTIVE', 'test-fixture', 'mini app endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + brand).getBytes(UTF_8)))
                .param("t", TENANT)
                .param("scope", brand)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.BRAND_MANAGER))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grantTenantOwner(String subject) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :t, :subject, :roleId, true, 'TENANT', :t,
                        'ACTIVE', 'test-fixture', 'mini app endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + "owner").getBytes(UTF_8)))
                .param("t", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.TENANT_OWNER))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static uz.horecaos.platform.iam.api.secrets.SecretValue secret(String value) {
        return uz.horecaos.platform.iam.api.secrets.SecretValue.of(value);
    }

    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)
                .claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.<String>of()))));
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
