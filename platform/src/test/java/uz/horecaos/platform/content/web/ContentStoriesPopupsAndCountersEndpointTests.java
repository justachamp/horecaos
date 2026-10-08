package uz.horecaos.platform.content.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.content.ContentFixture;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Release 2 (ADR 0157): stories, entry pop-ups and the promo grid, and the anonymous counters the
 * storefront reports about them -- over real HTTP, on a clock the test moves across the tenant's
 * midnight.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContentStoriesPopupsAndCountersEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-7000-7000-8000-0000000000c1");
    private static final UUID BRAND = UUID.fromString("018f9b20-7000-7000-8000-0000000000d1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-7000-7000-8000-0000000000d2");
    private static final UUID LOCATION = UUID.fromString("018f9b20-7000-7000-8000-0000000000e1");
    private static final String OWNER = "r2-owner";
    private static final String LOCATION_MANAGER = "r2-location-manager";

    private static final String CONTENT = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/content";
    private static final String BASE = "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/content";
    private static final String HOME = BASE + "/home";
    private static final String EVENTS = BASE + "/events";

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
    private final AtomicInteger keys = new AtomicInteger();

    @BeforeEach
    void reset() {
        clock.set(Instant.now());
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, catalog.catalogs,"
                        + " pricing.promotions, media.assets CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        fixture = new ContentFixture(jdbc);
        fixture.tenant(TENANT, "content-release-two");
        fixture.brand(TENANT, BRAND, "MAIN");
        fixture.brand(TENANT, OTHER_BRAND, "OTHER");
        fixture.location(TENANT, BRAND, LOCATION, "CENTRE");
        fixture.channel(TENANT, ContentFixture.STOREFRONT, "WEB");
        fixture.channel(TENANT, ContentFixture.QR, "QR_TABLE");
        fixture.grant(TENANT, OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        fixture.grant(TENANT, LOCATION_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION);
    }

    // ==================================================================== helpers

    private MvcResult send(
            MockHttpServletRequestBuilder request, String subject, @Nullable String body, @Nullable String ifMatch)
            throws Exception {
        request = request.with(tokenFor(subject)).header("Idempotency-Key", "r2-key-" + keys.incrementAndGet());
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

    private MvcResult create(String itemJson) throws Exception {
        return send(MockMvcRequestBuilders.post(CONTENT + "/items"), OWNER, itemJson, null);
    }

    private JsonNode created(String itemJson) throws Exception {
        MvcResult created = create(itemJson);
        assertThat(status(created))
                .as(created.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        return body(created);
    }

    private JsonNode publish(JsonNode item) throws Exception {
        MvcResult published = send(
                MockMvcRequestBuilders.post(CONTENT + "/items/" + item.get("id").stringValue() + "/publications"),
                OWNER,
                null,
                etag(item));
        assertThat(status(published))
                .as(published.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        return body(published);
    }

    private JsonNode home(String channel) throws Exception {
        MvcResult result =
                mvc.perform(get(HOME + "?channel=" + channel + "&locale=ru")).andReturn();
        assertThat(status(result)).isEqualTo(200);
        return body(result);
    }

    private UUID slideImage() {
        return fixture.image(
                TENANT, BRAND, 1080, 1920, "PUBLIC", "AVAILABLE", Map.of("THUMBNAIL", 200, "CARD", 400, "DETAIL", 800));
    }

    private UUID avatar() {
        return fixture.image(TENANT, BRAND, 400, 400, "PUBLIC", "AVAILABLE", Map.of("THUMBNAIL", 200, "CARD", 400));
    }

    private String story(UUID avatar, UUID first, UUID second, String title) {
        return """
                {"kind":"STORY_GROUP","internalName":"%s","document":{
                  "texts":{"ru":{"title":"%s"}},"avatarAssetId":"%s",
                  "slides":[
                    {"media":{"assetId":"%s"},"durationSeconds":7,"texts":{"ru":{"caption":"Новинка осени"}}},
                    {"media":{"assetId":"%s"},"link":{"type":"CART"}}]}}""".formatted(title, title, avatar, first, second);
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    // ================================================================== stories

    @Test
    @DisplayName(
            "a story group is authored, published and read: an avatar, up to ten slides of 3 to 15 seconds, a caption in the visitor's language and a link per slide")
    void aStoryGroup() throws Exception {
        JsonNode draft = created(story(avatar(), slideImage(), slideImage(), "Осень"));
        assertThat(draft.get("placement").stringValue()).isEqualTo("STORY_RAIL");
        assertThat(draft.get("problems")).isEmpty();
        assertThat(draft.get("draft")
                        .get("document")
                        .get("slides")
                        .get(1)
                        .get("position")
                        .intValue())
                .isEqualTo(2);
        publish(draft);

        JsonNode home = home(ContentFixture.STOREFRONT);

        assertThat(home.get("carousel")).isEmpty();
        JsonNode stories = home.get("stories");
        assertThat(stories).hasSize(1);
        JsonNode group = stories.get(0);
        assertThat(group.get("title").stringValue()).isEqualTo("Осень");
        assertThat(group.get("revisionNo").intValue()).isEqualTo(1);
        assertThat(group.get("avatar").get("renditions"))
                .extracting(r -> r.get("widthPx").intValue())
                .containsExactly(200, 400);
        JsonNode slides = group.get("slides");
        assertThat(slides).hasSize(2);
        assertThat(slides.get(0).get("durationSeconds").intValue()).isEqualTo(7);
        assertThat(slides.get(0).get("caption").stringValue()).isEqualTo("Новинка осени");
        assertThat(slides.get(0).get("media").get("renditions")).hasSize(3);
        assertThat(slides.get(0).get("link").get("type").stringValue()).isEqualTo("NONE");
        assertThat(slides.get(1).has("caption")).isFalse();
        assertThat(slides.get(1).get("link").get("type").stringValue()).isEqualTo("CART");
    }

    @Test
    @DisplayName(
            "a slide narrower than 720 px and an avatar that is not square are refused with CONTENT_MEDIA_ASPECT_MISMATCH")
    void storyMediaGates() throws Exception {
        UUID narrow = fixture.image(TENANT, BRAND, 600, 1067, "PUBLIC", "AVAILABLE", Map.of("DETAIL", 600));
        JsonNode narrowSlide = created(story(avatar(), narrow, slideImage(), "Узкий"));
        assertThat(narrowSlide.get("problems")).anySatisfy(problem -> {
            assertThat(problem.get("code").stringValue()).isEqualTo("CONTENT_MEDIA_ASPECT_MISMATCH");
            assertThat(problem.get("field").stringValue()).isEqualTo("slides[0].media.assetId");
        });
        MvcResult refused = send(
                MockMvcRequestBuilders.post(
                        CONTENT + "/items/" + narrowSlide.get("id").stringValue() + "/publications"),
                OWNER,
                null,
                etag(narrowSlide));
        assertThat(status(refused)).isEqualTo(422);

        UUID wide = fixture.image(TENANT, BRAND, 500, 400, "PUBLIC", "AVAILABLE", Map.of("DETAIL", 400));
        JsonNode notSquare = created(story(wide, slideImage(), slideImage(), "Не квадрат"));
        assertThat(notSquare.get("problems")).anySatisfy(problem -> {
            assertThat(problem.get("code").stringValue()).isEqualTo("CONTENT_MEDIA_ASPECT_MISMATCH");
            assertThat(problem.get("field").stringValue()).isEqualTo("avatarAssetId");
        });
    }

    @Test
    @DisplayName("a story with an unresolvable slide drops that slide, and a story with none left is not shown")
    void aStoryNeedsAtLeastOneServableSlide() throws Exception {
        UUID unrendered = fixture.image(TENANT, BRAND, 1080, 1920, "PUBLIC", "AVAILABLE", Map.of());
        publish(created(story(avatar(), slideImage(), unrendered, "Half")));
        assertThat(home(ContentFixture.STOREFRONT).get("stories").get(0).get("slides"))
                .hasSize(1);

        publish(created(story(avatar(), unrendered, unrendered, "None")));
        assertThat(home(ContentFixture.STOREFRONT).get("stories")).hasSize(1);
    }

    // =================================================================== pop-ups

    @Test
    @DisplayName(
            "an entry pop-up carries its timing and its frequency cap as a hint for the device to enforce, and only one pop-up is live at a time")
    void aPopup() throws Exception {
        UUID image = fixture.image(TENANT, BRAND, 1200, 800, "PUBLIC", "AVAILABLE", Map.of("CARD", 400, "DETAIL", 800));
        JsonNode first = publish(created("""
                {"kind":"POPUP","internalName":"Welcome","document":{
                  "texts":{"ru":{"title":"Добро пожаловать","body":"Скидка 10%% на первый заказ","ctaLabel":"Заказать"}},
                  "media":{"primaryAssetId":"%s"},"link":{"type":"CART"},
                  "showAfterSeconds":8,"daily":{"from":"09:00","until":"22:00","daysOfWeek":[1,2,3,4,5]},
                  "cap":{"mode":"EVERY_N_DAYS","n":3}}}""".formatted(image)));

        JsonNode popup = home(ContentFixture.STOREFRONT).get("popup");

        assertThat(popup.get("title").stringValue()).isEqualTo("Добро пожаловать");
        assertThat(popup.get("body").stringValue()).isEqualTo("Скидка 10% на первый заказ");
        assertThat(popup.get("revisionNo").intValue()).isEqualTo(1);
        assertThat(popup.get("showAfterSeconds").intValue()).isEqualTo(8);
        assertThat(popup.get("cap").get("mode").stringValue()).isEqualTo("EVERY_N_DAYS");
        assertThat(popup.get("cap").get("n").intValue()).isEqualTo(3);
        assertThat(popup.get("dismiss").stringValue()).isEqualTo("DISMISSIBLE");
        assertThat(popup.get("daily").get("from").stringValue()).isEqualTo("09:00");
        assertThat(popup.get("daily").get("daysOfWeek")).hasSize(5);
        assertThat(popup.get("media").get("renditions")).hasSize(2);

        // One at a time: the second is refused until the first is withdrawn.
        JsonNode second = created("""
                {"kind":"POPUP","internalName":"Second","document":{"texts":{"ru":{"title":"Второе окно"}}}}""");
        MvcResult full = send(
                MockMvcRequestBuilders.post(
                        CONTENT + "/items/" + second.get("id").stringValue() + "/publications"),
                OWNER,
                null,
                etag(second));
        assertThat(status(full)).isEqualTo(422);
        assertThat(body(full).get("errors").get(0).get("code").stringValue()).isEqualTo("CONTENT_PLACEMENT_FULL");

        assertThat(status(send(
                        MockMvcRequestBuilders.post(
                                CONTENT + "/items/" + first.get("id").stringValue() + "/withdrawals"),
                        OWNER,
                        null,
                        etag(first))))
                .isEqualTo(200);
        publish(second);
        assertThat(home(ContentFixture.STOREFRONT).get("popup").get("title").stringValue())
                .isEqualTo("Второе окно");
    }

    // ================================================================= promo grid

    @Test
    @DisplayName("a promo card placed in the grid is read in promoGrid and not in the carousel")
    void thePromoGrid() throws Exception {
        UUID card = fixture.goodCard(TENANT, BRAND);
        JsonNode grid = created("""
                {"kind":"PROMO_CARD","placement":"PROMO_GRID","internalName":"Grid","document":{
                  "texts":{"ru":{"title":"В сетке"}},"media":{"primaryAssetId":"%s"}}}""".formatted(card));
        assertThat(grid.get("placement").stringValue()).isEqualTo("PROMO_GRID");
        publish(grid);
        JsonNode inCarousel = created("""
                {"kind":"PROMO_CARD","internalName":"Default","document":{
                  "texts":{"ru":{"title":"В карусели"}},"media":{"primaryAssetId":"%s"}}}""".formatted(fixture.goodCard(TENANT, BRAND)));
        assertThat(inCarousel.get("placement").stringValue())
                .as("a promo card with no placement goes to the carousel")
                .isEqualTo("HOME_CAROUSEL");
        publish(inCarousel);

        JsonNode home = home(ContentFixture.STOREFRONT);

        assertThat(home.get("promoGrid"))
                .extracting(item -> item.get("title").stringValue())
                .containsExactly("В сетке");
        assertThat(home.get("carousel"))
                .extracting(item -> item.get("title").stringValue())
                .containsExactly("В карусели");
    }

    // =================================================================== counters

    private String events(String channel, String... events) {
        return "{\"channel\":\"" + channel + "\",\"events\":[" + String.join(",", events) + "]}";
    }

    private static String event(UUID item, int revision, String name) {
        return "{\"itemId\":\"" + item + "\",\"revisionNo\":" + revision + ",\"event\":\"" + name + "\"}";
    }

    private MvcResult report(String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(EVENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    @Test
    @DisplayName(
            "an anonymous storefront reports counts: they are filed per item, revision, channel and the tenant's calendar day, and the staff read says they are client-reported")
    void countersAreAnonymousAndPerTenantDay() throws Exception {
        JsonNode banner = publish(created("""
                {"kind":"BANNER","internalName":"Counted","document":{"texts":{"ru":{"title":"Считаем"}},
                 "media":{"primaryAssetId":"%s"}}}""".formatted(fixture.goodBanner(TENANT, BRAND))));
        UUID item = UUID.fromString(banner.get("id").stringValue());
        JsonNode popup = publish(created("""
                {"kind":"POPUP","internalName":"Counted pop-up","document":{"texts":{"ru":{"title":"Окно"}}}}"""));
        UUID popupId = UUID.fromString(popup.get("id").stringValue());
        JsonNode story = publish(created(story(avatar(), slideImage(), slideImage(), "Сториз")));
        UUID storyId = UUID.fromString(story.get("id").stringValue());

        // 23:30 in Tashkent (UTC+5) on 8 October.
        clock.set(Instant.parse("2026-10-08T18:30:00Z"));
        MvcResult first = report(events(
                ContentFixture.STOREFRONT,
                event(item, 1, "IMPRESSION"),
                event(item, 1, "IMPRESSION"),
                event(item, 1, "IMPRESSION"),
                event(item, 1, "CLICK"),
                event(popupId, 1, "DISMISSAL"),
                event(storyId, 1, "COMPLETION")));
        assertThat(status(first))
                .as(first.getResponse().getContentAsString(UTF_8))
                .isEqualTo(202);
        assertThat(body(first).get("accepted").intValue()).isEqualTo(6);
        assertThat(body(first).get("rejected").intValue()).isZero();

        // Half past midnight there: the next day, though still 8 October in UTC.
        clock.set(Instant.parse("2026-10-08T19:30:00Z"));
        assertThat(status(report(
                        events(ContentFixture.STOREFRONT, event(item, 1, "IMPRESSION"), event(item, 1, "IMPRESSION")))))
                .isEqualTo(202);

        JsonNode stats =
                body(send(get(CONTENT + "/items/" + item + "/stats?from=2026-10-01&to=2026-10-31"), OWNER, null, null));
        assertThat(stats.get("clientReported").booleanValue()).isTrue();
        assertThat(stats.get("rows")).hasSize(2);
        JsonNode newest = stats.get("rows").get(0);
        assertThat(newest.get("day").stringValue()).isEqualTo("2026-10-09");
        assertThat(newest.get("impressions").intValue()).isEqualTo(2);
        JsonNode oldest = stats.get("rows").get(1);
        assertThat(oldest.get("day").stringValue()).isEqualTo("2026-10-08");
        assertThat(oldest.get("impressions").intValue()).isEqualTo(3);
        assertThat(oldest.get("clicks").intValue()).isEqualTo(1);
        assertThat(oldest.get("channelCode").stringValue()).isEqualTo("STOREFRONT");
        assertThat(oldest.get("revisionNo").intValue()).isEqualTo(1);

        JsonNode popupStats = body(
                send(get(CONTENT + "/items/" + popupId + "/stats?from=2026-10-01&to=2026-10-31"), OWNER, null, null));
        assertThat(popupStats.get("rows").get(0).get("dismissals").intValue()).isEqualTo(1);
        JsonNode storyStats = body(
                send(get(CONTENT + "/items/" + storyId + "/stats?from=2026-10-01&to=2026-10-31"), OWNER, null, null));
        assertThat(storyStats.get("rows").get(0).get("completions").intValue()).isEqualTo(1);

        assertThat(status(send(get(CONTENT + "/items/" + item + "/stats"), LOCATION_MANAGER, null, null)))
                .as("reading the numbers needs content.read")
                .isEqualTo(403);
    }

    @Test
    @DisplayName(
            "a counter table holds a total and nothing that identifies who reported it: no device, customer, address or network column exists")
    void noIdentifierIsStored() {
        List<String> columns = jdbc.sql("SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'content' AND table_name = 'item_daily_stats' ORDER BY column_name")
                .query(String.class)
                .list();
        assertThat(columns)
                .containsExactlyInAnyOrder(
                        "tenant_id",
                        "brand_id",
                        "item_id",
                        "revision_no",
                        "day",
                        "channel_code",
                        "impressions",
                        "clicks",
                        "dismissals",
                        "completions");
    }

    @Test
    @DisplayName(
            "an event that names something the brand does not have, or that makes no sense for the kind, is rejected and counted, and the rest of the batch is kept")
    void badEventsAreRejectedNotFatal() throws Exception {
        JsonNode banner = publish(created("""
                {"kind":"BANNER","internalName":"Counted","document":{"texts":{"ru":{"title":"Считаем"}},
                 "media":{"primaryAssetId":"%s"}}}""".formatted(fixture.goodBanner(TENANT, BRAND))));
        UUID item = UUID.fromString(banner.get("id").stringValue());
        // An item of another brand of the same tenant.
        UUID foreign = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO content.items (id, tenant_id, brand_id, kind, placement, internal_name, display_title, state, sort_key, version, created_by)
                VALUES (:id, :t, :b, 'BANNER', 'HOME_CAROUSEL', 'other', '', 'DRAFT', 10, 1, 'seed')
                """)
                .param("id", foreign)
                .param("t", TENANT)
                .param("b", OTHER_BRAND)
                .update();

        MvcResult result = report(events(
                ContentFixture.STOREFRONT,
                event(item, 1, "IMPRESSION"),
                event(item, 9, "IMPRESSION"),
                event(UUID.randomUUID(), 1, "CLICK"),
                event(foreign, 1, "IMPRESSION"),
                event(item, 1, "DISMISSAL"),
                event(item, 1, "COMPLETION")));

        assertThat(status(result)).isEqualTo(202);
        assertThat(body(result).get("accepted").intValue()).isEqualTo(1);
        assertThat(body(result).get("rejected").intValue()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT sum(impressions + clicks + dismissals + completions) FROM content.item_daily_stats")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "a batch is at most twenty events and at least one, an event name is one of four, the channel is a content channel of the tenant, and the path is open to nobody else")
    void theBatchIsBounded() throws Exception {
        UUID item = UUID.randomUUID();
        List<String> twentyOne = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            twentyOne.add(event(item, 1, "IMPRESSION"));
        }
        assertThat(status(report(events(ContentFixture.STOREFRONT, twentyOne.toArray(String[]::new)))))
                .isEqualTo(400);
        assertThat(status(report(events(ContentFixture.STOREFRONT)))).isEqualTo(400);
        assertThat(status(report(events(ContentFixture.STOREFRONT, event(item, 1, "SCROLL")))))
                .isEqualTo(400);
        assertThat(status(report(events(ContentFixture.STOREFRONT, event(item, 0, "CLICK")))))
                .isEqualTo(400);
        assertThat(status(report(events("NO_SUCH_CHANNEL", event(item, 1, "CLICK")))))
                .isEqualTo(404);
        assertThat(status(report(events(ContentFixture.QR, event(item, 1, "CLICK")))))
                .as("a QR-table channel has no content")
                .isEqualTo(404);
        List<String> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            twenty.add(event(item, 1, "IMPRESSION"));
        }
        assertThat(status(report(events(ContentFixture.STOREFRONT, twenty.toArray(String[]::new)))))
                .isEqualTo(202);

        // Only that exact path is open: a sibling is still 401.
        MvcResult sibling = mvc.perform(MockMvcRequestBuilders.post(BASE + "/events/extra")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(status(sibling)).isEqualTo(401);
        assertThat(status(mvc.perform(get(EVENTS)).andReturn()))
                .as("GET is not the verb")
                .isIn(401, 405);
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

    /** The clock the whole context runs on, which a test moves across the tenant's midnight. */
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
