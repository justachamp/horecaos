package uz.horecaos.platform.content.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.commercial.application.PlanCatalogService;
import uz.horecaos.platform.commercial.application.SubscriptionService;
import uz.horecaos.platform.content.ContentFixture;
import uz.horecaos.platform.content.application.ContentPublicationService;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * A brand's storefront content authored over real HTTP (ADR 0157, Staff API): through the real
 * interceptor chain, the role-to-capability grants {@code PlatformRole} produces, the real
 * idempotency and optimistic-concurrency rules, and a real PostgreSQL.
 *
 * <p>Request bodies are the JSON the console sends -- only the keys it fills in -- because Jackson 3
 * refuses a missing primitive with a 400 and a test that built the request from the DTO would never
 * see it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContentEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-5000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b20-5000-7000-8000-0000000000c2");
    private static final UUID BRAND = UUID.fromString("018f9b20-5000-7000-8000-0000000000d1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-5000-7000-8000-0000000000d2");
    private static final UUID FOREIGN_BRAND = UUID.fromString("018f9b20-5000-7000-8000-0000000000d3");
    private static final UUID LOCATION = UUID.fromString("018f9b20-5000-7000-8000-0000000000e1");

    private static final UUID PRODUCT = UUID.fromString("018f9b20-5000-7000-8000-0000000000f1");
    private static final UUID CATEGORY = UUID.fromString("018f9b20-5000-7000-8000-0000000000f2");
    private static final UUID MISSING_CATEGORY = UUID.fromString("018f9b20-5000-7000-8000-0000000000f9");

    private static final String OWNER = "content-owner";
    private static final String ADMIN = "content-admin";
    private static final String BRAND_MANAGER = "content-brand-manager";
    private static final String LOCATION_MANAGER = "content-location-manager";
    private static final String FINANCE = "content-finance";
    private static final String STRANGER = "content-stranger";

    private static final String CONTENT = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/content";

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

    @Autowired
    @SuppressWarnings("NullAway")
    private ContentPublicationService publications;

    @Autowired
    @SuppressWarnings("NullAway")
    private PlatformTransactionManager transactions;

    private ContentFixture fixture;
    private UUID publication;
    private final java.util.concurrent.atomic.AtomicInteger keys = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE commercial.subscriptions, commercial.plan_entitlements,"
                        + " commercial.plan_versions, commercial.plans CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.policies, tenant.policy_current").update();
        // The menu, promotions and media sit in other modules' tables, which a tenant truncate
        // does not reach through their tenant-blind references.
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, catalog.catalogs,"
                        + " pricing.promotions, media.assets CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        fixture = new ContentFixture(jdbc);
        fixture.tenant(TENANT, "content-endpoint");
        fixture.tenant(OTHER_TENANT, "content-other");
        fixture.brand(TENANT, BRAND, "MAIN");
        fixture.brand(TENANT, OTHER_BRAND, "OTHER");
        fixture.brand(OTHER_TENANT, FOREIGN_BRAND, "FOREIGN");
        fixture.location(TENANT, BRAND, LOCATION, "CENTRE");
        fixture.channel(TENANT, ContentFixture.STOREFRONT, "WEB");
        fixture.channel(TENANT, ContentFixture.IOS, "IOS");
        fixture.channel(TENANT, ContentFixture.QR, "QR_TABLE");
        fixture.channel(TENANT, ContentFixture.WOLT, "AGGREGATOR");
        publication =
                fixture.publication(TENANT, BRAND, ContentFixture.STOREFRONT, List.of(PRODUCT), List.of(CATEGORY));

        fixture.grant(TENANT, OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        fixture.grant(TENANT, ADMIN, PlatformRole.TENANT_ADMIN, "TENANT", TENANT);
        fixture.grant(TENANT, BRAND_MANAGER, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        fixture.grant(TENANT, LOCATION_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION);
        fixture.grant(TENANT, FINANCE, PlatformRole.TENANT_FINANCE, "TENANT", TENANT);
        fixture.grant(OTHER_TENANT, STRANGER, PlatformRole.TENANT_OWNER, "TENANT", OTHER_TENANT);
    }

    // ==================================================================== helpers

    private MvcResult send(
            MockHttpServletRequestBuilder request, String subject, @Nullable String body, @Nullable String ifMatch)
            throws Exception {
        request = request.with(tokenFor(subject)).header("Idempotency-Key", "content-key-" + keys.incrementAndGet());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult post(String path, String subject, @Nullable String body, @Nullable String ifMatch)
            throws Exception {
        return send(MockMvcRequestBuilders.post(path), subject, body, ifMatch);
    }

    private MvcResult putTo(String path, String subject, String body, @Nullable String ifMatch) throws Exception {
        return send(put(path), subject, body, ifMatch);
    }

    private MvcResult read(String path, String subject) throws Exception {
        return mvc.perform(get(path).with(tokenFor(subject))).andReturn();
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

    private static List<String> codes(JsonNode problemBody) {
        List<String> codes = new ArrayList<>();
        for (JsonNode error : problemBody.path("errors")) {
            codes.add(error.path("code").stringValue());
        }
        return codes;
    }

    private static String bannerJson(UUID asset, String name, String title) {
        return """
                {"kind":"BANNER","internalName":"%s",
                 "document":{"texts":{"ru":{"title":"%s"}},"media":{"primaryAssetId":"%s"}}}""".formatted(name, title, asset);
    }

    private JsonNode createBanner(String name, String title) throws Exception {
        MvcResult created =
                post(CONTENT + "/items", OWNER, bannerJson(fixture.goodBanner(TENANT, BRAND), name, title), null);
        assertThat(status(created))
                .as(created.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        return body(created);
    }

    private JsonNode publish(JsonNode item) throws Exception {
        MvcResult published =
                post(CONTENT + "/items/" + item.get("id").stringValue() + "/publications", OWNER, null, etag(item));
        assertThat(status(published))
                .as(published.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        return body(published);
    }

    private JsonNode item(String id) throws Exception {
        return body(read(CONTENT + "/items/" + id, OWNER));
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    // ============================================================ the whole life

    @Test
    @DisplayName(
            "an operator uploads a 3:1 image, writes a title, links it to a category, publishes, extends the schedule and publishes again as revision 2, withdraws it and republishes revision 1")
    void theWholeLifeOfABanner() throws Exception {
        UUID asset = fixture.goodBanner(TENANT, BRAND);
        MvcResult created = post(CONTENT + "/items", OWNER, """
                {"kind":"BANNER","internalName":"Autumn offer",
                 "document":{"texts":{"ru":{"title":"Осенняя скидка","ctaLabel":"Смотреть"}},
                             "media":{"primaryAssetId":"%s"},
                             "link":{"type":"CATEGORY","targetId":"%s"}},
                 "startsAt":"2026-10-10T08:00:00Z","endsAt":"2026-10-17T08:00:00Z"}""".formatted(asset, CATEGORY), null);

        assertThat(status(created)).isEqualTo(201);
        assertThat(created.getResponse().getHeader("ETag")).isEqualTo("W/\"1\"");
        JsonNode draft = body(created);
        String id = draft.get("id").stringValue();
        assertThat(draft.get("kind").stringValue()).isEqualTo("BANNER");
        assertThat(draft.get("placement").stringValue()).isEqualTo("HOME_CAROUSEL");
        assertThat(draft.get("state").stringValue()).isEqualTo("DRAFT");
        assertThat(draft.get("displayTitle").stringValue()).isEqualTo("Осенняя скидка");
        assertThat(draft.get("liveRevision").isNull()).isTrue();
        assertThat(draft.get("visibility").isNull()).isTrue();
        assertThat(draft.get("hasUnpublishedChanges").booleanValue()).isTrue();
        assertThat(draft.get("problems")).isEmpty();
        assertThat(draft.get("draft")
                        .get("document")
                        .get("texts")
                        .get("ru")
                        .get("source")
                        .stringValue())
                .isEqualTo("AUTHORED");

        JsonNode first = publish(draft);
        assertThat(first.get("state").stringValue()).isEqualTo("PUBLISHED");
        assertThat(first.get("liveRevision").get("revisionNo").intValue()).isEqualTo(1);
        assertThat(first.get("liveRevision").get("contentHash").stringValue()).matches("^[0-9a-f]{64}$");
        assertThat(first.get("hasUnpublishedChanges").booleanValue()).isFalse();
        assertThat(first.get("version").intValue()).isEqualTo(2);

        // Extend the schedule by a week: an ordinary publish, a new revision.
        MvcResult extended =
                putTo(CONTENT + "/items/" + id + "/draft", OWNER, """
                {"document":{"texts":{"ru":{"title":"Осенняя скидка","ctaLabel":"Смотреть"}},
                             "media":{"primaryAssetId":"%s"},
                             "link":{"type":"CATEGORY","targetId":"%s"}},
                 "startsAt":"2026-10-10T08:00:00Z","endsAt":"2026-10-24T08:00:00Z"}""".formatted(asset, CATEGORY), etag(first));
        assertThat(status(extended))
                .as(extended.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        JsonNode edited = body(extended);
        assertThat(edited.get("hasUnpublishedChanges").booleanValue())
                .as("the live revision is untouched until the next publication")
                .isTrue();
        assertThat(edited.get("liveRevision").get("endsAt").stringValue()).startsWith("2026-10-17");

        JsonNode second = publish(edited);
        assertThat(second.get("liveRevision").get("revisionNo").intValue()).isEqualTo(2);
        assertThat(second.get("liveRevision").get("endsAt").stringValue()).startsWith("2026-10-24");

        // History: newest first, immutable, and revision 1 still says what it said.
        JsonNode revisions = body(read(CONTENT + "/items/" + id + "/revisions", OWNER));
        assertThat(revisions).hasSize(2);
        assertThat(revisions.get(0).get("revisionNo").intValue()).isEqualTo(2);
        assertThat(revisions.get(0).get("live").booleanValue()).isTrue();
        assertThat(revisions.get(1).get("live").booleanValue()).isFalse();
        JsonNode oldest = body(read(CONTENT + "/items/" + id + "/revisions/1", OWNER));
        assertThat(oldest.get("endsAt").stringValue()).startsWith("2026-10-17");
        assertThat(oldest.get("document").get("texts").get("ru").get("title").stringValue())
                .isEqualTo("Осенняя скидка");

        // Withdraw it: the live pointer goes and every revision stays.
        MvcResult withdrawn = post(CONTENT + "/items/" + id + "/withdrawals", OWNER, null, etag(second));
        assertThat(status(withdrawn)).isEqualTo(200);
        JsonNode down = body(withdrawn);
        assertThat(down.get("state").stringValue()).isEqualTo("WITHDRAWN");
        assertThat(down.get("liveRevision").isNull()).isTrue();
        assertThat(body(read(CONTENT + "/items/" + id + "/revisions", OWNER))).hasSize(2);

        // An older revision can be read and republished: it is cloned into the draft, then published.
        MvcResult cloned = post(CONTENT + "/items/" + id + "/revisions/1/republications", OWNER, null, etag(down));
        assertThat(status(cloned)).isEqualTo(200);
        JsonNode clonedItem = body(cloned);
        assertThat(clonedItem.get("draft").get("basedOnRevisionNo").intValue()).isEqualTo(1);
        assertThat(clonedItem.get("draft").get("endsAt").stringValue()).startsWith("2026-10-17");
        JsonNode third = publish(clonedItem);
        assertThat(third.get("liveRevision").get("revisionNo").intValue()).isEqualTo(3);
        assertThat(third.get("liveRevision").get("contentHash").stringValue())
                .as("revision 3 is revision 1 again, and says so")
                .isEqualTo(body(read(CONTENT + "/items/" + id + "/revisions/1", OWNER))
                        .get("contentHash")
                        .stringValue());
    }

    // ======================================================== nothing to publish

    @Test
    @DisplayName(
            "publishing an unchanged draft is CONTENT_NOTHING_TO_PUBLISH, and changing only the end date, only the channels or only the promotion makes it publishable; a withdrawn item is publishable unchanged")
    void nothingToPublishMeansNothing() throws Exception {
        JsonNode published = publish(createBanner("Offer", "Скидка"));
        String id = published.get("id").stringValue();

        MvcResult again = post(CONTENT + "/items/" + id + "/publications", OWNER, null, etag(published));
        assertThat(status(again)).isEqualTo(422);
        assertThat(body(again).get("code").stringValue()).isEqualTo("UNPROCESSABLE_STATE");
        assertThat(codes(body(again))).containsExactly("CONTENT_NOTHING_TO_PUBLISH");

        String document = """
                "document":{"texts":{"ru":{"title":"Скидка"}},"media":{"primaryAssetId":"%s"}}""".formatted(published
                .get("draft")
                .get("document")
                .get("media")
                .get("primaryAssetId")
                .stringValue());

        // Only the end date.
        JsonNode current = body(putTo(
                CONTENT + "/items/" + id + "/draft",
                OWNER,
                "{" + document + ",\"endsAt\":\"2030-01-01T00:00:00Z\"}",
                etag(published)));
        current = publish(current);
        assertThat(current.get("liveRevision").get("revisionNo").intValue()).isEqualTo(2);

        // Only the channel set.
        current = body(putTo(
                CONTENT + "/items/" + id + "/draft",
                OWNER,
                "{" + document + ",\"endsAt\":\"2030-01-01T00:00:00Z\",\"channelCodes\":[\"STOREFRONT\"]}",
                etag(current)));
        current = publish(current);
        assertThat(current.get("liveRevision").get("revisionNo").intValue()).isEqualTo(3);

        // Only the window's start.
        current = body(putTo(
                CONTENT + "/items/" + id + "/draft",
                OWNER,
                "{" + document
                        + ",\"startsAt\":\"2020-01-01T00:00:00Z\",\"endsAt\":\"2030-01-01T00:00:00Z\",\"channelCodes\":[\"STOREFRONT\"]}",
                etag(current)));
        current = publish(current);
        assertThat(current.get("liveRevision").get("revisionNo").intValue()).isEqualTo(4);

        // Saving the very same draft again is still nothing to publish.
        current = body(putTo(
                CONTENT + "/items/" + id + "/draft",
                OWNER,
                "{" + document
                        + ",\"startsAt\":\"2020-01-01T00:00:00Z\",\"endsAt\":\"2030-01-01T00:00:00Z\",\"channelCodes\":[\"STOREFRONT\"]}",
                etag(current)));
        MvcResult same = post(CONTENT + "/items/" + id + "/publications", OWNER, null, etag(current));
        assertThat(codes(body(same))).containsExactly("CONTENT_NOTHING_TO_PUBLISH");
        assertThat(current.get("hasUnpublishedChanges").booleanValue()).isFalse();

        // Withdrawn, the same draft has nothing to equal, so it is publishable.
        JsonNode down = body(post(CONTENT + "/items/" + id + "/withdrawals", OWNER, null, etag(current)));
        JsonNode back = publish(down);
        assertThat(back.get("liveRevision").get("revisionNo").intValue()).isEqualTo(5);
    }

    @Test
    @DisplayName("a promo card's promotion is one of the six values the hash covers")
    void thePromotionIsPartOfTheHash() throws Exception {
        UUID asset = fixture.goodCard(TENANT, BRAND);
        UUID promotion = fixture.promotion(
                TENANT, BRAND, "SUMMER", "ACTIVE", Instant.now().minus(Duration.ofDays(1)), null);
        String document = """
                "document":{"texts":{"ru":{"title":"Акция"}},"media":{"primaryAssetId":"%s"}}""".formatted(asset);
        MvcResult created = post(
                CONTENT + "/items",
                OWNER,
                "{\"kind\":\"PROMO_CARD\",\"internalName\":\"Card\"," + document + "}",
                null);
        assertThat(status(created))
                .as(created.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        JsonNode published = publish(body(created));
        String id = published.get("id").stringValue();

        JsonNode bound = body(putTo(
                CONTENT + "/items/" + id + "/draft",
                OWNER,
                "{" + document + ",\"promotionId\":\"" + promotion + "\"}",
                etag(published)));
        assertThat(bound.get("draft").get("promotionId").stringValue()).isEqualTo(promotion.toString());
        JsonNode rebound = publish(bound);
        assertThat(rebound.get("liveRevision").get("revisionNo").intValue()).isEqualTo(2);
        assertThat(rebound.get("liveRevision").get("promotionId").stringValue()).isEqualTo(promotion.toString());
    }

    // ====================================================================== gates

    private void assertGate(String itemJson, String code) throws Exception {
        MvcResult created = post(CONTENT + "/items", OWNER, itemJson, null);
        assertThat(status(created))
                .as(created.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        JsonNode item = body(created);
        assertThat(item.get("problems"))
                .as("the gate is a warning on the draft")
                .anySatisfy(
                        problem -> assertThat(problem.get("code").stringValue()).isEqualTo(code));
        MvcResult refused =
                post(CONTENT + "/items/" + item.get("id").stringValue() + "/publications", OWNER, null, etag(item));
        assertThat(status(refused)).isEqualTo(422);
        assertThat(body(refused).get("code").stringValue()).isEqualTo("UNPROCESSABLE_STATE");
        assertThat(codes(body(refused))).contains(code);
        assertThat(item(item.get("id").stringValue()).get("state").stringValue())
                .as("a refused publication leaves the item as it was")
                .isEqualTo("DRAFT");
    }

    private String banner(UUID asset, String texts, String extra) {
        return "{\"kind\":\"BANNER\",\"internalName\":\"gate\",\"document\":{\"texts\":" + texts
                + ",\"media\":{\"primaryAssetId\":\"" + asset + "\"}}" + extra + "}";
    }

    @Test
    @DisplayName("CONTENT_DEFAULT_LOCALE_MISSING: text in English only, for a brand whose default language is Russian")
    void defaultLocaleGate() throws Exception {
        assertGate(
                banner(fixture.goodBanner(TENANT, BRAND), "{\"en\":{\"title\":\"Sale\"}}", ""),
                "CONTENT_DEFAULT_LOCALE_MISSING");
    }

    @Test
    @DisplayName("CONTENT_LOCALE_INCOMPLETE: only when the brand's content.publishing policy demands every language")
    void localeCompletenessGate() throws Exception {
        UUID asset = fixture.goodBanner(TENANT, BRAND);
        String partial = "{\"ru\":{\"title\":\"Скидка\"}}";
        MvcResult created = post(CONTENT + "/items", OWNER, banner(asset, partial, ""), null);
        JsonNode item = body(created);
        assertThat(item.get("problems"))
                .as("a brand that asks nothing extra is not warned")
                .isEmpty();
        assertThat(status(post(
                        CONTENT + "/items/" + item.get("id").stringValue() + "/publications", OWNER, null, etag(item))))
                .isEqualTo(200);

        MvcResult policy =
                putTo(CONTENT + "/policies/publishing", OWNER, "{\"requireAllBrandLocales\":true}", "W/\"0\"");
        assertThat(status(policy))
                .as(policy.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(body(policy).get("publishing").get("requireAllBrandLocales").booleanValue())
                .isTrue();
        assertGate(banner(fixture.goodBanner(TENANT, BRAND), partial, ""), "CONTENT_LOCALE_INCOMPLETE");

        // With all three languages the same brand publishes.
        MvcResult complete = post(
                CONTENT + "/items",
                OWNER,
                banner(
                        fixture.goodBanner(TENANT, BRAND),
                        "{\"ru\":{\"title\":\"a\"},\"uz\":{\"title\":\"b\"},\"en\":{\"title\":\"c\"}}",
                        ""),
                null);
        assertThat(status(post(
                        CONTENT + "/items/" + body(complete).get("id").stringValue() + "/publications",
                        OWNER,
                        null,
                        etag(body(complete)))))
                .isEqualTo(200);
    }

    @Test
    @DisplayName(
            "CONTENT_MEDIA_NOT_DISPLAYABLE: no such asset, an asset still pending, a PRIVATE asset, and an asset of another tenant")
    void mediaDisplayableGate() throws Exception {
        String texts = "{\"ru\":{\"title\":\"Скидка\"}}";
        assertGate(banner(UUID.randomUUID(), texts, ""), "CONTENT_MEDIA_NOT_DISPLAYABLE");
        assertGate(
                banner(fixture.image(TENANT, BRAND, 1800, 600, "PUBLIC", "UPLOADED", Map.of()), texts, ""),
                "CONTENT_MEDIA_NOT_DISPLAYABLE");
        assertGate(
                banner(
                        fixture.image(TENANT, BRAND, 1800, 600, "PRIVATE", "AVAILABLE", Map.of("DETAIL", 800)),
                        texts,
                        ""),
                "CONTENT_MEDIA_NOT_DISPLAYABLE");
        assertGate(banner(fixture.goodBanner(OTHER_TENANT, FOREIGN_BRAND), texts, ""), "CONTENT_MEDIA_NOT_DISPLAYABLE");

        // A private asset is refused in every role, not only as the primary image.
        UUID privateAsset = fixture.image(TENANT, BRAND, 1800, 600, "PRIVATE", "AVAILABLE", Map.of("DETAIL", 800));
        assertGate(
                "{\"kind\":\"BANNER\",\"internalName\":\"override\",\"document\":{\"texts\":{\"ru\":{\"title\":\"x\","
                        + "\"mediaAssetId\":\"" + privateAsset + "\"}},\"media\":{\"primaryAssetId\":\""
                        + fixture.goodBanner(TENANT, BRAND) + "\"}}}",
                "CONTENT_MEDIA_NOT_DISPLAYABLE");
    }

    @Test
    @DisplayName("CONTENT_MEDIA_ASPECT_MISMATCH: more than 3% from the placement's ratio, or narrower than its minimum")
    void aspectGate() throws Exception {
        String texts = "{\"ru\":{\"title\":\"Скидка\"}}";
        // A square is not a 3:1 banner.
        assertGate(
                banner(
                        fixture.image(TENANT, BRAND, 1000, 1000, "PUBLIC", "AVAILABLE", Map.of("DETAIL", 800)),
                        texts,
                        ""),
                "CONTENT_MEDIA_ASPECT_MISMATCH");
        // 3:1 but only 900 px wide: under the 960 minimum.
        assertGate(
                banner(fixture.image(TENANT, BRAND, 900, 300, "PUBLIC", "AVAILABLE", Map.of("DETAIL", 800)), texts, ""),
                "CONTENT_MEDIA_ASPECT_MISMATCH");
        // 3% off is accepted, just over is not.
        UUID within = fixture.image(TENANT, BRAND, 1830, 600, "PUBLIC", "AVAILABLE", Map.of("DETAIL", 800));
        JsonNode accepted = body(post(CONTENT + "/items", OWNER, banner(within, texts, ""), null));
        assertThat(accepted.get("problems")).isEmpty();
        UUID over = fixture.image(TENANT, BRAND, 1900, 600, "PUBLIC", "AVAILABLE", Map.of("DETAIL", 800));
        assertGate(banner(over, texts, ""), "CONTENT_MEDIA_ASPECT_MISMATCH");
    }

    private String bannerWithLink(UUID asset, String link) {
        return "{\"kind\":\"BANNER\",\"internalName\":\"gate\",\"document\":{\"texts\":{\"ru\":{\"title\":\"x\"}},"
                + "\"media\":{\"primaryAssetId\":\"" + asset + "\"},\"link\":" + link + "}}";
    }

    @Test
    @DisplayName("CONTENT_TARGET_NOT_FOUND: a category or a product the brand's active publication does not hold")
    void targetGate() throws Exception {
        UUID asset = fixture.goodBanner(TENANT, BRAND);
        assertGate(
                bannerWithLink(asset, "{\"type\":\"CATEGORY\",\"targetId\":\"" + MISSING_CATEGORY + "\"}"),
                "CONTENT_TARGET_NOT_FOUND");
        assertGate(
                bannerWithLink(asset, "{\"type\":\"PRODUCT\",\"targetId\":\"" + UUID.randomUUID() + "\"}"),
                "CONTENT_TARGET_NOT_FOUND");

        // One the menu holds is fine.
        JsonNode fine = body(post(
                CONTENT + "/items",
                OWNER,
                bannerWithLink(asset, "{\"type\":\"PRODUCT\",\"targetId\":\"" + PRODUCT + "\"}"),
                null));
        assertThat(fine.get("problems")).isEmpty();
        assertThat(status(post(
                        CONTENT + "/items/" + fine.get("id").stringValue() + "/publications", OWNER, null, etag(fine))))
                .isEqualTo(200);
    }

    @Test
    @DisplayName(
            "CONTENT_PROMOTION_NOT_FOUND: a promo card bound to a promotion that does not exist for this brand is refused even as a draft")
    void promotionGate() throws Exception {
        UUID asset = fixture.goodCard(TENANT, BRAND);
        MvcResult refused = post(CONTENT + "/items", OWNER, """
                {"kind":"PROMO_CARD","internalName":"x","promotionId":"%s",
                 "document":{"texts":{"ru":{"title":"t"}},"media":{"primaryAssetId":"%s"}}}""".formatted(UUID.randomUUID(), asset), null);
        assertThat(status(refused)).isEqualTo(422);
        assertThat(codes(body(refused))).containsExactly("CONTENT_PROMOTION_NOT_FOUND");

        // Another brand's promotion is as absent as a made-up one.
        UUID foreign = fixture.promotion(
                TENANT, OTHER_BRAND, "OTHERS", "ACTIVE", Instant.now().minus(Duration.ofDays(1)), null);
        MvcResult other = post(CONTENT + "/items", OWNER, """
                {"kind":"PROMO_CARD","internalName":"x","promotionId":"%s",
                 "document":{"texts":{"ru":{"title":"t"}},"media":{"primaryAssetId":"%s"}}}""".formatted(foreign, asset), null);
        assertThat(codes(body(other))).containsExactly("CONTENT_PROMOTION_NOT_FOUND");

        // Only a promo card is bound to a promotion.
        MvcResult banner =
                post(CONTENT + "/items", OWNER, """
                {"kind":"BANNER","internalName":"x","promotionId":"%s",
                 "document":{"texts":{"ru":{"title":"t"}},"media":{"primaryAssetId":"%s"}}}""".formatted(foreign, fixture.goodBanner(TENANT, BRAND)), null);
        assertThat(status(banner)).isEqualTo(400);
    }

    @Test
    @DisplayName(
            "CONTENT_LINK_HOST_NOT_ALLOWED: an external link is refused until its host is on the brand's allowlist, and then it publishes")
    void linkHostGate() throws Exception {
        UUID asset = fixture.goodBanner(TENANT, BRAND);
        String link = "{\"type\":\"EXTERNAL\",\"url\":\"https://t.me/shopchannel\"}";
        assertGate(bannerWithLink(asset, link), "CONTENT_LINK_HOST_NOT_ALLOWED");

        MvcResult allowed = post(CONTENT + "/allowed-hosts", OWNER, "{\"host\":\"T.me\"}", null);
        assertThat(status(allowed)).isEqualTo(201);
        assertThat(body(allowed).get("host").stringValue()).isEqualTo("t.me");

        JsonNode item = body(post(CONTENT + "/items", OWNER, bannerWithLink(asset, link), null));
        assertThat(item.get("problems")).isEmpty();
        assertThat(status(post(
                        CONTENT + "/items/" + item.get("id").stringValue() + "/publications", OWNER, null, etag(item))))
                .isEqualTo(200);

        // A verified channel hostname is on the effective allowlist without anyone adding it.
        UUID channel = jdbc.sql("SELECT id FROM tenant.sales_channels WHERE tenant_id = :t AND code = 'STOREFRONT'")
                .param("t", TENANT)
                .query(UUID.class)
                .single();
        fixture.channelHostname(TENANT, channel, "shop.example.uz", true);
        JsonNode own = body(post(
                CONTENT + "/items",
                OWNER,
                bannerWithLink(asset, "{\"type\":\"EXTERNAL\",\"url\":\"https://shop.example.uz/menu\"}"),
                null));
        assertThat(own.get("problems")).isEmpty();
        // An unverified one is not.
        fixture.channelHostname(TENANT, channel, "shop.example.uz", false);
        JsonNode unverified = body(post(
                CONTENT + "/items",
                OWNER,
                bannerWithLink(asset, "{\"type\":\"EXTERNAL\",\"url\":\"https://shop.example.uz/menu\"}"),
                null));
        assertThat(unverified.get("problems"))
                .anySatisfy(problem ->
                        assertThat(problem.get("code").stringValue()).isEqualTo("CONTENT_LINK_HOST_NOT_ALLOWED"));
    }

    @Test
    @DisplayName(
            "CONTENT_CHANNEL_NOT_A_CONTENT_SURFACE: a QR-table channel, an aggregator and a code that is not the tenant's are refused as targets; web and iOS are accepted")
    void channelGate() throws Exception {
        UUID asset = fixture.goodBanner(TENANT, BRAND);
        for (String channel : new String[] {ContentFixture.QR, ContentFixture.WOLT, "NO_SUCH_CHANNEL"}) {
            assertGate(
                    banner(asset, "{\"ru\":{\"title\":\"x\"}}", ",\"channelCodes\":[\"" + channel + "\"]"),
                    "CONTENT_CHANNEL_NOT_A_CONTENT_SURFACE");
        }
        JsonNode fine = body(post(
                CONTENT + "/items",
                OWNER,
                banner(asset, "{\"ru\":{\"title\":\"x\"}}", ",\"channelCodes\":[\"STOREFRONT\",\"IOS_APP\"]"),
                null));
        assertThat(fine.get("problems")).isEmpty();
        assertThat(fine.get("draft").get("channelCodes")).hasSize(2);
    }

    @Test
    @DisplayName(
            "CONTENT_PLACEMENT_FULL: the eleventh banner in a carousel of ten is refused; withdrawing one makes room")
    void placementFullGate() throws Exception {
        List<JsonNode> live = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            live.add(publish(createBanner("Banner " + i, "Скидка " + i)));
        }
        JsonNode eleventh = createBanner("Eleventh", "Одиннадцатый");
        MvcResult refused = post(
                CONTENT + "/items/" + eleventh.get("id").stringValue() + "/publications", OWNER, null, etag(eleventh));
        assertThat(status(refused)).isEqualTo(422);
        assertThat(codes(body(refused))).containsExactly("CONTENT_PLACEMENT_FULL");

        // An item already live is not "added" by republishing it.
        JsonNode firstLive = live.get(0);
        MvcResult edit = putTo(
                CONTENT + "/items/" + firstLive.get("id").stringValue() + "/draft",
                OWNER,
                """
                {"document":{"texts":{"ru":{"title":"Новая"}},"media":{"primaryAssetId":"%s"}}}""".formatted(firstLive
                        .get("draft")
                        .get("document")
                        .get("media")
                        .get("primaryAssetId")
                        .stringValue()),
                etag(firstLive));
        assertThat(status(post(
                        CONTENT + "/items/" + firstLive.get("id").stringValue() + "/publications",
                        OWNER,
                        null,
                        etag(body(edit)))))
                .isEqualTo(200);

        assertThat(status(post(
                        CONTENT + "/items/" + live.get(1).get("id").stringValue() + "/withdrawals",
                        OWNER,
                        null,
                        etag(live.get(1)))))
                .isEqualTo(200);
        assertThat(status(post(
                        CONTENT + "/items/" + eleventh.get("id").stringValue() + "/publications",
                        OWNER,
                        null,
                        etag(eleventh))))
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a brand holds at most 500 items that are not archived; archiving one makes room")
    void itemsPerBrandAreBounded() throws Exception {
        jdbc.sql("""
                INSERT INTO content.items (id, tenant_id, brand_id, kind, placement, internal_name, display_title,
                                           state, sort_key, version, created_by)
                SELECT gen_random_uuid(), :t, :b, 'BANNER', 'HOME_CAROUSEL', 'filler ' || n, '', 'DRAFT', n, 1, 'seed'
                FROM generate_series(1, 500) AS n
                """).param("t", TENANT).param("b", BRAND).update();

        MvcResult refused = post(
                CONTENT + "/items", OWNER, bannerJson(fixture.goodBanner(TENANT, BRAND), "one too many", "x"), null);
        assertThat(status(refused)).isEqualTo(422);
        assertThat(codes(body(refused))).containsExactly("CONTENT_PLACEMENT_FULL");

        UUID someone = jdbc.sql("SELECT id FROM content.items WHERE tenant_id = :t LIMIT 1")
                .param("t", TENANT)
                .query(UUID.class)
                .single();
        assertThat(status(post(CONTENT + "/items/" + someone + "/archives", OWNER, null, "W/\"1\"")))
                .isEqualTo(200);
        assertThat(status(post(
                        CONTENT + "/items",
                        OWNER,
                        bannerJson(fixture.goodBanner(TENANT, BRAND), "fits now", "x"),
                        null)))
                .isEqualTo(201);
    }

    // ============================================================== concurrency

    @Test
    @DisplayName(
            "two concurrent publishes of one item have exactly one winner and a 409 for the other, and one revision is written")
    void concurrentPublishesHaveOneWinner() throws Exception {
        JsonNode draft = createBanner("Race", "Гонка");
        String path = CONTENT + "/items/" + draft.get("id").stringValue() + "/publications";
        String ifMatch = etag(draft);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tasks.add(() -> {
                ready.countDown();
                go.await();
                return status(post(path, OWNER, null, ifMatch));
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.sql("SELECT count(*) FROM content.item_revisions WHERE item_id = :id")
                        .param("id", UUID.fromString(draft.get("id").stringValue()))
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    /**
     * Publishes {@code first} inside a transaction that is held open, and {@code second} on another thread
     * while it is, then commits the first.
     *
     * <p>This is the interleaving that two operators publishing at the same moment produce, made certain
     * instead of likely: the second publication starts after the first has written everything and before
     * it has committed, so a check that only reads committed rows cannot see the first. If the second
     * completes inside the window, nothing made it wait.
     *
     * @return what the second publication threw, or null when it was published
     */
    private @Nullable Throwable publishWhileTheOtherIsUncommitted(JsonNode first, JsonNode second) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Future<Throwable>> other = new AtomicReference<>();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                publications.publish(
                        TENANT,
                        BRAND,
                        OWNER,
                        UUID.fromString(first.get("id").stringValue()),
                        first.get("version").longValue(),
                        null);
                Future<Throwable> result = pool.submit(() -> {
                    try {
                        publications.publish(
                                TENANT,
                                BRAND,
                                OWNER,
                                UUID.fromString(second.get("id").stringValue()),
                                second.get("version").longValue(),
                                null);
                        return null;
                    } catch (Throwable refused) {
                        return refused;
                    }
                });
                other.set(result);
                try {
                    result.get(3, TimeUnit.SECONDS);
                } catch (TimeoutException waiting) {
                    // The second publication is waiting for the first, which is what it should do.
                } catch (InterruptedException | java.util.concurrent.ExecutionException failure) {
                    throw new IllegalStateException(failure);
                }
            });
            return Objects.requireNonNull(other.get()).get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /** The gate codes a refused publication carries; fails when the second publication was not refused. */
    private static String refusalOf(@Nullable Throwable refused) {
        assertThat(refused).as("the second publication was refused").isInstanceOf(ApiException.class);
        return Objects.toString(
                ((ApiException) Objects.requireNonNull(refused)).properties().get("errors"));
    }

    private long publishedInTheCarousel() {
        return jdbc.sql("SELECT count(*) FROM content.items WHERE tenant_id = :t AND brand_id = :b"
                        + " AND placement = 'HOME_CAROUSEL' AND state = 'PUBLISHED'")
                .param("t", TENANT)
                .param("b", BRAND)
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName(
            "two publications of different items into the last free slot of a placement cannot both succeed: the second waits for the first and is then refused CONTENT_PLACEMENT_FULL")
    void theLastSlotOfAPlacementGoesToOnePublication() throws Exception {
        for (int i = 0; i < 9; i++) {
            publish(createBanner("Live " + i, "Живой " + i));
        }
        JsonNode first = createBanner("First", "Первый");
        JsonNode second = createBanner("Second", "Второй");

        Throwable refused = publishWhileTheOtherIsUncommitted(first, second);

        assertThat(refusalOf(refused)).contains("CONTENT_PLACEMENT_FULL");
        assertThat(publishedInTheCarousel())
                .as("the carousel holds ten, not eleven")
                .isEqualTo(10);
    }

    @Test
    @DisplayName(
            "two publications that would each be the brand's fifth live video cannot both succeed: the second waits for the first and is then refused CONTENT_VIDEO_LIMIT")
    void theLastLiveVideoGoesToOnePublication() throws Exception {
        fixture.entitle(plans, subscriptions, TENANT, false, true);
        for (int i = 0; i < 4; i++) {
            publish(createVideoBanner("Live video " + i));
        }
        JsonNode first = createVideoBanner("First video");
        JsonNode second = createVideoBanner("Second video");

        Throwable refused = publishWhileTheOtherIsUncommitted(first, second);

        assertThat(refusalOf(refused)).contains("CONTENT_VIDEO_LIMIT");
        assertThat(jdbc.sql("SELECT count(DISTINCT m.media_asset_id) FROM content.items i"
                                + " JOIN content.revision_media m ON m.item_id = i.id AND m.revision_no = i.live_revision_no"
                                + " JOIN media.assets a ON a.asset_id = m.media_asset_id"
                                + " WHERE i.tenant_id = :t AND i.brand_id = :b AND i.state = 'PUBLISHED'"
                                + " AND a.declared_content_type = 'video/mp4'")
                        .param("t", TENANT)
                        .param("b", BRAND)
                        .query(Long.class)
                        .single())
                .as("a brand has at most five live videos")
                .isEqualTo(5);
    }

    private JsonNode createVideoBanner(String name) throws Exception {
        MvcResult created = post(
                CONTENT + "/items",
                OWNER,
                """
                {"kind":"BANNER","internalName":"%s",
                 "document":{"texts":{"ru":{"title":"Видео"}},
                             "media":{"primaryAssetId":"%s","posterAssetId":"%s"}}}""".formatted(name, fixture.video(TENANT, BRAND), fixture.goodBanner(TENANT, BRAND)),
                null);
        assertThat(status(created))
                .as(created.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        return body(created);
    }

    // ================================================================== ordering

    @Test
    @DisplayName(
            "a placement is ordered with a token: the order takes effect at once, is not a revision, and a stale token or a wrong set of ids is refused")
    void orderingAPlacement() throws Exception {
        JsonNode a = publish(createBanner("A", "Первый"));
        JsonNode b = publish(createBanner("B", "Второй"));
        JsonNode c = createBanner("C", "Третий");
        String idA = a.get("id").stringValue();
        String idB = b.get("id").stringValue();
        String idC = c.get("id").stringValue();

        JsonNode listed = body(read(CONTENT + "/items?placement=HOME_CAROUSEL", OWNER));
        assertThat(listed.get("items"))
                .extracting(row -> row.get("id").stringValue())
                .containsExactly(idA, idB, idC);
        String token = listed.get("orderToken").stringValue();

        MvcResult stale = putTo(
                CONTENT + "/placements/HOME_CAROUSEL/order",
                OWNER,
                "{\"ids\":[\"" + idC + "\",\"" + idB + "\",\"" + idA + "\"],\"orderToken\":\"0000000000000000\"}",
                null);
        assertThat(status(stale)).isEqualTo(409);
        assertThat(body(stale).get("code").stringValue()).isEqualTo("STALE_VERSION");

        MvcResult wrongSet = putTo(
                CONTENT + "/placements/HOME_CAROUSEL/order",
                OWNER,
                "{\"ids\":[\"" + idC + "\",\"" + idB + "\"],\"orderToken\":\"" + token + "\"}",
                null);
        assertThat(status(wrongSet)).isEqualTo(400);

        MvcResult ordered = putTo(
                CONTENT + "/placements/HOME_CAROUSEL/order",
                OWNER,
                "{\"ids\":[\"" + idC + "\",\"" + idA + "\",\"" + idB + "\"],\"orderToken\":\"" + token + "\"}",
                null);
        assertThat(status(ordered))
                .as(ordered.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        String next = body(ordered).get("orderToken").stringValue();
        assertThat(next).isNotEqualTo(token);

        JsonNode after = body(read(CONTENT + "/items?placement=HOME_CAROUSEL", OWNER));
        assertThat(after.get("items"))
                .extracting(row -> row.get("id").stringValue())
                .containsExactly(idC, idA, idB);
        assertThat(after.get("orderToken").stringValue()).isEqualTo(next);
        assertThat(body(read(CONTENT + "/items/" + idA + "/revisions", OWNER)))
                .as("reordering is not a revision")
                .hasSize(1);
        assertThat(item(idA).get("version").intValue())
                .as("nor does it move the version an author's edit is checked against")
                .isEqualTo(a.get("version").intValue());

        // The token that was current a moment ago is stale now.
        MvcResult again = putTo(
                CONTENT + "/placements/HOME_CAROUSEL/order",
                OWNER,
                "{\"ids\":[\"" + idA + "\",\"" + idB + "\",\"" + idC + "\"],\"orderToken\":\"" + token + "\"}",
                null);
        assertThat(status(again)).isEqualTo(409);
    }

    // ===================================================================== list

    @Test
    @DisplayName(
            "the list filters by kind and state, pages with a cursor that is tied to its filters, and hides archived items")
    void listing() throws Exception {
        JsonNode live = publish(createBanner("Live", "Живой"));
        JsonNode draftOnly = createBanner("Draft", "Черновик");
        JsonNode archived = createBanner("Gone", "Ушёл");
        assertThat(status(post(
                        CONTENT + "/items/" + archived.get("id").stringValue() + "/archives",
                        OWNER,
                        null,
                        etag(archived))))
                .isEqualTo(200);

        JsonNode all = body(read(CONTENT + "/items", OWNER));
        assertThat(all.get("items"))
                .extracting(row -> row.get("internalName").stringValue())
                .containsExactly("Live", "Draft");
        JsonNode row = all.get("items").get(0);
        assertThat(row.get("state").stringValue()).isEqualTo("PUBLISHED");
        assertThat(row.get("visibility").stringValue()).isEqualTo("LIVE");
        assertThat(row.get("liveRevisionNo").intValue()).isEqualTo(1);

        assertThat(body(read(CONTENT + "/items?state=DRAFT", OWNER)).get("items"))
                .hasSize(1);
        assertThat(body(read(CONTENT + "/items?state=ARCHIVED", OWNER)).get("items"))
                .hasSize(1);
        assertThat(body(read(CONTENT + "/items?kind=POPUP", OWNER)).get("items"))
                .isEmpty();
        assertThat(body(read(CONTENT + "/items?visibility=LIVE", OWNER)).get("items"))
                .hasSize(1);
        assertThat(status(read(CONTENT + "/items?kind=NOPE", OWNER))).isEqualTo(400);

        JsonNode page = body(read(CONTENT + "/items?limit=1", OWNER));
        assertThat(page.get("items")).hasSize(1);
        String cursor = page.get("nextCursor").stringValue();
        JsonNode second = body(read(CONTENT + "/items?limit=1&cursor=" + cursor, OWNER));
        assertThat(second.get("items").get(0).get("internalName").stringValue()).isEqualTo("Draft");
        assertThat(second.get("nextCursor").isNull()).isTrue();
        assertThat(status(read(CONTENT + "/items?limit=1&kind=BANNER&cursor=" + cursor, OWNER)))
                .as("a cursor is tied to the filters it was issued for")
                .isEqualTo(400);
        assertThat(draftOnly.get("state").stringValue()).isEqualTo("DRAFT");
        assertThat(live.get("id").stringValue()).isNotEmpty();
    }

    // ===================================================================== drafts

    @Test
    @DisplayName(
            "a draft is discarded without touching the item or its revisions; an archived item can no longer be edited")
    void discardAndArchive() throws Exception {
        JsonNode published = publish(createBanner("Keep", "Остаётся"));
        String id = published.get("id").stringValue();

        MvcResult discarded = send(delete(CONTENT + "/items/" + id + "/draft"), OWNER, null, etag(published));
        assertThat(status(discarded)).isEqualTo(200);
        JsonNode after = body(discarded);
        assertThat(after.get("draft").isNull()).isTrue();
        assertThat(after.get("state").stringValue()).isEqualTo("PUBLISHED");
        assertThat(after.get("liveRevision").get("revisionNo").intValue()).isEqualTo(1);

        MvcResult archived = post(CONTENT + "/items/" + id + "/archives", OWNER, null, etag(after));
        assertThat(status(archived)).isEqualTo(200);
        JsonNode gone = body(archived);
        assertThat(gone.get("state").stringValue()).isEqualTo("ARCHIVED");
        assertThat(gone.get("liveRevision").isNull()).isTrue();
        assertThat(status(post(CONTENT + "/items/" + id + "/publications", OWNER, null, etag(gone))))
                .isEqualTo(409);
        assertThat(status(putTo(CONTENT + "/items/" + id + "/draft", OWNER, """
                {"document":{"texts":{"ru":{"title":"x"}}}}""", etag(gone))))
                .isEqualTo(409);
        assertThat(status(post(CONTENT + "/items/" + id + "/archives", OWNER, null, etag(gone))))
                .isEqualTo(409);
    }

    @Test
    @DisplayName(
            "a document with an unknown key is a 400 that names the path; every optional key may be omitted; a bare uz is stored as uz-Latn")
    void documentGrammarOverHttp() throws Exception {
        UUID asset = fixture.goodBanner(TENANT, BRAND);
        MvcResult unknown = post(CONTENT + "/items", OWNER, """
                {"kind":"BANNER","internalName":"x","document":{"texts":{"ru":{"title":"t","onclick":"x"}},
                 "media":{"primaryAssetId":"%s"}}}""".formatted(asset), null);
        assertThat(status(unknown)).isEqualTo(400);
        assertThat(body(unknown).get("code").stringValue()).isEqualTo("VALIDATION_FAILED");
        assertThat(body(unknown).get("errors")).anySatisfy(error -> {
            assertThat(error.get("field").stringValue()).isEqualTo("document.texts.ru.onclick");
            assertThat(error.get("code").stringValue()).isEqualTo("UNKNOWN_KEY");
        });

        // The console's real JSON with every optional key omitted: no placement, slug, channels, window.
        MvcResult minimal = post(CONTENT + "/items", OWNER, """
                {"kind":"BANNER","internalName":"minimal","document":{"texts":{"uz":{"title":"Chegirma"},"ru":{"title":"Скидка"}},
                 "media":{"primaryAssetId":"%s"}}}""".formatted(asset), null);
        assertThat(status(minimal))
                .as(minimal.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        JsonNode texts = body(minimal).get("draft").get("document").get("texts");
        assertThat(texts.propertyNames()).containsExactlyInAnyOrder("ru", "uz-Latn");

        MvcResult noKind = post(CONTENT + "/items", OWNER, "{\"internalName\":\"x\",\"document\":{}}", null);
        assertThat(status(noKind)).isEqualTo(400);
        MvcResult wrongPlacement = post(CONTENT + "/items", OWNER, """
                {"kind":"BANNER","placement":"STORY_RAIL","internalName":"x","document":{"texts":{"ru":{"title":"t"}}}}""", null);
        assertThat(status(wrongPlacement)).isEqualTo(400);
        MvcResult slug = post(CONTENT + "/items", OWNER, """
                {"kind":"BANNER","slug":"banner-slug","internalName":"x","document":{"texts":{"ru":{"title":"t"}}}}""", null);
        assertThat(status(slug)).isEqualTo(400);
    }

    // =================================================================== preview

    @Test
    @DisplayName(
            "the preview is the storefront document built from drafts by the same assembler, and it shows what the live read does not")
    void preview() throws Exception {
        JsonNode draft = createBanner("Only a draft", "Только черновик");
        String path = CONTENT + "/preview?channel=STOREFRONT&locale=ru";
        JsonNode previewed = body(read(path, OWNER));
        assertThat(previewed.get("carousel")).hasSize(1);
        assertThat(previewed.get("carousel").get(0).get("title").stringValue()).isEqualTo("Только черновик");
        assertThat(previewed.get("carousel").get(0).get("media").get("renditions"))
                .isNotEmpty();

        MvcResult live = mvc.perform(get("/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND
                        + "/content/home?channel=STOREFRONT&locale=ru"))
                .andReturn();
        assertThat(status(live)).isEqualTo(200);
        assertThat(body(live).get("carousel")).as("nothing is live yet").isEmpty();

        // A window in the future: the live read would hide it, the preview can look at it.
        publish(draft);
        putTo(
                CONTENT + "/items/" + draft.get("id").stringValue() + "/draft",
                OWNER,
                """
                {"document":{"texts":{"ru":{"title":"Позже"}},"media":{"primaryAssetId":"%s"}},"startsAt":"2031-01-01T00:00:00Z"}""".formatted(draft.get("draft")
                        .get("document")
                        .get("media")
                        .get("primaryAssetId")
                        .stringValue()),
                "W/\"2\"");
        assertThat(body(read(path, OWNER)).get("carousel"))
                .as("not yet started")
                .isEmpty();
        assertThat(body(read(path + "&asOf=2031-06-01T00:00:00Z", OWNER)).get("carousel"))
                .hasSize(1);
        assertThat(status(read(CONTENT + "/preview?channel=NOPE", OWNER))).isEqualTo(404);
    }

    // =================================================================== health

    @Test
    @DisplayName(
            "health names what the storefront has quietly degraded: a link whose target left the menu, a host no longer allowed, a promotion that is not live")
    void health() throws Exception {
        UUID asset = fixture.goodCard(TENANT, BRAND);
        UUID promotion = fixture.promotion(
                TENANT, BRAND, "HEALTH", "ACTIVE", Instant.now().minus(Duration.ofDays(1)), null);
        UUID extraCategory = UUID.randomUUID();
        fixture.publicationItem(TENANT, BRAND, publication, "CATEGORY", extraCategory);
        post(CONTENT + "/allowed-hosts", OWNER, "{\"host\":\"shop.example.uz\"}", null);

        JsonNode toCategory = publish(body(post(
                CONTENT + "/items",
                OWNER,
                bannerWithLink(
                        fixture.goodBanner(TENANT, BRAND),
                        "{\"type\":\"CATEGORY\",\"targetId\":\"" + extraCategory + "\"}"),
                null)));
        JsonNode external = publish(body(post(
                CONTENT + "/items",
                OWNER,
                bannerWithLink(
                        fixture.goodBanner(TENANT, BRAND),
                        "{\"type\":\"EXTERNAL\",\"url\":\"https://shop.example.uz/a\"}"),
                null)));
        JsonNode card = publish(body(post(CONTENT + "/items", OWNER, """
                {"kind":"PROMO_CARD","internalName":"bound","promotionId":"%s",
                 "document":{"texts":{"ru":{"title":"t"}},"media":{"primaryAssetId":"%s"}}}""".formatted(promotion, asset), null)));
        assertThat(body(read(CONTENT + "/health", OWNER)))
                .as("everything is healthy to begin with")
                .isEmpty();

        fixture.dropFromPublication(publication, extraCategory);
        fixture.promotionStatus(promotion, "SUSPENDED");
        assertThat(status(mvc.perform(delete(CONTENT + "/allowed-hosts/shop.example.uz")
                                .with(tokenFor(OWNER))
                                .header("Idempotency-Key", "health-" + keys.incrementAndGet()))
                        .andReturn()))
                .isEqualTo(204);

        JsonNode unhealthy = body(read(CONTENT + "/health", OWNER));
        assertThat(unhealthy).hasSize(3);
        assertThat(problemCodes(unhealthy, toCategory)).containsExactly("CONTENT_TARGET_NOT_FOUND");
        assertThat(problemCodes(unhealthy, external)).containsExactly("CONTENT_LINK_HOST_NOT_ALLOWED");
        assertThat(problemCodes(unhealthy, card)).containsExactly("CONTENT_PROMOTION_NOT_LIVE");
        assertThat(unhealthy.get(0).get("visibility").stringValue()).isEqualTo("LIVE");
    }

    private static List<String> problemCodes(JsonNode health, JsonNode item) {
        List<String> codes = new ArrayList<>();
        for (JsonNode row : health) {
            if (row.get("id").stringValue().equals(item.get("id").stringValue())) {
                row.get("problems")
                        .forEach(problem -> codes.add(problem.get("code").stringValue()));
            }
        }
        return codes;
    }

    // ================================================================= releases

    @Test
    @DisplayName(
            "CONTENT_REVISION_MEDIA_RELEASED: cloning a revision whose media has been released is refused with a stated reason")
    void aReleasedRevisionCannotBeRepublished() throws Exception {
        UUID asset = fixture.goodBanner(TENANT, BRAND);
        JsonNode first = publish(body(post(CONTENT + "/items", OWNER, bannerJson(asset, "Old", "Старый"), null)));
        String id = first.get("id").stringValue();
        UUID newer = fixture.goodBanner(TENANT, BRAND);
        JsonNode edited = body(putTo(
                CONTENT + "/items/" + id + "/draft",
                OWNER,
                "{\"document\":{\"texts\":{\"ru\":{\"title\":\"Новый\"}},\"media\":{\"primaryAssetId\":\"" + newer
                        + "\"}}}",
                etag(first)));
        JsonNode second = publish(edited);

        // The sweeper (Release 3) asks media to release the superseded revision's asset.
        jdbc.sql("UPDATE media.assets SET status = 'DELETION_REQUESTED' WHERE asset_id = :id")
                .param("id", asset)
                .update();

        MvcResult refused = post(CONTENT + "/items/" + id + "/revisions/1/republications", OWNER, null, etag(second));
        assertThat(status(refused)).isEqualTo(422);
        assertThat(codes(body(refused))).containsExactly("CONTENT_REVISION_MEDIA_RELEASED");
        MvcResult cloned = post(CONTENT + "/items/" + id + "/revisions/2/republications", OWNER, null, etag(second));
        assertThat(status(cloned)).isEqualTo(200);
        assertThat(status(post(
                        CONTENT + "/items/" + id + "/revisions/9/republications", OWNER, null, etag(body(cloned)))))
                .isEqualTo(404);
    }

    // =========================================================== capabilities

    @Test
    @DisplayName(
            "content.read, author and publish belong to the owner, the administrator and the brand manager; content.manage to the owner and administrator only; a branch manager and finance hold none")
    void capabilities() throws Exception {
        String list = CONTENT + "/items";
        for (String subject : new String[] {OWNER, ADMIN, BRAND_MANAGER}) {
            assertThat(status(read(list, subject))).as("%s reads", subject).isEqualTo(200);
        }
        for (String subject : new String[] {LOCATION_MANAGER, FINANCE}) {
            MvcResult refused = read(list, subject);
            assertThat(status(refused)).as("%s reads", subject).isEqualTo(403);
            assertThat(body(refused).get("code").stringValue()).isEqualTo("INSUFFICIENT_CAPABILITY");
        }

        UUID asset = fixture.goodBanner(TENANT, BRAND);
        JsonNode byManager = body(post(list, BRAND_MANAGER, bannerJson(asset, "By the manager", "Менеджер"), null));
        assertThat(byManager.get("createdBy").stringValue()).isEqualTo(BRAND_MANAGER);
        assertThat(status(post(
                        list + "/" + byManager.get("id").stringValue() + "/publications",
                        BRAND_MANAGER,
                        null,
                        etag(byManager))))
                .isEqualTo(200);
        assertThat(status(post(list, FINANCE, bannerJson(asset, "x", "x"), null)))
                .isEqualTo(403);
        assertThat(status(post(list, LOCATION_MANAGER, bannerJson(asset, "x", "x"), null)))
                .isEqualTo(403);

        // The link allowlist and the two policies are the owner's and the administrator's.
        String hosts = CONTENT + "/allowed-hosts";
        assertThat(status(post(hosts, BRAND_MANAGER, "{\"host\":\"one.example.uz\"}", null)))
                .isEqualTo(403);
        assertThat(status(post(hosts, ADMIN, "{\"host\":\"one.example.uz\"}", null)))
                .isEqualTo(201);
        assertThat(status(post(hosts, OWNER, "{\"host\":\"two.example.uz\"}", null)))
                .isEqualTo(201);
        assertThat(status(read(hosts, BRAND_MANAGER))).isEqualTo(200);
        assertThat(status(putTo(
                        CONTENT + "/policies/publishing",
                        BRAND_MANAGER,
                        "{\"requireAllBrandLocales\":true}",
                        "W/\"0\"")))
                .isEqualTo(403);
        assertThat(status(putTo(CONTENT + "/policies/editorial", BRAND_MANAGER, "{\"news\":true}", "W/\"0\"")))
                .isEqualTo(403);
    }

    @Test
    @DisplayName(
            "an unauthenticated caller is 401, and an owner of another tenant is refused on this tenant's brand and on a brand that is not theirs")
    void tenantIsolation() throws Exception {
        assertThat(status(mvc.perform(get(CONTENT + "/items")).andReturn())).isEqualTo(401);
        assertThat(status(read(CONTENT + "/items", STRANGER))).isEqualTo(403);
        assertThat(status(read("/api/v1/tenants/" + TENANT + "/brands/" + FOREIGN_BRAND + "/content/items", OWNER)))
                .as("a brand of another tenant is as absent as one that does not exist")
                .isIn(403, 404);

        // An item of one brand is not reachable through another brand's path, or another tenant's.
        JsonNode item = createBanner("Mine", "Моё");
        String id = item.get("id").stringValue();
        String otherBrand = "/api/v1/tenants/" + TENANT + "/brands/" + OTHER_BRAND + "/content/items/" + id;
        assertThat(status(read(otherBrand, OWNER))).isEqualTo(404);
        assertThat(status(post(otherBrand + "/publications", OWNER, null, etag(item))))
                .isEqualTo(404);
        assertThat(status(read(CONTENT + "/items/" + UUID.randomUUID(), OWNER))).isEqualTo(404);
    }

    @Test
    @DisplayName(
            "a mutating call without an Idempotency-Key, or a change without If-Match, is refused before anything is written; a stale If-Match is a 409; a replayed key writes once")
    void replaySafetyAndConcurrency() throws Exception {
        UUID asset = fixture.goodBanner(TENANT, BRAND);
        MvcResult noKey = mvc.perform(MockMvcRequestBuilders.post(CONTENT + "/items")
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bannerJson(asset, "x", "x")))
                .andReturn();
        assertThat(status(noKey)).isEqualTo(400);
        assertThat(noKey.getResponse().getContentAsString(UTF_8)).contains("IDEMPOTENCY_KEY_REQUIRED");

        JsonNode item = createBanner("Guarded", "Охрана");
        String id = item.get("id").stringValue();
        assertThat(status(post(CONTENT + "/items/" + id + "/publications", OWNER, null, null)))
                .isEqualTo(400);
        assertThat(status(putTo(CONTENT + "/items/" + id + "/draft", OWNER, "{\"document\":{}}", null)))
                .isEqualTo(400);

        MvcResult stale = post(CONTENT + "/items/" + id + "/publications", OWNER, null, "W/\"7\"");
        assertThat(status(stale)).isEqualTo(409);
        assertThat(body(stale).get("code").stringValue()).isEqualTo("STALE_VERSION");
        assertThat(body(stale).get("currentVersion").intValue()).isEqualTo(1);

        var replayed = MockMvcRequestBuilders.post(CONTENT + "/items")
                .with(tokenFor(OWNER))
                .header("Idempotency-Key", "same-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bannerJson(asset, "Replayed", "Повтор"));
        MvcResult first = mvc.perform(replayed).andReturn();
        MvcResult second = mvc.perform(replayed).andReturn();
        assertThat(status(first)).isEqualTo(201);
        assertThat(second.getResponse().getContentAsString(UTF_8))
                .isEqualTo(first.getResponse().getContentAsString(UTF_8));
        assertThat(jdbc.sql("SELECT count(*) FROM content.items WHERE internal_name = 'Replayed'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    // =========================================================== audit and events

    @Test
    @DisplayName(
            "publication, withdrawal, archiving, ordering and the allowlist are audited facts with no text in them, and publication and withdrawal reach the outbox with ids and kinds only")
    void auditAndOutbox() throws Exception {
        JsonNode first = publish(createBanner("Audited", "Секретный текст акции"));
        String id = first.get("id").stringValue();
        JsonNode second = createBanner("Second", "Второй текст");
        publish(second);

        AuditTrail.Fact published =
                AuditTrail.facts(jdbc, "content.item.published").get(0);
        assertThat(published.scopeType()).isEqualTo("BRAND");
        assertThat(published.scopeId()).isEqualTo(BRAND);
        assertThat(published.targetType()).isEqualTo("content.item");
        assertThat(published.targetId()).isEqualTo(UUID.fromString(id));
        assertThat(published.actorSubject()).isEqualTo(OWNER);
        assertThat(published.after("revisionNo").intValue()).isEqualTo(1);
        assertThat(published.after("kind").stringValue()).isEqualTo("BANNER");
        assertThat(published.after("locales").stringValue()).isEqualTo("ru");
        assertThat(published.after("contentHash").stringValue()).hasSize(64);
        assertThat(published.change().toString())
                .as("the text stays out of the history")
                .doesNotContain("Секретный");

        // A second revision records what changed from the first.
        JsonNode edited = body(putTo(
                CONTENT + "/items/" + id + "/draft",
                OWNER,
                """
                {"document":{"texts":{"ru":{"title":"Секретный текст акции"}},"media":{"primaryAssetId":"%s"}},"endsAt":"2031-01-01T00:00:00Z"}""".formatted(first.get("draft")
                        .get("document")
                        .get("media")
                        .get("primaryAssetId")
                        .stringValue()),
                etag(first)));
        assertThat(status(post(
                        CONTENT + "/items/" + id + "/publications",
                        OWNER,
                        "{\"reason\":\"Extended by a week for the autumn campaign\"}",
                        etag(edited))))
                .isEqualTo(200);
        assertThat(published.reason())
                .as(
                        "ADR 0027 wants a reason on every fact a user produces; publishing a banner is not worth a mandatory field")
                .isEqualTo("Published");
        AuditTrail.Fact republished =
                AuditTrail.facts(jdbc, "content.item.published").get(2);
        assertThat(republished.reason()).isEqualTo("Extended by a week for the autumn campaign");
        assertThat(republished.before("revisionNo").intValue()).isEqualTo(1);
        assertThat(republished.after("revisionNo").intValue()).isEqualTo(2);
        assertThat(republished.after("endsAt").stringValue()).startsWith("2031-01-01");

        JsonNode current = item(id);
        assertThat(status(post(CONTENT + "/items/" + id + "/withdrawals", OWNER, null, etag(current))))
                .isEqualTo(200);
        AuditTrail.Fact withdrawn = AuditTrail.only(jdbc, "content.item.withdrawn");
        assertThat(withdrawn.before("state").stringValue()).isEqualTo("PUBLISHED");
        assertThat(withdrawn.after("state").stringValue()).isEqualTo("WITHDRAWN");

        JsonNode live = item(second.get("id").stringValue());
        assertThat(status(post(
                        CONTENT + "/items/" + second.get("id").stringValue() + "/archives", OWNER, null, etag(live))))
                .isEqualTo(200);
        assertThat(AuditTrail.only(jdbc, "content.item.archived").after("state").stringValue())
                .isEqualTo("ARCHIVED");

        assertThat(status(post(CONTENT + "/allowed-hosts", OWNER, "{\"host\":\"audit.example.uz\"}", null)))
                .isEqualTo(201);
        assertThat(status(mvc.perform(delete(CONTENT + "/allowed-hosts/audit.example.uz")
                                .with(tokenFor(OWNER))
                                .header("Idempotency-Key", "audit-" + keys.incrementAndGet()))
                        .andReturn()))
                .isEqualTo(204);
        long securityFacts = jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE audit_class = 'SECURITY'"
                        + " AND action_code IN ('content.host.allowed', 'content.host.removed')")
                .query(Long.class)
                .single();
        assertThat(securityFacts)
                .as("each addition and removal is a security fact")
                .isEqualTo(2L);

        // The outbox: ids and kinds, never text, never a URL.
        List<String> events = jdbc.sql("SELECT event_type FROM integration.outbox_events"
                        + " WHERE event_type LIKE 'ContentItem%' ORDER BY occurred_at, event_id")
                .query(String.class)
                .list();
        assertThat(events)
                .containsExactly(
                        "ContentItemPublished",
                        "ContentItemPublished",
                        "ContentItemPublished",
                        "ContentItemWithdrawn",
                        "ContentItemWithdrawn");
        String payloads = jdbc.sql(
                        "SELECT string_agg(payload::text, ' ') FROM integration.outbox_events WHERE event_type LIKE 'ContentItem%'")
                .query(String.class)
                .single();
        assertThat(payloads).doesNotContain("Секретный").doesNotContain("http");
        JsonNode payload = json.readTree(jdbc.sql("SELECT payload::text FROM integration.outbox_events"
                        + " WHERE event_type = 'ContentItemPublished' ORDER BY occurred_at, event_id LIMIT 1")
                .query(String.class)
                .single());
        assertThat(payload.get("kind").stringValue()).isEqualTo("BANNER");
        assertThat(payload.get("placement").stringValue()).isEqualTo("HOME_CAROUSEL");
        assertThat(payload.get("revisionNo").intValue()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT DISTINCT topic FROM integration.outbox_events WHERE event_type LIKE 'ContentItem%'")
                        .query(String.class)
                        .list())
                .containsExactly("content.events");
    }

    @Test
    @DisplayName("ordering a placement is an audited business fact naming the items' new positions, and nothing else")
    void orderingIsAudited() throws Exception {
        JsonNode a = publish(createBanner("A", "Первый"));
        JsonNode b = publish(createBanner("B", "Второй"));
        String token = body(read(CONTENT + "/items?placement=HOME_CAROUSEL", OWNER))
                .get("orderToken")
                .stringValue();
        assertThat(status(putTo(
                        CONTENT + "/placements/HOME_CAROUSEL/order",
                        OWNER,
                        "{\"ids\":[\"" + b.get("id").stringValue() + "\",\""
                                + a.get("id").stringValue() + "\"],\"orderToken\":\"" + token + "\"}",
                        null)))
                .isEqualTo(200);

        AuditTrail.Fact reordered = AuditTrail.only(jdbc, "content.placement.reordered");
        assertThat(reordered.before(a.get("id").stringValue()).stringValue()).isEqualTo("1");
        assertThat(reordered.after(a.get("id").stringValue()).stringValue()).isEqualTo("2");
        assertThat(reordered.after(b.get("id").stringValue()).stringValue()).isEqualTo("1");
        assertThat(reordered.change().toString()).doesNotContain("Первый");
    }

    // ======================================================== a revoked video entitlement

    @Test
    @DisplayName(
            "a plan that stops including video does not lock the console: the video banner is still listed, read, ordered, flagged, withdrawn and archived; only publishing a video is refused (403)")
    void aVideoBannerStaysManageableAfterTheEntitlementIsRevoked() throws Exception {
        fixture.entitle(plans, subscriptions, TENANT, false, true);
        UUID video = fixture.video(TENANT, BRAND);
        UUID poster = fixture.goodBanner(TENANT, BRAND);
        String videoBanner = """
                {"kind":"BANNER","internalName":"Video offer",
                 "document":{"texts":{"ru":{"title":"Видео"}},
                             "media":{"primaryAssetId":"%s","posterAssetId":"%s"}}}""".formatted(video, poster);
        MvcResult created = post(CONTENT + "/items", OWNER, videoBanner, null);
        assertThat(status(created))
                .as(created.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        JsonNode live = publish(body(created));
        String id = live.get("id").stringValue();
        JsonNode image = publish(body(post(
                CONTENT + "/items",
                OWNER,
                bannerJson(fixture.goodBanner(TENANT, BRAND), "Image offer", "Картинка"),
                null)));

        // The plan is downgraded: the tenant sits on the catalogue's own safe default, video off.
        jdbc.sql("TRUNCATE TABLE commercial.subscriptions, commercial.plan_entitlements,"
                        + " commercial.plan_versions, commercial.plans CASCADE")
                .update();

        MvcResult listed = read(CONTENT + "/items?placement=HOME_CAROUSEL", OWNER);
        assertThat(status(listed))
                .as(
                        "the placement can still be listed: %s",
                        listed.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        JsonNode rows = body(listed).get("items");
        assertThat(rows).hasSize(2);
        for (JsonNode row : rows) {
            boolean isVideo = row.get("id").stringValue().equals(id);
            assertThat(row.get("problems").findValuesAsString("code"))
                    .as("only the video item is flagged, as a warning")
                    .isEqualTo(isVideo ? List.of("CONTENT_VIDEO_NOT_ENTITLED") : List.of());
        }

        MvcResult one = read(CONTENT + "/items/" + id, OWNER);
        assertThat(status(one)).isEqualTo(200);
        assertThat(body(one).get("liveRevision").get("revisionNo").intValue()).isEqualTo(1);

        JsonNode health = body(read(CONTENT + "/health", OWNER));
        assertThat(problemCodes(health, live)).containsExactly("CONTENT_VIDEO_NOT_ENTITLED");
        assertThat(problemCodes(health, image)).isEmpty();

        // Ordering the placement works with the video item in it.
        String token = body(listed).get("orderToken").stringValue();
        MvcResult ordered = putTo(
                CONTENT + "/placements/HOME_CAROUSEL/order",
                OWNER,
                "{\"ids\":[\"%s\",\"%s\"],\"orderToken\":\"%s\"}"
                        .formatted(image.get("id").stringValue(), id, token),
                null);
        assertThat(status(ordered))
                .as(ordered.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);

        // A new publication that carries a video is the one thing refused.
        JsonNode current = item(id);
        MvcResult edit = putTo(CONTENT + "/items/" + id + "/draft", OWNER, """
                {"document":{"texts":{"ru":{"title":"Видео 2"}},
                             "media":{"primaryAssetId":"%s","posterAssetId":"%s"}}}""".formatted(video, poster), etag(current));
        assertThat(status(edit))
                .as(
                        "a draft that keeps its video can still be edited: %s",
                        edit.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        MvcResult republish = post(CONTENT + "/items/" + id + "/publications", OWNER, null, etag(body(edit)));
        assertThat(status(republish)).isEqualTo(403);
        assertThat(body(republish).get("code").stringValue()).isEqualTo("ENTITLEMENT_REQUIRED");

        // Taking it down is never refused.
        JsonNode beforeTakedown = item(id);
        MvcResult withdrawn = post(CONTENT + "/items/" + id + "/withdrawals", OWNER, null, etag(beforeTakedown));
        assertThat(status(withdrawn))
                .as(withdrawn.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(body(withdrawn).get("state").stringValue()).isEqualTo("WITHDRAWN");
        MvcResult archived = post(CONTENT + "/items/" + id + "/archives", OWNER, null, etag(body(withdrawn)));
        assertThat(status(archived))
                .as(archived.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
    }

    // ================================================ the creative a brand manager uploads

    @Test
    @DisplayName(
            "a brand manager, who holds content.author and content.publish at her brand, can also upload, finalize, poll and preview a creative for that brand -- and for no other (the media routes under the brand)")
    void aBrandManagerUploadsTheCreativeForHerOwnBrandOnly() throws Exception {
        String own = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/media/assets";
        String other = "/api/v1/tenants/" + TENANT + "/brands/" + OTHER_BRAND + "/media/assets";
        String image = """
                {"visibility":"PUBLIC","contentType":"image/jpeg","sizeBytes":120000,
                 "filename":"autumn.jpg","renditionProfile":"CREATIVE"}""";

        MvcResult requested = post(own + "/upload-requests", BRAND_MANAGER, image, null);
        assertThat(status(requested))
                .as(requested.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        UUID asset = UUID.fromString(body(requested).get("assetId").stringValue());
        Map<String, Object> row = jdbc.sql("SELECT owner_scope, owner_id, visibility, rendition_profile, status"
                        + " FROM media.assets WHERE asset_id = :id")
                .param("id", asset)
                .query()
                .singleRow();
        assertThat(row.get("owner_scope"))
                .as("owned by the brand the route names")
                .isEqualTo("BRAND");
        assertThat(row.get("owner_id")).isEqualTo(BRAND);
        assertThat(row.get("visibility")).isEqualTo("PUBLIC");
        assertThat(row.get("rendition_profile")).isEqualTo("CREATIVE");

        // Her brand only: the same call under another brand of the same tenant, and under another tenant.
        MvcResult elsewhere = post(other + "/upload-requests", BRAND_MANAGER, image, null);
        assertThat(status(elsewhere)).isEqualTo(403);
        assertThat(body(elsewhere).get("code").stringValue()).isEqualTo("INSUFFICIENT_CAPABILITY");
        assertThat(status(post(own + "/upload-requests", STRANGER, image, null)))
                .as("another tenant's owner")
                .isEqualTo(403);
        assertThat(status(post(own + "/upload-requests", LOCATION_MANAGER, image, null)))
                .as("a location role holds no media capability")
                .isEqualTo(403);
        assertThat(status(post(own + "/upload-requests", FINANCE, image, null)))
                .as("finance holds no media capability")
                .isEqualTo(403);
        // A grant above the brand covers the brand's route.
        MvcResult byAdmin = post(other + "/upload-requests", ADMIN, image, null);
        assertThat(status(byAdmin))
                .as(byAdmin.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        UUID othersAsset = UUID.fromString(body(byAdmin).get("assetId").stringValue());

        // A video is the plan's: refused until the entitlement is on, then allocated like an image.
        String video = """
                {"visibility":"PUBLIC","contentType":"video/mp4","sizeBytes":5000000,"filename":"a.mp4"}""";
        MvcResult refused = post(own + "/upload-requests", BRAND_MANAGER, video, null);
        assertThat(status(refused)).isEqualTo(403);
        assertThat(body(refused).get("code").stringValue()).isEqualTo("ENTITLEMENT_REQUIRED");
        fixture.entitle(plans, subscriptions, TENANT, false, true);
        assertThat(status(post(own + "/upload-requests", BRAND_MANAGER, video, null)))
                .isEqualTo(200);

        // Finalize and poll: hers, and only hers.
        MvcResult finalized = post(own + "/" + asset + "/finalize", BRAND_MANAGER, null, null);
        assertThat(status(finalized))
                .as(finalized.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(body(finalized).get("status").stringValue()).isEqualTo("UPLOADED");
        assertThat(status(post(own + "/" + othersAsset + "/finalize", BRAND_MANAGER, null, null)))
                .as("another brand's asset is not found under this brand's route")
                .isEqualTo(404);
        MvcResult polled = read(own + "/" + asset, BRAND_MANAGER);
        assertThat(status(polled)).isEqualTo(200);
        assertThat(body(polled).get("status").stringValue()).isEqualTo("UPLOADED");
        assertThat(status(read(own + "/" + othersAsset, BRAND_MANAGER))).isEqualTo(404);

        // Preview: an available creative of her brand gets a signed URL, another brand's does not.
        UUID mine = fixture.goodBanner(TENANT, BRAND);
        UUID theirs = fixture.goodBanner(TENANT, OTHER_BRAND);
        MvcResult preview = read(own + "/" + mine + "/download-url?variant=THUMBNAIL", BRAND_MANAGER);
        assertThat(status(preview))
                .as(preview.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(body(preview).get("url").stringValue()).isNotBlank();
        assertThat(status(read(own + "/" + theirs + "/download-url?variant=THUMBNAIL", BRAND_MANAGER)))
                .isEqualTo(404);
        assertThat(status(read(other + "/" + theirs + "/download-url?variant=THUMBNAIL", BRAND_MANAGER)))
                .as("and she cannot name the other brand in the path to reach it")
                .isEqualTo(403);
    }

    // ================================================================= policies

    @Test
    @DisplayName(
            "an editorial kind needs both the plan's entitlement (403 ENTITLEMENT_REQUIRED) and the brand's policy (422 CONTENT_KIND_NOT_ENABLED)")
    void editorialNeedsTheEntitlementAndThePolicy() throws Exception {
        String news = """
                {"kind":"NEWS","slug":"opening","internalName":"Opening","document":{"texts":{"ru":{"title":"Открытие"}},
                 "body":{"ru":{"blocks":[{"type":"PARAGRAPH","inlines":[{"text":"Мы открылись."}]}]}}}}""";

        MvcResult noEntitlement = post(CONTENT + "/items", OWNER, news, null);
        assertThat(status(noEntitlement)).isEqualTo(403);
        assertThat(body(noEntitlement).get("code").stringValue()).isEqualTo("ENTITLEMENT_REQUIRED");

        fixture.entitle(plans, subscriptions, TENANT, true, false);
        MvcResult noPolicy = post(CONTENT + "/items", OWNER, news, null);
        assertThat(status(noPolicy)).isEqualTo(422);
        assertThat(codes(body(noPolicy))).containsExactly("CONTENT_KIND_NOT_ENABLED");

        JsonNode policies = body(read(CONTENT + "/policies", OWNER));
        assertThat(policies.get("editorialEntitled").booleanValue()).isTrue();
        assertThat(policies.get("videoEntitled").booleanValue()).isFalse();
        assertThat(policies.get("editorial").get("news").booleanValue()).isFalse();
        assertThat(policies.get("editorialVersion").intValue()).isZero();

        MvcResult turnedOn = putTo(CONTENT + "/policies/editorial", OWNER, "{\"news\":true}", "W/\"0\"");
        assertThat(status(turnedOn))
                .as(turnedOn.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(body(turnedOn).get("editorial").get("news").booleanValue()).isTrue();
        assertThat(body(turnedOn).get("editorial").get("gallery").booleanValue())
                .isFalse();
        assertThat(body(turnedOn).get("editorialVersion").intValue()).isEqualTo(1);

        // A stale policy version is refused like any other change.
        assertThat(status(putTo(CONTENT + "/policies/editorial", OWNER, "{\"news\":false}", "W/\"0\"")))
                .isEqualTo(409);

        MvcResult created = post(CONTENT + "/items", OWNER, news, null);
        assertThat(status(created))
                .as(created.getResponse().getContentAsString(UTF_8))
                .isEqualTo(201);
        JsonNode article = body(created);
        assertThat(article.get("kind").stringValue()).isEqualTo("NEWS");
        assertThat(article.get("placement").isNull()).isTrue();
        assertThat(article.get("slug").stringValue()).isEqualTo("opening");
        assertThat(status(post(
                        CONTENT + "/items/" + article.get("id").stringValue() + "/publications",
                        OWNER,
                        null,
                        etag(article))))
                .isEqualTo(200);

        // The same slug cannot name two live items of a kind.
        assertThat(status(post(CONTENT + "/items", OWNER, news, null))).isEqualTo(409);
        // A kind the policy did not turn on stays off.
        MvcResult gallery = post(CONTENT + "/items", OWNER, """
                {"kind":"GALLERY_ALBUM","slug":"hall","internalName":"Hall","document":{"texts":{"ru":{"title":"Зал"}}}}""", null);
        assertThat(codes(body(gallery))).containsExactly("CONTENT_KIND_NOT_ENABLED");
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
