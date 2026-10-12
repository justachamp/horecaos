package uz.horecaos.platform.content.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.commercial.application.PlanCatalogService;
import uz.horecaos.platform.commercial.application.SubscriptionService;
import uz.horecaos.platform.content.ContentFixture;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The editorial tier as an anonymous visitor reads it (ADR 0157, Decision 7; Release 5): news, gallery
 * albums, recipes and a vacancy listing -- authored and published over the staff API exactly as an
 * operator would, read over the storefront API exactly as a storefront would.
 *
 * <p>The thing under test is the pair of switches (the plan's entitlement and the brand's policy), the
 * typed-block body that is rendered with each link checked against the allowlist again, the vacancy that
 * carries one contact and nothing of a candidate, and the 404 that "off" is indistinguishable from.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContentEditorialEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-8000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b20-8000-7000-8000-0000000000c2");
    private static final UUID BRAND = UUID.fromString("018f9b20-8000-7000-8000-0000000000d1");
    private static final UUID FOREIGN_BRAND = UUID.fromString("018f9b20-8000-7000-8000-0000000000d3");
    private static final UUID LOCATION = UUID.fromString("018f9b20-8000-7000-8000-0000000000e1");

    private static final String OWNER = "editorial-owner";

    private static final String CONTENT = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/content";
    private static final String PUBLIC = "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/content";

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
    private PlanCatalogService plans;

    @Autowired
    @SuppressWarnings("NullAway")
    private SubscriptionService subscriptions;

    private ContentFixture fixture;
    private final AtomicInteger keys = new AtomicInteger();

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE commercial.subscriptions, commercial.plan_entitlements,"
                        + " commercial.plan_versions, commercial.plans CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.policies, tenant.policy_current").update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, catalog.catalogs,"
                        + " pricing.promotions, media.assets CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        fixture = new ContentFixture(jdbc);
        fixture.tenant(TENANT, "editorial-endpoint");
        fixture.tenant(OTHER_TENANT, "editorial-other");
        fixture.brand(TENANT, BRAND, "MAIN");
        fixture.brand(OTHER_TENANT, FOREIGN_BRAND, "FOREIGN");
        fixture.location(TENANT, BRAND, LOCATION, "CENTRE");
        fixture.brandLocales(TENANT, BRAND, "ru", "uz-Latn");
        fixture.channel(TENANT, ContentFixture.STOREFRONT, "WEB");
        fixture.channel(TENANT, ContentFixture.IOS, "IOS");
        fixture.channel(TENANT, ContentFixture.QR, "QR_TABLE");
        fixture.grant(TENANT, OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
    }

    // ==================================================================== helpers

    private MvcResult send(MockHttpServletRequestBuilder request, @Nullable String body, @Nullable String ifMatch)
            throws Exception {
        request = request.with(tokenFor(OWNER)).header("Idempotency-Key", "editorial-key-" + keys.incrementAndGet());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult post(String path, @Nullable String body, @Nullable String ifMatch) throws Exception {
        return send(MockMvcRequestBuilders.post(path), body, ifMatch);
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

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    /** The plan includes the editorial tier and the brand runs these kinds. */
    private void turnOn(String... kinds) throws Exception {
        fixture.entitle(plans, subscriptions, TENANT, true, false);
        StringBuilder policy = new StringBuilder("{");
        for (int i = 0; i < kinds.length; i++) {
            policy.append(i == 0 ? "" : ",").append('"').append(kinds[i]).append("\":true");
        }
        MvcResult result =
                send(put(CONTENT + "/policies/editorial"), policy.append('}').toString(), "W/\"0\"");
        assertThat(status(result))
                .as(result.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
    }

    private JsonNode create(String itemJson) throws Exception {
        MvcResult created = post(CONTENT + "/items", itemJson, null);
        assertThat(status(created))
                .as(created.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        return body(created);
    }

    private MvcResult tryPublish(JsonNode item) throws Exception {
        return post(CONTENT + "/items/" + item.get("id").stringValue() + "/publications", null, etag(item));
    }

    private JsonNode publish(JsonNode item) throws Exception {
        MvcResult published = tryPublish(item);
        assertThat(status(published))
                .as(published.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        return body(published);
    }

    private void allowHost(String host) throws Exception {
        assertThat(status(post(CONTENT + "/allowed-hosts", "{\"host\":\"" + host + "\"}", null)))
                .isEqualTo(201);
    }

    private void removeHost(String host) throws Exception {
        assertThat(status(mvc.perform(delete(CONTENT + "/allowed-hosts/" + host)
                                .with(tokenFor(OWNER))
                                .header("Idempotency-Key", "editorial-key-" + keys.incrementAndGet()))
                        .andReturn()))
                .isEqualTo(204);
    }

    private MvcResult read(String path) throws Exception {
        return mvc.perform(get(PUBLIC + path)).andReturn();
    }

    private JsonNode ok(String path) throws Exception {
        MvcResult result = read(path);
        assertThat(status(result))
                .as(path + " " + result.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        return body(result);
    }

    private static List<String> slugs(JsonNode page) {
        List<String> slugs = new ArrayList<>();
        page.get("items").forEach(item -> slugs.add(item.get("slug").stringValue()));
        return slugs;
    }

    private static String article(String kind, String slug, String title, String extraDocument, String outer) {
        return "{\"kind\":\"" + kind + "\",\"slug\":\"" + slug + "\",\"internalName\":\"" + slug
                + "\",\"document\":{\"texts\":{\"ru\":{\"title\":\"" + title + "\",\"summary\":\"Кратко: " + title
                + "\"}}" + extraDocument + "}" + outer + "}";
    }

    private static String paragraph(String text) {
        return "\"body\":{\"ru\":{\"blocks\":[{\"type\":\"PARAGRAPH\",\"inlines\":[{\"text\":\"" + text + "\"}]}]}}";
    }

    // ============================================================ the two switches

    @Test
    @DisplayName(
            "the features read says which kinds a brand runs: nothing without the plan, nothing without the policy, and only the kinds the brand turned on with both")
    void featuresFollowBothSwitches() throws Exception {
        JsonNode nothing = ok("/features");
        assertThat(nothing.get("news").booleanValue()).isFalse();
        assertThat(nothing.get("gallery").booleanValue()).isFalse();
        assertThat(nothing.get("recipes").booleanValue()).isFalse();
        assertThat(nothing.get("vacancies").booleanValue()).isFalse();

        turnOn("news", "vacancies");

        JsonNode some = ok("/features");
        assertThat(some.get("news").booleanValue()).isTrue();
        assertThat(some.get("gallery").booleanValue()).isFalse();
        assertThat(some.get("recipes").booleanValue()).isFalse();
        assertThat(some.get("vacancies").booleanValue()).isTrue();
    }

    @Test
    @DisplayName(
            "a brand's policy without the plan's entitlement is still off: the features say so and every listing is a 404, however many rows exist")
    void thePolicyAloneIsNotEnough() throws Exception {
        // The brand turns news on in its policy first -- which the staff API itself refuses without the plan, so the
        // policy is written the way a downgrade leaves it: the plan was entitled, the policy set, the plan then lapsed.
        turnOn("news");
        JsonNode live = publish(create(article("NEWS", "opening", "Открытие", "," + paragraph("Мы открылись."), "")));
        assertThat(ok("/news?channel=STOREFRONT&locale=ru").get("items")).hasSize(1);

        jdbc.sql("TRUNCATE TABLE commercial.subscriptions CASCADE").update();

        assertThat(ok("/features").get("news").booleanValue()).isFalse();
        assertThat(status(read("/news?channel=STOREFRONT&locale=ru"))).isEqualTo(404);
        assertThat(status(read("/news/opening?channel=STOREFRONT&locale=ru"))).isEqualTo(404);
        assertThat(live.get("state").stringValue())
                .as("the row stays; only the route goes")
                .isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName(
            "a kind the brand has not turned on is the 404 an unknown route gives, and off, never-existed, an unknown slug, an unknown brand, a brand of another tenant and an unknown channel are one answer")
    void offAndAbsentAreOneAnswer() throws Exception {
        turnOn("news");
        publish(create(article("NEWS", "opening", "Открытие", "," + paragraph("Мы открылись."), "")));

        MvcResult off = read("/gallery?channel=STOREFRONT");
        MvcResult unknownSlug = read("/news/no-such-article?channel=STOREFRONT");
        MvcResult unknownChannel = read("/news?channel=NO_SUCH_CHANNEL");
        MvcResult noContentSurface = read("/news?channel=" + ContentFixture.QR);
        MvcResult otherTenantsBrand = mvc.perform(get("/api/v1/storefront/tenants/" + TENANT + "/brands/"
                        + FOREIGN_BRAND + "/content/news?channel=STOREFRONT"))
                .andReturn();
        MvcResult unknownBrand = mvc.perform(get("/api/v1/storefront/tenants/" + TENANT + "/brands/" + UUID.randomUUID()
                        + "/content/news?channel=STOREFRONT"))
                .andReturn();

        for (MvcResult miss : List.of(off, unknownSlug, unknownChannel, noContentSurface, otherTenantsBrand)) {
            assertThat(status(miss)).isEqualTo(404);
            assertThat(body(miss).get("code").stringValue()).isEqualTo("RESOURCE_NOT_FOUND");
        }
        assertThat(status(unknownBrand)).isEqualTo(404);
        assertThat(body(off).get("detail").stringValue())
                .as("a listing that is off does not say it is off")
                .isEqualTo(body(unknownChannel).get("detail").stringValue());
        assertThat(status(read("/news?channel=STOREFRONT"))).isEqualTo(200);
    }

    // ================================================================= news

    @Test
    @DisplayName(
            "a news article is read as typed blocks, never markup: a heading, runs with bold and italic, a list, a quote, an image with a caption, a divider; with public cache headers and a digest ETag that answers 304")
    void aNewsArticleIsReadAsTypedBlocks() throws Exception {
        turnOn("news");
        UUID cover = fixture.goodBanner(TENANT, BRAND);
        UUID inline = fixture.goodCard(TENANT, BRAND);
        publish(create("""
                {"kind":"NEWS","slug":"opening","internalName":"Opening",
                 "startsAt":"2026-03-01T08:00:00Z",
                 "document":{"texts":{"ru":{"title":"Открытие","summary":"Мы открылись"}},
                  "coverAssetId":"%s",
                  "body":{"ru":{"blocks":[
                    {"type":"HEADING","level":2,"inlines":[{"text":"Новое место"}]},
                    {"type":"PARAGRAPH","inlines":[{"text":"Приходите "},{"text":"сегодня","bold":true},{"text":" и ","italic":true},{"text":"<script>alert(1)</script>"}]},
                    {"type":"LIST","ordered":true,"items":[[{"text":"Первое"}],[{"text":"Второе"}]]},
                    {"type":"QUOTE","inlines":[{"text":"Лучший суп"}]},
                    {"type":"IMAGE","assetId":"%s","caption":[{"text":"Зал"}]},
                    {"type":"DIVIDER"}]}}}}""".formatted(cover, inline)));

        MvcResult result = read("/news/opening?channel=STOREFRONT&locale=ru");

        assertThat(status(result)).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control"))
                .contains("public")
                .contains("max-age=60");
        String etag = java.util.Objects.requireNonNull(result.getResponse().getHeader("ETag"));
        assertThat(etag).startsWith("\"").endsWith("\"");
        JsonNode detail = body(result);
        assertThat(detail.get("slug").stringValue()).isEqualTo("opening");
        assertThat(detail.get("kind").stringValue()).isEqualTo("NEWS");
        assertThat(detail.get("title").stringValue()).isEqualTo("Открытие");
        assertThat(detail.get("textLocale").stringValue()).isEqualTo("ru");
        assertThat(detail.get("bodyLocale").stringValue()).isEqualTo("ru");
        assertThat(detail.get("cover").get("renditions"))
                .extracting(r -> r.get("widthPx").intValue())
                .containsExactly(200, 400, 800, 1600);
        JsonNode blocks = detail.get("blocks");
        assertThat(blocks)
                .extracting(b -> b.get("type").stringValue())
                .containsExactly("HEADING", "PARAGRAPH", "LIST", "QUOTE", "IMAGE", "DIVIDER");
        assertThat(blocks.get(0).get("level").intValue()).isEqualTo(2);
        JsonNode runs = blocks.get(1).get("inlines");
        assertThat(runs.get(1).get("bold").booleanValue()).isTrue();
        assertThat(runs.get(2).get("italic").booleanValue()).isTrue();
        assertThat(runs.get(3).get("text").stringValue())
                .as("markup in a run is just characters")
                .isEqualTo("<script>alert(1)</script>");
        assertThat(blocks.get(1).has("html")).isFalse();
        assertThat(blocks.get(2).get("ordered").booleanValue()).isTrue();
        assertThat(blocks.get(2).get("items")).hasSize(2);
        assertThat(blocks.get(4).get("media").get("renditions")).isNotEmpty();
        assertThat(blocks.get(4).get("caption").get(0).get("text").stringValue())
                .isEqualTo("Зал");
        String raw = result.getResponse().getContentAsString(UTF_8);
        assertThat(raw)
                .doesNotContain("internalName")
                .doesNotContain("createdBy")
                .doesNotContain("assetId");

        MvcResult revalidated = mvc.perform(get(PUBLIC + "/news/opening?channel=STOREFRONT&locale=ru")
                        .header("If-None-Match", etag))
                .andReturn();
        assertThat(status(revalidated)).isEqualTo(304);
    }

    @Test
    @DisplayName(
            "a link in a body is served only while its host is on the brand's allowlist: when the host leaves it, the words stay and the link goes")
    void aLinkWhoseHostLeftTheAllowlistIsServedAsPlainText() throws Exception {
        turnOn("news");
        allowHost("shop.example.uz");
        publish(create(article(
                "NEWS",
                "linked",
                "Со ссылкой",
                ",\"body\":{\"ru\":{\"blocks\":[{\"type\":\"PARAGRAPH\",\"inlines\":[{\"text\":\"Заказ \"},"
                        + "{\"text\":\"здесь\",\"href\":\"https://shop.example.uz/order\"},"
                        + "{\"text\":\" или \"},{\"text\":\"позвоните\",\"href\":\"tel:+998901234567\"}]}]}}",
                "")));

        JsonNode before = ok("/news/linked?channel=STOREFRONT&locale=ru")
                .get("blocks")
                .get(0)
                .get("inlines");
        assertThat(before.get(1).get("href").stringValue()).isEqualTo("https://shop.example.uz/order");
        assertThat(before.get(3).get("href").stringValue()).isEqualTo("tel:+998901234567");

        removeHost("shop.example.uz");

        JsonNode after = ok("/news/linked?channel=STOREFRONT&locale=ru")
                .get("blocks")
                .get(0)
                .get("inlines");
        assertThat(after.get(1).get("text").stringValue()).isEqualTo("здесь");
        assertThat(after.get(1).has("href"))
                .as("the host left the allowlist; the read does not link to it")
                .isFalse();
        assertThat(after.get(3).get("href").stringValue())
                .as("tel: needs no host")
                .isEqualTo("tel:+998901234567");
    }

    @Test
    @DisplayName(
            "a listing is newest first and paged by an opaque cursor that repeats and skips nothing; a window that has not opened and a channel the item is not for are not listed")
    void aListingIsPagedNewestFirst() throws Exception {
        turnOn("news");
        for (String[] article : new String[][] {
            {"first", "2026-01-01T08:00:00Z"},
            {"second", "2026-02-01T08:00:00Z"},
            {"third", "2026-03-01T08:00:00Z"}
        }) {
            publish(create(article(
                    "NEWS", article[0], article[0], "," + paragraph("x"), ",\"startsAt\":\"" + article[1] + "\"")));
        }
        publish(create(
                article("NEWS", "not-yet", "not-yet", "," + paragraph("x"), ",\"startsAt\":\"2099-01-01T00:00:00Z\"")));
        publish(create(article(
                "NEWS",
                "app-only",
                "app-only",
                "," + paragraph("x"),
                ",\"startsAt\":\"2026-04-01T08:00:00Z\"," + "\"channelCodes\":[\"" + ContentFixture.IOS + "\"]")));

        JsonNode page1 = ok("/news?channel=STOREFRONT&locale=ru&limit=2");
        assertThat(slugs(page1)).containsExactly("third", "second");
        String cursor = page1.get("nextCursor").stringValue();
        JsonNode page2 = ok("/news?channel=STOREFRONT&locale=ru&limit=2&cursor=" + cursor);
        assertThat(slugs(page2)).containsExactly("first");
        assertThat(page2.get("nextCursor").isNull() || !page2.has("nextCursor")).isTrue();

        assertThat(slugs(ok("/news?channel=IOS_APP&locale=ru")))
                .containsExactly("app-only", "third", "second", "first");
        assertThat(status(read("/news/not-yet?channel=STOREFRONT"))).isEqualTo(404);
        assertThat(status(read("/news/app-only?channel=STOREFRONT"))).isEqualTo(404);
        assertThat(status(read("/news?channel=STOREFRONT&cursor=not-a-cursor"))).isIn(200, 400);
    }

    @Test
    @DisplayName(
            "text is served in the visitor's language, else the brand's default, and says which: a Uzbek visitor gets Uzbek where it exists and Russian where it does not")
    void textFollowsTheLanguageChain() throws Exception {
        turnOn("news");
        publish(create("""
                {"kind":"NEWS","slug":"both","internalName":"both",
                 "document":{"texts":{"ru":{"title":"По-русски"},"uz-Latn":{"title":"Oʻzbekcha"}}}}"""));
        publish(create("""
                {"kind":"NEWS","slug":"only-ru","internalName":"only-ru",
                 "document":{"texts":{"ru":{"title":"Только русский"}}}}"""));

        JsonNode uzbek = ok("/news?channel=STOREFRONT&locale=uz");
        JsonNode both = uzbek.get("items").get(0).get("slug").stringValue().equals("both")
                ? uzbek.get("items").get(0)
                : uzbek.get("items").get(1);
        JsonNode onlyRu = uzbek.get("items").get(0).get("slug").stringValue().equals("only-ru")
                ? uzbek.get("items").get(0)
                : uzbek.get("items").get(1);
        assertThat(both.get("title").stringValue()).isEqualTo("Oʻzbekcha");
        assertThat(both.get("textLocale").stringValue()).isEqualTo("uz-Latn");
        assertThat(onlyRu.get("title").stringValue()).isEqualTo("Только русский");
        assertThat(onlyRu.get("textLocale").stringValue()).isEqualTo("ru");
    }

    // ================================================================= gallery

    @Test
    @DisplayName(
            "a gallery album lists its images in order with captions, and an image that cannot be served is left out rather than shown broken")
    void aGalleryAlbumListsItsImages() throws Exception {
        turnOn("gallery");
        UUID cover = fixture.goodBanner(TENANT, BRAND);
        UUID one = fixture.goodCard(TENANT, BRAND);
        UUID two = fixture.goodCard(TENANT, BRAND);
        publish(create("""
                {"kind":"GALLERY_ALBUM","slug":"hall","internalName":"Hall",
                 "document":{"texts":{"ru":{"title":"Зал"}},"coverAssetId":"%s",
                  "albumImages":[{"assetId":"%s","captions":{"ru":"Вечером"}},{"assetId":"%s"}]}}""".formatted(cover, one, two)));
        // The second image is withdrawn from service after publication: it is private now.
        jdbc.sql("UPDATE media.assets SET visibility = 'PRIVATE' WHERE asset_id = :id")
                .param("id", two)
                .update();

        JsonNode album = ok("/gallery/hall?channel=STOREFRONT&locale=ru");

        assertThat(album.get("kind").stringValue()).isEqualTo("GALLERY_ALBUM");
        assertThat(album.get("images")).hasSize(1);
        assertThat(album.get("images").get(0).get("caption").stringValue()).isEqualTo("Вечером");
        assertThat(album.get("images").get(0).get("media").get("renditions")).isNotEmpty();
        assertThat(ok("/gallery?channel=STOREFRONT&locale=ru")
                        .get("items")
                        .get(0)
                        .get("cover"))
                .isNotNull();
    }

    // ================================================================= recipes

    @Test
    @DisplayName("a recipe is read like an article, and only while recipes are on")
    void aRecipeIsReadLikeAnArticle() throws Exception {
        turnOn("recipes");
        publish(create(article("RECIPE", "plov", "Плов", "," + paragraph("Рис и морковь."), "")));

        JsonNode recipe = ok("/recipes/plov?channel=STOREFRONT&locale=ru");

        assertThat(recipe.get("kind").stringValue()).isEqualTo("RECIPE");
        assertThat(recipe.get("blocks").get(0).get("inlines").get(0).get("text").stringValue())
                .isEqualTo("Рис и морковь.");
        assertThat(status(read("/news/plov?channel=STOREFRONT")))
                .as("a recipe is not news")
                .isEqualTo(404);
    }

    // ================================================================ vacancies

    @Test
    @DisplayName(
            "a vacancy carries its branch and one contact and nothing of a candidate; a web contact needs its host on the allowlist, and is dropped from the read when the host leaves it")
    void aVacancyCarriesOneContactAndNothingOfACandidate() throws Exception {
        turnOn("vacancies");
        String vacancy = """
                {"kind":"VACANCY","slug":"cook","internalName":"Cook","locationId":"%s",
                 "document":{"texts":{"ru":{"title":"Повар"}},
                  %s,
                  "vacancy":{"employmentType":"FULL_TIME","apply":{"type":"TELEGRAM","value":"https://t.me/horeca_hr"}}}}""".formatted(LOCATION, paragraph("Ищем повара."));

        JsonNode draft = create(vacancy);
        MvcResult refused = tryPublish(draft);
        assertThat(status(refused)).isEqualTo(422);
        assertThat(body(refused).toString()).contains("CONTENT_LINK_HOST_NOT_ALLOWED");

        allowHost("t.me");
        publish(draft);

        JsonNode listed =
                ok("/careers?channel=STOREFRONT&locale=ru").get("items").get(0);
        assertThat(listed.get("title").stringValue()).isEqualTo("Повар");
        assertThat(listed.get("employmentType").stringValue()).isEqualTo("FULL_TIME");
        JsonNode detail = ok("/careers/cook?channel=STOREFRONT&locale=ru");
        assertThat(detail.get("vacancy").get("employmentType").stringValue()).isEqualTo("FULL_TIME");
        assertThat(detail.get("vacancy").get("apply").get("type").stringValue()).isEqualTo("TELEGRAM");
        assertThat(detail.get("vacancy").get("apply").get("value").stringValue())
                .isEqualTo("https://t.me/horeca_hr");
        assertThat(detail.get("vacancy").get("branch").get("locationId").stringValue())
                .isEqualTo(LOCATION.toString());
        assertThat(detail.get("blocks")).isNotEmpty();

        removeHost("t.me");
        assertThat(ok("/careers/cook?channel=STOREFRONT&locale=ru")
                        .get("vacancy")
                        .has("apply"))
                .as("the contact's host left the allowlist")
                .isFalse();

        // There is no way to apply through the platform: no application route exists to POST to.
        MvcResult application = mvc.perform(MockMvcRequestBuilders.post(PUBLIC + "/careers/cook/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"phone\":\"+998900000000\"}"))
                .andReturn();
        assertThat(status(application)).isGreaterThanOrEqualTo(400);
    }

    @Test
    @DisplayName("a phone contact needs no allowlist and is served as written")
    void aPhoneContactNeedsNoHost() throws Exception {
        turnOn("vacancies");
        publish(create("""
                {"kind":"VACANCY","slug":"waiter","internalName":"Waiter",
                 "document":{"texts":{"ru":{"title":"Официант"}},
                  "vacancy":{"employmentType":"PART_TIME","apply":{"type":"PHONE","value":"+998 90 123 45 67"}}}}"""));

        JsonNode detail = ok("/careers/waiter?channel=STOREFRONT&locale=ru");

        assertThat(detail.get("vacancy").get("apply").get("type").stringValue()).isEqualTo("PHONE");
        assertThat(detail.get("vacancy").get("apply").get("value").stringValue())
                .isEqualTo("+998 90 123 45 67");
        assertThat(detail.get("vacancy").has("branch")).isFalse();
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
