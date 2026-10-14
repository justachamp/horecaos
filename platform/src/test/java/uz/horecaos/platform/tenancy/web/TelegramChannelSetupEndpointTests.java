package uz.horecaos.platform.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * A TELEGRAM channel's own settings over HTTP (ADR 0183 D27, row 10.5): the menu button label and welcome text per
 * language, the Mini App for one brand, the BotFather short name and the public-offer link.
 *
 * <p>What these prove, in the order a tenant meets them: nothing a tenant types becomes an address (the Mini App URL is
 * the brand's <em>verified</em> storefront hostname, or a reason there is none); a language the platform does not send
 * messages in is refused; and the write is audited, version-gated and capability-gated like every other facet of
 * Channel setup.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TelegramChannelSetupEndpointTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID TENANT = UUID.fromString("018f9a20-4000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9a20-4000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018f9a20-4000-7000-8000-0000000000c1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9a20-4000-7000-8000-0000000000c2");
    private static final UUID LOCATION = UUID.fromString("018f9a20-4000-7000-8000-0000000000d1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9a20-4000-7000-8000-0000000000d2");
    private static final UUID BOT_CHANNEL = UUID.fromString("018f9a20-4000-7000-8000-0000000000b1");
    private static final UUID WEB_CHANNEL = UUID.fromString("018f9a20-4000-7000-8000-0000000000b2");
    private static final UUID SECOND_WEB_CHANNEL = UUID.fromString("018f9a20-4000-7000-8000-0000000000b3");
    private static final UUID OTHER_TENANTS_BOT_CHANNEL = UUID.fromString("018f9a20-4000-7000-8000-0000000000b4");

    private static final String OWNER = "telegram-setup-owner";
    private static final String READ_ONLY = "telegram-setup-reader";

    private static final String IDEMPOTENCY_HEADER = IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER;

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the Telegram channel setup endpoint test");
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
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT, "telegram-setup");
        insertTenant(OTHER_TENANT, "telegram-setup-other");
        insertBrand(TENANT, BRAND, "MAIN");
        insertBrand(TENANT, OTHER_BRAND, "SECOND");
        insertLocation(TENANT, BRAND, LOCATION, "MAIN01");
        insertLocation(TENANT, OTHER_BRAND, OTHER_LOCATION, "SEC01");
        insertChannel(TENANT, BOT_CHANNEL, "BOT", "TELEGRAM");
        insertChannel(TENANT, WEB_CHANNEL, "WEB", "WEB");
        insertChannel(TENANT, SECOND_WEB_CHANNEL, "WEB2", "WEB");
        insertChannel(OTHER_TENANT, OTHER_TENANTS_BOT_CHANNEL, "BOT", "TELEGRAM");
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
        grant(READ_ONLY, PlatformRole.BRAND_MANAGER, TENANT);
    }

    private String path(UUID tenantId, UUID channelId) {
        return "/api/v1/control-plane/tenants/" + tenantId + "/sales-channels/" + channelId + "/setup/telegram";
    }

    private MvcResult save(String key, String body, int expectedVersion) throws Exception {
        return mvc.perform(put(path(TENANT, BOT_CHANNEL))
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("expectedVersion", String.valueOf(expectedVersion))
                        .content(body))
                .andReturn();
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a channel nobody has configured reads as everything off, and says why there is no Mini App address")
    void anUnconfiguredChannelReadsAsOff() throws Exception {
        MvcResult read = mvc.perform(get(path(TENANT, BOT_CHANNEL)).with(tokenFor(OWNER)))
                .andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = body(read);
        assertThat(view.path("miniAppEnabled").asBoolean()).isFalse();
        assertThat(view.path("miniAppUrl").isNull()).isTrue();
        assertThat(view.path("miniAppUnavailable").asText()).isEqualTo("BRAND_NOT_CHOSEN");
        assertThat(view.path("channelVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the Mini App address is the brand's verified storefront hostname, and a tenant cannot type another")
    void theMiniAppAddressIsDerivedFromTheVerifiedHostname() throws Exception {
        claimHostname(WEB_CHANNEL, "shop.example.uz", true);
        bindToLocation(WEB_CHANNEL, LOCATION);

        MvcResult saved = save("derive-1", """
                {"brandId":"%s","miniAppEnabled":true,"miniAppShortName":"order",
                 "publicOfferLink":true,"miniAppUrl":"https://evil.example/","menuButtonUrl":"https://evil.example/"}
                """.formatted(BRAND), 1);

        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = body(saved);
        assertThat(view.path("miniAppUrl").asText())
                .as("a URL in the request body is not a field: the address comes from the verified hostname")
                .isEqualTo("https://shop.example.uz/");
        assertThat(view.path("publicOfferUrl").asText()).isEqualTo("https://shop.example.uz/terms");
        assertThat(view.path("miniAppShortName").asText()).isEqualTo("order");
        assertThat(view.path("channelVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unverified hostname gives no Mini App address, and says it is unverified")
    void anUnverifiedHostnameIsNotAnAddress() throws Exception {
        claimHostname(WEB_CHANNEL, "pending.example.uz", false);
        bindToLocation(WEB_CHANNEL, LOCATION);

        JsonNode view = body(save("unverified-1", """
                {"brandId":"%s","miniAppEnabled":true,"publicOfferLink":true}
                """.formatted(BRAND), 1));

        assertThat(view.path("miniAppUrl").isNull()).isTrue();
        assertThat(view.path("miniAppUnavailable").asText()).isEqualTo("HOSTNAME_NOT_VERIFIED");
        assertThat(view.path("publicOfferUrl").isNull()).isTrue();
    }

    @Test
    @DisplayName(
            "a brand whose branches sell on no hostname-bearing web channel has none; another brand's hostname is not borrowed")
    void anotherBrandsHostnameIsNeverBorrowed() throws Exception {
        claimHostname(WEB_CHANNEL, "shop.example.uz", true);
        bindToLocation(WEB_CHANNEL, OTHER_LOCATION);

        JsonNode view = body(save("not-borrowed-1", """
                {"brandId":"%s","miniAppEnabled":true}
                """.formatted(BRAND), 1));

        assertThat(view.path("miniAppUrl").isNull()).isTrue();
        assertThat(view.path("miniAppUnavailable").asText()).isEqualTo("HOSTNAME_NOT_CLAIMED");
    }

    @Test
    @DisplayName("two verified hostnames at one brand's branches name no storefront, and the answer says so")
    void twoVerifiedHostnamesAreAmbiguous() throws Exception {
        claimHostname(WEB_CHANNEL, "shop.example.uz", true);
        claimHostname(SECOND_WEB_CHANNEL, "orders.example.uz", true);
        bindToLocation(WEB_CHANNEL, LOCATION);
        bindToLocation(SECOND_WEB_CHANNEL, LOCATION);

        JsonNode view = body(save("ambiguous-1", """
                {"brandId":"%s","miniAppEnabled":true}
                """.formatted(BRAND), 1));

        assertThat(view.path("miniAppUrl").isNull()).isTrue();
        assertThat(view.path("miniAppUnavailable").asText()).isEqualTo("HOSTNAME_AMBIGUOUS");
    }

    @Test
    @DisplayName(
            "labels and welcome texts are stored per language under the registry's tag; an alias is read, never stored")
    void textsAreStoredUnderTheRegistryTag() throws Exception {
        MvcResult saved = save("texts-1", """
                {"menuButtonLabels":{"ru":"Заказать","uz":"Buyurtma","en":"Order"},
                 "welcomeTexts":{"ru":"Добро пожаловать","uz-Latn":"Xush kelibsiz","en":"  Welcome  "}}
                """, 1);

        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = body(saved);
        assertThat(view.path("menuButtonLabels").path("uz-Latn").asText()).isEqualTo("Buyurtma");
        assertThat(view.path("menuButtonLabels").has("uz")).isFalse();
        assertThat(view.path("welcomeTexts").path("en").asText()).isEqualTo("Welcome");
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.telegram_channel_texts WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Integer.class)
                        .single())
                .isEqualTo(6);
    }

    @Test
    @DisplayName("a full replace: an omitted language drops its text and a blank text means no text")
    void aSaveReplacesEverything() throws Exception {
        save("replace-1", """
                {"welcomeTexts":{"ru":"Привет","en":"Hello"}}
                """, 1);

        JsonNode view = body(save("replace-2", """
                {"welcomeTexts":{"ru":"Здравствуйте","en":"   "}}
                """, 2));

        assertThat(view.path("welcomeTexts").path("ru").asText()).isEqualTo("Здравствуйте");
        assertThat(view.path("welcomeTexts").has("en")).isFalse();
    }

    @Test
    @DisplayName(
            "a menu button label past Telegram's 64 characters is refused, and so is a language no message is sent in")
    void tooLongOrUnknownLanguageIsRefused() throws Exception {
        MvcResult tooLong = save("long-1", """
                {"menuButtonLabels":{"en":"%s"}}
                """.formatted("x".repeat(65)), 1);
        MvcResult unknown = save("unknown-1", """
                {"welcomeTexts":{"kk":"Қош келдіңіз"}}
                """, 1);
        MvcResult notALanguage = save("unknown-2", """
                {"welcomeTexts":{"klingon":"nuqneH"}}
                """, 1);

        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
        assertThat(unknown.getResponse().getStatus())
                .as("kk is declared in the registry but live in no tier")
                .isEqualTo(400);
        assertThat(notALanguage.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.telegram_channel_texts")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("a short name is what BotFather accepts, and a Mini App needs a brand to open")
    void shortNameAndBrandAreValidated() throws Exception {
        MvcResult badName = save("name-1", """
                {"miniAppShortName":"has space!"}
                """, 1);
        MvcResult noBrand = save("brand-1", """
                {"miniAppEnabled":true}
                """, 1);
        MvcResult foreignBrand = save("brand-2", """
                {"brandId":"%s","miniAppEnabled":true}
                """.formatted(UUID.randomUUID()), 1);

        assertThat(badName.getResponse().getStatus()).isEqualTo(400);
        assertThat(noBrand.getResponse().getStatus()).isEqualTo(400);
        assertThat(foreignBrand.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("only a TELEGRAM channel has these settings")
    void aWebChannelHasNone() throws Exception {
        MvcResult read = mvc.perform(get(path(TENANT, WEB_CHANNEL)).with(tokenFor(OWNER)))
                .andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(400);
        assertThat(read.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("a write against a stale channel version is refused and writes nothing")
    void aStaleVersionIsRefused() throws Exception {
        MvcResult stale = save("stale-1", """
                {"welcomeTexts":{"en":"Hello"}}
                """, 7);

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.telegram_channel_texts")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("a reader cannot save the settings; a tenant cannot reach another tenant's channel")
    void capabilityAndTenantIsolation() throws Exception {
        MvcResult refused = mvc.perform(put(path(TENANT, BOT_CHANNEL))
                        .with(tokenFor(READ_ONLY))
                        .header(IDEMPOTENCY_HEADER, "reader-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("expectedVersion", "1")
                        .content("{\"welcomeTexts\":{\"en\":\"Hello\"}}"))
                .andReturn();
        MvcResult crossTenant = mvc.perform(
                        get(path(TENANT, OTHER_TENANTS_BOT_CHANNEL)).with(tokenFor(OWNER)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains(Capability.CHANNEL_MANAGE.code());
        assertThat(crossTenant.getResponse().getStatus())
                .as("OTHER_TENANTS_BOT_CHANNEL is another tenant's, so this tenant's path must not find it")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("a save leaves one audit fact with a per-field diff, and the diff names no URL a tenant typed")
    void aSaveIsAudited() throws Exception {
        claimHostname(WEB_CHANNEL, "shop.example.uz", true);
        bindToLocation(WEB_CHANNEL, LOCATION);
        save("audit-1", """
                {"brandId":"%s","miniAppEnabled":true,"miniAppShortName":"order",
                 "menuButtonLabels":{"en":"Order"}}
                """.formatted(BRAND), 1);

        List<String> documents = jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events
                        WHERE action_code = 'channel.telegram.updated'
                        """).query(String.class).list();

        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst())
                .contains("miniAppEnabled")
                .contains("miniAppShortName")
                .contains("order")
                .doesNotContain("evil");
    }

    // -------------------------------------------------------------- fixtures

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertBrand(UUID tenantId, UUID brandId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .update();
    }

    private void insertLocation(UUID tenantId, UUID brandId, UUID locationId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .update();
    }

    private void insertChannel(UUID tenantId, UUID channelId, String code, String systemType) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status, version)
                VALUES (:id, :tenantId, :code, :systemType, 'Channel', 'ACTIVE', 1)
                """)
                .param("id", channelId)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("systemType", systemType)
                .update();
    }

    private void claimHostname(UUID channelId, String hostname, boolean verified) {
        jdbc.sql("""
                INSERT INTO tenant.channel_hostnames (tenant_id, channel_id, hostname, verified)
                VALUES (:tenantId, :channelId, :hostname, :verified)
                """)
                .param("tenantId", TENANT)
                .param("channelId", channelId)
                .param("hostname", hostname)
                .param("verified", verified)
                .update();
    }

    private void bindToLocation(UUID channelId, UUID locationId) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                VALUES (:tenantId, :channelId, :locationId, 'ACTIVE')
                """)
                .param("tenantId", TENANT)
                .param("channelId", channelId)
                .param("locationId", locationId)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'telegram channel setup endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param(
                        "id",
                        UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(StandardCharsets.UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
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
