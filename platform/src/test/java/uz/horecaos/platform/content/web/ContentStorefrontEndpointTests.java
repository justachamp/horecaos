package uz.horecaos.platform.content.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
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
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.content.ContentFixture;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * What a customer is shown, read over real HTTP the way a storefront reads it (ADR 0157, Decision 9
 * and the assembler's pipeline): anonymously, from one cacheable document, against a clock the test
 * moves so that a window's edges are asserted at the edge.
 *
 * <p>Content is authored through the staff API and read back through the storefront's, so the
 * assertions are about what the whole chain delivers; the rows that other modules own (the menu's
 * publication, promotions, media) are written straight into their tables, as the platform's own
 * tests write them.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContentStorefrontEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-6000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b20-6000-7000-8000-0000000000c2");
    private static final UUID BRAND = UUID.fromString("018f9b20-6000-7000-8000-0000000000d1");
    private static final UUID FOREIGN_BRAND = UUID.fromString("018f9b20-6000-7000-8000-0000000000d3");

    private static final UUID PRODUCT_A = UUID.fromString("018f9b20-6000-7000-8000-0000000000f1");
    private static final UUID PRODUCT_B = UUID.fromString("018f9b20-6000-7000-8000-0000000000f2");
    private static final UUID PRODUCT_GONE = UUID.fromString("018f9b20-6000-7000-8000-0000000000f3");
    private static final UUID CATEGORY = UUID.fromString("018f9b20-6000-7000-8000-0000000000f4");

    private static final String OWNER = "storefront-owner";

    private static final String CONTENT = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/content";
    private static final String HOME = "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/content/home";

    private final JsonMapper json = JsonMapper.builder().build();

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

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
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    @SuppressWarnings("NullAway")
    private MovableClock clock;

    private ContentFixture fixture;
    private UUID publication;
    private final AtomicInteger keys = new AtomicInteger();

    @BeforeEach
    void reset() {
        clock.set(Instant.now());
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        // The menu, promotions and media sit in other modules' tables, which a tenant truncate
        // does not reach through their tenant-blind references.
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, catalog.catalogs,"
                        + " pricing.promotions, media.assets CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        fixture = new ContentFixture(jdbc);
        fixture.tenant(TENANT, "content-storefront");
        fixture.tenant(OTHER_TENANT, "content-storefront-other");
        fixture.brand(TENANT, BRAND, "MAIN");
        fixture.brand(OTHER_TENANT, FOREIGN_BRAND, "FOREIGN");
        fixture.channel(TENANT, ContentFixture.STOREFRONT, "WEB");
        fixture.channel(TENANT, ContentFixture.IOS, "IOS");
        fixture.channel(TENANT, ContentFixture.QR, "QR_TABLE");
        publication = fixture.publication(
                TENANT,
                BRAND,
                ContentFixture.STOREFRONT,
                List.of(PRODUCT_A, PRODUCT_B, PRODUCT_GONE),
                List.of(CATEGORY));
        fixture.publication(TENANT, BRAND, ContentFixture.IOS, List.of(PRODUCT_A), List.of());
        fixture.grant(TENANT, OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
    }

    // ==================================================================== helpers

    private MvcResult send(MockHttpServletRequestBuilder request, @Nullable String body, @Nullable String ifMatch)
            throws Exception {
        request = request.with(tokenFor(OWNER)).header("Idempotency-Key", "storefront-key-" + keys.incrementAndGet());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(UTF_8));
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String etag(JsonNode item) {
        return "W/\"" + item.get("version").intValue() + "\"";
    }

    private JsonNode create(String itemJson) throws Exception {
        MvcResult created = send(post(CONTENT + "/items"), itemJson, null);
        assertThat(status(created))
                .as(created.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        return body(created);
    }

    private JsonNode publish(JsonNode item) throws Exception {
        MvcResult published =
                send(post(CONTENT + "/items/" + item.get("id").stringValue() + "/publications"), null, etag(item));
        assertThat(status(published))
                .as(published.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        return body(published);
    }

    private JsonNode withdraw(JsonNode item) throws Exception {
        MvcResult withdrawn =
                send(post(CONTENT + "/items/" + item.get("id").stringValue() + "/withdrawals"), null, etag(item));
        assertThat(status(withdrawn)).isEqualTo(200);
        return body(withdrawn);
    }

    private String banner(UUID asset, String title, String extra) {
        return "{\"kind\":\"BANNER\",\"internalName\":\"" + title + "\",\"document\":{\"texts\":{\"ru\":{\"title\":\""
                + title + "\"}},\"media\":{\"primaryAssetId\":\"" + asset + "\"}" + extra + "}}";
    }

    /** A published banner with the usual image; {@code extra} goes inside the document, {@code outer} beside it. */
    private JsonNode liveBanner(String title, String extra, String outer) throws Exception {
        UUID asset = fixture.goodBanner(TENANT, BRAND);
        String text = banner(asset, title, extra);
        return publish(create(outer.isEmpty() ? text : text.substring(0, text.length() - 1) + outer + "}"));
    }

    private JsonNode home(String channel, @Nullable String locale) throws Exception {
        MvcResult result = homeResult(channel, locale);
        assertThat(status(result))
                .as(result.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        return body(result);
    }

    private MvcResult homeResult(String channel, @Nullable String locale) throws Exception {
        String query = "?channel=" + channel + (locale == null ? "" : "&locale=" + locale);
        return mvc.perform(get(HOME + query)).andReturn();
    }

    private List<String> titles(JsonNode home) {
        List<String> titles = new ArrayList<>();
        home.get("carousel").forEach(item -> titles.add(item.get("title").stringValue()));
        return titles;
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    // ========================================================== the document

    @Test
    @DisplayName(
            "an anonymous visitor reads the home document: banners with ready-made rendition URLs, the text's language, a link, public cache headers and a digest ETag, and nothing a staff member typed for staff")
    void anAnonymousVisitorReadsTheHomeDocument() throws Exception {
        UUID asset = fixture.goodBanner(TENANT, BRAND);
        publish(create("""
                {"kind":"BANNER","internalName":"Staff-only name",
                 "document":{"texts":{"ru":{"title":"Осенняя скидка","subtitle":"Только осенью","ctaLabel":"Смотреть","alt":"Тыквенный суп"}},
                             "media":{"primaryAssetId":"%s"},
                             "link":{"type":"CATEGORY","targetId":"%s"}}}""".formatted(asset, CATEGORY)));

        MvcResult result = homeResult(ContentFixture.STOREFRONT, "ru");

        assertThat(status(result)).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control"))
                .contains("public")
                .contains("max-age=60");
        assertThat(result.getResponse().getHeader("ETag")).startsWith("\"").endsWith("\"");
        JsonNode home = body(result);
        assertThat(home.get("locale").stringValue()).isEqualTo("ru");
        assertThat(home.get("groups")).isEmpty();
        assertThat(home.has("stories"))
                .as("Release 1 brands are served exactly the Release 1 document")
                .isFalse();
        assertThat(home.has("popup")).isFalse();
        JsonNode item = home.get("carousel").get(0);
        assertThat(item.get("kind").stringValue()).isEqualTo("BANNER");
        assertThat(item.get("title").stringValue()).isEqualTo("Осенняя скидка");
        assertThat(item.get("subtitle").stringValue()).isEqualTo("Только осенью");
        assertThat(item.get("ctaLabel").stringValue()).isEqualTo("Смотреть");
        assertThat(item.get("textLocale").stringValue()).isEqualTo("ru");
        assertThat(item.get("link").get("type").stringValue()).isEqualTo("CATEGORY");
        assertThat(item.get("link").get("targetId").stringValue()).isEqualTo(CATEGORY.toString());
        JsonNode media = item.get("media");
        assertThat(media.get("kind").stringValue()).isEqualTo("IMAGE");
        assertThat(media.get("alt").stringValue()).isEqualTo("Тыквенный суп");
        assertThat(media.get("widthPx").intValue()).isEqualTo(1800);
        assertThat(media.get("heightPx").intValue()).isEqualTo(600);
        assertThat(media.get("renditions"))
                .extracting(r -> r.get("widthPx").intValue())
                .containsExactly(200, 400, 800, 1600);
        assertThat(media.get("renditions").get(3).get("url").stringValue())
                .isEqualTo("/api/v1/storefront/tenants/" + TENANT + "/media/" + asset + "?variant=w1600");
        assertThat(media.get("renditions").get(0).get("url").stringValue()).endsWith("?variant=thumb");

        String raw = result.getResponse().getContentAsString(UTF_8);
        assertThat(raw)
                .doesNotContain("Staff-only name")
                .doesNotContain("internalName")
                .doesNotContain("createdBy");
        assertThat(item.has("document")).isFalse();
        assertThat(raw)
                .as("no timestamp: the digest moves only when content does")
                .doesNotContain("At\"");
    }

    @Test
    @DisplayName(
            "only a path listed in SecurityConfiguration is anonymous: a sibling under the same brand is still 401, and so is the staff surface")
    void anUnlistedStorefrontPathStaysClosed() throws Exception {
        String sibling = "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/content/drafts";
        assertThat(status(mvc.perform(get(sibling)).andReturn())).isEqualTo(401);
        assertThat(status(mvc.perform(get(CONTENT + "/items")).andReturn())).isEqualTo(401);
        assertThat(status(mvc.perform(get(HOME + "?channel=STOREFRONT")).andReturn()))
                .isEqualTo(200);
    }

    @Test
    @DisplayName(
            "an unknown brand, a brand of another tenant and an unknown channel are one 404; a channel with no content surface is an empty document, not an error")
    void unknownThingsAreOne404() throws Exception {
        assertThat(status(homeResult("NO_SUCH_CHANNEL", "ru"))).isEqualTo(404);
        String unknownBrand = "/api/v1/storefront/tenants/" + TENANT + "/brands/" + UUID.randomUUID()
                + "/content/home?channel=STOREFRONT";
        assertThat(status(mvc.perform(get(unknownBrand)).andReturn())).isEqualTo(404);
        String crossTenant = "/api/v1/storefront/tenants/" + TENANT + "/brands/" + FOREIGN_BRAND
                + "/content/home?channel=STOREFRONT";
        MvcResult cross = mvc.perform(get(crossTenant)).andReturn();
        assertThat(status(cross)).isEqualTo(404);
        MvcResult unknown = mvc.perform(get(unknownBrand)).andReturn();
        assertThat(body(cross).get("code").stringValue()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(body(cross).get("detail").stringValue())
                .as("the same answer, so the endpoint cannot be used to probe what exists")
                .isEqualTo(body(unknown).get("detail").stringValue());
        assertThat(body(homeResult("NO_SUCH_CHANNEL", "ru")).get("detail").stringValue())
                .isEqualTo(body(unknown).get("detail").stringValue());

        liveBanner("Visible", "", "");
        JsonNode qr = home(ContentFixture.QR, "ru");
        assertThat(qr.get("carousel"))
                .as("a QR-table channel shows the menu only")
                .isEmpty();
        assertThat(qr.get("groups")).isEmpty();
        assertThat(status(mvc.perform(get(HOME)).andReturn()))
                .as("channel is required")
                .isEqualTo(400);
    }

    // ============================================================ the schedule

    @Test
    @DisplayName(
            "a window starts inclusively and ends exclusively, decided against the injected clock when the response is built")
    void windowEdges() throws Exception {
        Instant start = clock.instant().plus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant end = start.plus(Duration.ofHours(1));
        liveBanner("Scheduled", "", ",\"startsAt\":\"" + start + "\",\"endsAt\":\"" + end + "\"");

        clock.set(start.minusSeconds(1));
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru")))
                .as("one second before it starts")
                .isEmpty();
        clock.set(start);
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru")))
                .as("at the instant it starts")
                .containsExactly("Scheduled");
        clock.set(end.minusSeconds(1));
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru")))
                .as("one second before it ends")
                .containsExactly("Scheduled");
        clock.set(end);
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru")))
                .as("at the instant it ends")
                .isEmpty();
        clock.set(end.plus(Duration.ofDays(30)));
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru")))
                .as("long after: nothing swept it, it is simply not live")
                .isEmpty();

        // The console computes the same thing, and never stores it.
        clock.set(start.plusSeconds(5));
        JsonNode row = body(mvc.perform(get(CONTENT + "/items").with(tokenFor(OWNER)))
                        .andReturn())
                .get("items")
                .get(0);
        assertThat(row.get("visibility").stringValue()).isEqualTo("LIVE");
        clock.set(end.plusSeconds(5));
        row = body(mvc.perform(get(CONTENT + "/items").with(tokenFor(OWNER))).andReturn())
                .get("items")
                .get(0);
        assertThat(row.get("visibility").stringValue()).isEqualTo("EXPIRED");
        assertThat(row.get("state").stringValue())
                .as("expired is not a stored state")
                .isEqualTo("PUBLISHED");
        clock.set(start.minusSeconds(30));
        row = body(mvc.perform(get(CONTENT + "/items").with(tokenFor(OWNER))).andReturn())
                .get("items")
                .get(0);
        assertThat(row.get("visibility").stringValue()).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName(
            "a withdrawal reaches customers on the next read; the minute is the cache window the response states, not a delay in the server")
    void withdrawal() throws Exception {
        JsonNode live = liveBanner("Going", "", "");
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru"))).containsExactly("Going");

        withdraw(live);

        assertThat(titles(home(ContentFixture.STOREFRONT, "ru"))).isEmpty();
        assertThat(homeResult(ContentFixture.STOREFRONT, "ru").getResponse().getHeader("Cache-Control"))
                .contains("max-age=60");
    }

    // =============================================================== channels

    @Test
    @DisplayName("an item names the channels it appears on, and an empty set means every content channel")
    void channelScope() throws Exception {
        liveBanner("Everywhere", "", "");
        liveBanner("Web only", "", ",\"channelCodes\":[\"STOREFRONT\"]");
        liveBanner("iOS only", "", ",\"channelCodes\":[\"IOS_APP\"]");

        assertThat(titles(home(ContentFixture.STOREFRONT, "ru"))).containsExactlyInAnyOrder("Everywhere", "Web only");
        assertThat(titles(home(ContentFixture.IOS, "ru"))).containsExactlyInAnyOrder("Everywhere", "iOS only");
    }

    // ============================================================== promotions

    @Test
    @DisplayName(
            "a promo card bound to a promotion is visible only while that promotion is live, and the window of the promotion decides it without anyone consuming an event")
    void aCardOutlivesNeitherItsPromotionNorItsWindow() throws Exception {
        UUID promotion = fixture.promotion(
                TENANT,
                BRAND,
                "CARD-PROMO",
                "ACTIVE",
                clock.instant().minus(Duration.ofDays(1)),
                clock.instant().plus(Duration.ofHours(2)));
        UUID asset = fixture.goodCard(TENANT, BRAND);
        publish(create("""
                {"kind":"PROMO_CARD","internalName":"Card","promotionId":"%s",
                 "document":{"texts":{"ru":{"title":"Акция"}},"media":{"primaryAssetId":"%s"}}}""".formatted(promotion, asset)));

        assertThat(titles(home(ContentFixture.STOREFRONT, "ru"))).containsExactly("Акция");
        assertThat(home(ContentFixture.STOREFRONT, "ru")
                        .get("carousel")
                        .get(0)
                        .get("kind")
                        .stringValue())
                .isEqualTo("PROMO_CARD");

        fixture.promotionStatus(promotion, "SUSPENDED");
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru")))
                .as("the promotion is suspended")
                .isEmpty();

        fixture.promotionStatus(promotion, "ACTIVE");
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru")))
                .as("and back again")
                .containsExactly("Акция");

        clock.set(clock.instant().plus(Duration.ofHours(2)));
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru")))
                .as("the promotion's own window has ended")
                .isEmpty();
    }

    // ================================================================== links

    @Test
    @DisplayName("a link to something the menu no longer holds is served as NONE and the item stays live")
    void anUnresolvedLinkDegradesAndTheItemStays() throws Exception {
        UUID category = UUID.randomUUID();
        fixture.publicationItem(TENANT, BRAND, publication, "CATEGORY", category);
        liveBanner("Linked", ",\"link\":{\"type\":\"CATEGORY\",\"targetId\":\"" + category + "\"}", "");
        liveBanner("Product", ",\"link\":{\"type\":\"PRODUCT\",\"targetId\":\"" + PRODUCT_A + "\"}", "");
        liveBanner("Cart", ",\"link\":{\"type\":\"CART\"}", "");

        JsonNode before = home(ContentFixture.STOREFRONT, "ru");
        assertThat(linkOf(before, "Linked")).isEqualTo("CATEGORY");
        assertThat(linkOf(before, "Product")).isEqualTo("PRODUCT");
        assertThat(linkOf(before, "Cart")).isEqualTo("CART");

        fixture.dropFromPublication(publication, category);
        JsonNode after = home(ContentFixture.STOREFRONT, "ru");
        assertThat(titles(after)).as("the item stays live").containsExactlyInAnyOrder("Linked", "Product", "Cart");
        assertThat(linkOf(after, "Linked")).isEqualTo("NONE");
        assertThat(linkNode(after, "Linked").has("targetId")).isFalse();

        // The same product on the iOS channel's publication resolves; on a channel whose publication lacks it, it does
        // not.
        assertThat(linkOf(home(ContentFixture.IOS, "ru"), "Product")).isEqualTo("PRODUCT");
    }

    @Test
    @DisplayName(
            "an external link is re-checked against the allowlist on every read: a host removed, or a channel hostname released, is served as NONE from the next read")
    void anExternalLinkIsCheckedAgainWhenItIsRead() throws Exception {
        MvcResult allowed = send(post(CONTENT + "/allowed-hosts"), "{\"host\":\"promo.example.uz\"}", null);
        assertThat(status(allowed)).isEqualTo(201);
        UUID channel = jdbc.sql("SELECT id FROM tenant.sales_channels WHERE tenant_id = :t AND code = 'STOREFRONT'")
                .param("t", TENANT)
                .query(UUID.class)
                .single();
        fixture.channelHostname(TENANT, channel, "shop.example.uz", true);
        liveBanner("Promo site", ",\"link\":{\"type\":\"EXTERNAL\",\"url\":\"https://promo.example.uz/winter\"}", "");
        liveBanner("Own shop", ",\"link\":{\"type\":\"EXTERNAL\",\"url\":\"https://shop.example.uz/menu\"}", "");

        JsonNode before = home(ContentFixture.STOREFRONT, "ru");
        assertThat(linkOf(before, "Promo site")).isEqualTo("EXTERNAL");
        assertThat(linkNode(before, "Promo site").get("url").stringValue())
                .isEqualTo("https://promo.example.uz/winter");
        assertThat(linkOf(before, "Own shop")).isEqualTo("EXTERNAL");

        MvcResult removed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                CONTENT + "/allowed-hosts/promo.example.uz")
                        .with(tokenFor(OWNER))
                        .header("Idempotency-Key", "remove-" + keys.incrementAndGet()))
                .andReturn();
        assertThat(status(removed)).isEqualTo(204);
        fixture.channelHostname(TENANT, channel, "shop.example.uz", false);

        JsonNode after = home(ContentFixture.STOREFRONT, "ru");
        assertThat(titles(after)).containsExactlyInAnyOrder("Promo site", "Own shop");
        assertThat(linkOf(after, "Promo site"))
                .as("the host left the allowlist")
                .isEqualTo("NONE");
        assertThat(linkOf(after, "Own shop")).as("the hostname was released").isEqualTo("NONE");
        assertThat(after.toString()).doesNotContain("promo.example.uz").doesNotContain("shop.example.uz");
    }

    private static String linkOf(JsonNode home, String title) {
        return linkNode(home, title).get("type").stringValue();
    }

    private static JsonNode linkNode(JsonNode home, String title) {
        for (JsonNode item : home.get("carousel")) {
            if (item.get("title").stringValue().equals(title)) {
                return item.get("link");
            }
        }
        throw new AssertionError("no carousel item titled " + title);
    }

    // ============================================================== languages

    @Test
    @DisplayName(
            "text is served in the visitor's language, else the brand's default, else the registry fallback, and the response says which; a bare uz is read as uz-Latn; never an error")
    void languageFallback() throws Exception {
        UUID asset = fixture.goodBanner(TENANT, BRAND);
        publish(create("""
                {"kind":"BANNER","internalName":"Languages",
                 "document":{"texts":{"ru":{"title":"По-русски"},"uz-Latn":{"title":"Oʻzbekcha"}},"media":{"primaryAssetId":"%s"}}}""".formatted(asset)));

        JsonNode ru = home(ContentFixture.STOREFRONT, "ru");
        assertThat(ru.get("locale").stringValue()).isEqualTo("ru");
        assertThat(ru.get("carousel").get(0).get("textLocale").stringValue()).isEqualTo("ru");

        for (String uz : new String[] {"uz", "uz-Latn", "UZ-latn"}) {
            JsonNode home = home(ContentFixture.STOREFRONT, uz);
            assertThat(home.get("locale").stringValue()).as(uz).isEqualTo("uz-Latn");
            assertThat(titles(home)).as(uz).containsExactly("Oʻzbekcha");
        }

        // English is a language the platform speaks and this item has no text in: the brand's default.
        JsonNode en = home(ContentFixture.STOREFRONT, "en");
        assertThat(en.get("locale").stringValue()).isEqualTo("en");
        assertThat(titles(en)).containsExactly("По-русски");
        assertThat(en.get("carousel").get(0).get("textLocale").stringValue()).isEqualTo("ru");

        // A language nobody speaks, and none at all: the brand default, not an error.
        assertThat(titles(home(ContentFixture.STOREFRONT, "fr"))).containsExactly("По-русски");
        assertThat(titles(home(ContentFixture.STOREFRONT, null))).containsExactly("По-русски");

        // The brand later makes Uzbek its default; the item has Uzbek text, so it is served in it.
        fixture.brandLocales(TENANT, BRAND, "uz-Latn", "ru");
        assertThat(titles(home(ContentFixture.STOREFRONT, "fr"))).containsExactly("Oʻzbekcha");
        // And when the default language has no text, the registry fallback (ru) speaks.
        jdbc.sql("DELETE FROM tenant.brand_locales WHERE brand_id = :b")
                .param("b", BRAND)
                .update();
        fixture.brandLocales(TENANT, BRAND, "en", "ru");
        assertThat(titles(home(ContentFixture.STOREFRONT, "en"))).containsExactly("По-русски");
        assertThat(home(ContentFixture.STOREFRONT, "en")
                        .get("carousel")
                        .get(0)
                        .get("textLocale")
                        .stringValue())
                .isEqualTo("ru");
    }

    // ================================================================== media

    @Test
    @DisplayName(
            "only the renditions that exist are listed, smallest first, and never the original; an image with no rendition yet, and one that is not public, are not shown")
    void renditionsAndServableImages() throws Exception {
        UUID noHero = fixture.image(
                TENANT, BRAND, 1800, 600, "PUBLIC", "AVAILABLE", Map.of("THUMBNAIL", 200, "CARD", 400, "DETAIL", 800));
        publish(create(banner(noHero, "No HERO", "")));
        UUID unrendered = fixture.image(TENANT, BRAND, 1800, 600, "PUBLIC", "AVAILABLE", Map.of());
        JsonNode pending = publish(create(banner(unrendered, "Not rendered yet", "")));

        JsonNode home = home(ContentFixture.STOREFRONT, "ru");

        assertThat(titles(home))
                .as("an image with nothing rendered cannot be shown yet")
                .containsExactly("No HERO");
        JsonNode renditions = home.get("carousel").get(0).get("media").get("renditions");
        assertThat(renditions).extracting(r -> r.get("widthPx").intValue()).containsExactly(200, 400, 800);
        assertThat(renditions.toString()).doesNotContain("w1600");

        // The derivative job catches up: the same published item now shows, with no republication.
        fixture.rendition(TENANT, unrendered, "THUMBNAIL", 200, 67);
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru")))
                .containsExactlyInAnyOrder("No HERO", "Not rendered yet");

        // An asset that stopped being public stops being shown (the redirect would answer 404 for it).
        jdbc.sql("UPDATE media.assets SET visibility = 'PRIVATE' WHERE asset_id = :id")
                .param("id", unrendered)
                .update();
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru"))).containsExactly("No HERO");
        assertThat(pending.get("state").stringValue()).isEqualTo("PUBLISHED");
    }

    // ================================================================== groups

    @Test
    @DisplayName(
            "a hand-picked group silently skips a dish the menu no longer holds; a by-category group goes with its category; a group left with nothing is not shown")
    void productGroups() throws Exception {
        publish(create("""
                {"kind":"PRODUCT_GROUP","internalName":"Popular",
                 "document":{"texts":{"ru":{"title":"Популярное"}},"mode":"MANUAL",
                             "members":[{"id":"%s"},{"id":"%s"},{"id":"%s"}]}}""".formatted(PRODUCT_A, PRODUCT_GONE, PRODUCT_B)));
        publish(create("""
                {"kind":"PRODUCT_GROUP","internalName":"Pizza",
                 "document":{"texts":{"ru":{"title":"Пицца"}},"mode":"BY_CATEGORY","categoryId":"%s","order":"NEWEST","limit":5}}""".formatted(CATEGORY)));

        JsonNode home = home(ContentFixture.STOREFRONT, "ru");
        assertThat(home.get("groups")).hasSize(2);
        JsonNode popular = home.get("groups").get(0);
        assertThat(popular.get("title").stringValue()).isEqualTo("Популярное");
        assertThat(popular.get("productIds"))
                .extracting(JsonNode::stringValue)
                .containsExactly(PRODUCT_A.toString(), PRODUCT_GONE.toString(), PRODUCT_B.toString());
        JsonNode pizza = home.get("groups").get(1);
        assertThat(pizza.get("categoryId").stringValue()).isEqualTo(CATEGORY.toString());
        assertThat(pizza.get("order").stringValue()).isEqualTo("NEWEST");
        assertThat(pizza.get("limit").intValue()).isEqualTo(5);
        assertThat(pizza.get("productIds")).isEmpty();

        fixture.dropFromPublication(publication, PRODUCT_GONE);
        fixture.dropFromPublication(publication, CATEGORY);
        JsonNode after = home(ContentFixture.STOREFRONT, "ru");
        assertThat(after.get("groups")).hasSize(1);
        assertThat(after.get("groups").get(0).get("productIds"))
                .extracting(JsonNode::stringValue)
                .containsExactly(PRODUCT_A.toString(), PRODUCT_B.toString());

        fixture.dropFromPublication(publication, PRODUCT_A);
        fixture.dropFromPublication(publication, PRODUCT_B);
        assertThat(home(ContentFixture.STOREFRONT, "ru").get("groups"))
                .as("nothing left to show")
                .isEmpty();
    }

    // ================================================================= ordering

    @Test
    @DisplayName(
            "order is placement, sort key, creation time, id: a fixture gives every item an explicit, distinct key and timestamp so it never depends on insertion time")
    void orderingIsDeterministic() throws Exception {
        JsonNode a = liveBanner("A", "", "");
        JsonNode b = liveBanner("B", "", "");
        JsonNode c = liveBanner("C", "", "");
        String idA = a.get("id").stringValue();
        String idB = b.get("id").stringValue();
        String idC = c.get("id").stringValue();

        // Same sort key everywhere; creation time alone decides, set explicitly (repeated now() fixtures tie
        // locally and reorder under CI load).
        jdbc.sql("UPDATE content.items SET sort_key = 10").update();
        setCreated(idB, "2026-01-01T00:00:00Z");
        setCreated(idC, "2026-01-02T00:00:00Z");
        setCreated(idA, "2026-01-03T00:00:00Z");
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru"))).containsExactly("B", "C", "A");

        setCreated(idB, "2026-01-05T00:00:00Z");
        setCreated(idC, "2026-01-04T00:00:00Z");
        setCreated(idA, "2026-01-03T00:00:00Z");
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru"))).containsExactly("A", "C", "B");

        // A smaller sort key beats an earlier creation time.
        jdbc.sql("UPDATE content.items SET sort_key = 5 WHERE id = :id")
                .param("id", UUID.fromString(idB))
                .update();
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru"))).containsExactly("B", "A", "C");

        // Identical sort key and creation time: the id breaks the tie, and the same way every time.
        jdbc.sql("UPDATE content.items SET sort_key = 10, created_at = '2026-02-02T00:00:00Z'")
                .update();
        List<String> byId = new ArrayList<>(List.of(idA, idB, idC));
        java.util.Collections.sort(byId);
        List<String> expected = new ArrayList<>();
        for (String id : byId) {
            expected.add(id.equals(idA) ? "A" : id.equals(idB) ? "B" : "C");
        }
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru"))).isEqualTo(expected);
        assertThat(titles(home(ContentFixture.STOREFRONT, "ru"))).isEqualTo(expected);
    }

    private void setCreated(String id, String instant) {
        jdbc.sql("UPDATE content.items SET created_at = :at WHERE id = :id")
                .param("at", Instant.parse(instant).atOffset(ZoneOffset.UTC))
                .param("id", UUID.fromString(id))
                .update();
    }

    // =================================================================== caching

    @Test
    @DisplayName(
            "the ETag is a digest of the body: a conditional read of unchanged content is a 304, and it moves exactly when content does")
    void conditionalRead() throws Exception {
        JsonNode live = liveBanner("Cached", "", "");
        MvcResult first = homeResult(ContentFixture.STOREFRONT, "ru");
        String tag = java.util.Objects.requireNonNull(first.getResponse().getHeader("ETag"));

        MvcResult unchanged = mvc.perform(
                        get(HOME + "?channel=STOREFRONT&locale=ru").header("If-None-Match", tag))
                .andReturn();
        assertThat(status(unchanged)).isEqualTo(304);
        assertThat(unchanged.getResponse().getContentAsString(UTF_8)).isEmpty();

        // Reading it again does not move it, whenever it is read.
        clock.set(clock.instant().plusSeconds(3600));
        assertThat(homeResult(ContentFixture.STOREFRONT, "ru").getResponse().getHeader("ETag"))
                .isEqualTo(tag);

        // The language is part of the document, so it is part of the tag.
        assertThat(homeResult(ContentFixture.STOREFRONT, "uz").getResponse().getHeader("ETag"))
                .isNotEqualTo(tag);
        assertThat(homeResult(ContentFixture.STOREFRONT, "en").getResponse().getHeader("ETag"))
                .isNotEqualTo(tag);

        // A new revision moves it.
        MvcResult edited = send(
                put(CONTENT + "/items/" + live.get("id").stringValue() + "/draft"),
                """
                {"document":{"texts":{"ru":{"title":"Cached again"}},"media":{"primaryAssetId":"%s"}}}""".formatted(live.get("draft")
                        .get("document")
                        .get("media")
                        .get("primaryAssetId")
                        .stringValue()),
                etag(live));
        publish(body(edited));
        MvcResult changed = mvc.perform(
                        get(HOME + "?channel=STOREFRONT&locale=ru").header("If-None-Match", tag))
                .andReturn();
        assertThat(status(changed)).isEqualTo(200);
        assertThat(changed.getResponse().getHeader("ETag")).isNotEqualTo(tag);
        assertThat(titles(body(changed))).containsExactly("Cached again");
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

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    /** The clock the whole context runs on, which a test moves to stand at the edge of a window. */
    static final class MovableClock extends Clock {
        private volatile Instant now = Instant.now();

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
