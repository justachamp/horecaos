package uz.horecaos.platform.reviews.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.reviews.application.PublicReviewCache;
import uz.horecaos.platform.reviews.application.PublicReviewService;
import uz.horecaos.platform.reviews.application.ReviewsErasureParticipant;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The tags, the consent box, the moderation of comments and the public page, over HTTP (ADR 0159,
 * Decisions 3, 5, 6 and 7), with a real cache and a real consent store behind them.
 *
 * <p>The properties under test are the ones that put a customer's words in front of a stranger, so most
 * assertions state what must <em>not</em> reach the public: a comment nobody approved, one the customer
 * never offered, one a moderator hid a moment ago (read through a cache that must already be empty), one
 * the customer took back, and a name, an order number or a time of day on any of them.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewFeedbackEndpointTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ISSUER = "https://issuer.test/realms/horecaos";

    private static final UUID TENANT = UUID.fromString("018f9f10-c000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-c000-7000-8000-0000000000b1");
    private static final UUID CHILONZOR = UUID.fromString("018f9f10-c000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-c000-7000-8000-0000000000e1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f10-c000-7000-8000-0000000000e2");
    private static final UUID OTHER_BRANCH = UUID.fromString("018f9f10-c000-7000-8000-0000000000e3");

    private static final String OWNER = "feedback-owner";
    private static final String BRAND_MANAGER = "feedback-brand-manager";
    private static final String CHI_MANAGER = "feedback-chilonzor-manager";
    private static final String OTHER_OWNER = "feedback-other-owner";
    private static final String GUEST = "feedback-guest";

    private static final AtomicInteger KEYS = new AtomicInteger();

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the feedback endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
        registry.add("horecaos.reviews.sla-sweep.initial-delay", () -> "PT1H");
        registry.add("horecaos.reviews.sla-sweep.interval", () -> "PT1H");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private JdbcAuthorizationService authorization;

    @Autowired
    private PublicReviewCache publicCache;

    @Autowired
    private ReviewsErasureParticipant erasure;

    @Autowired
    private TransactionTemplate transactions;

    private OrderBoardFixtures fixtures;
    private int orders;

    @BeforeEach
    void reset() throws Exception {
        fixtures = new OrderBoardFixtures(jdbc);
        orders = 0;
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE integration.outbox_events").update();
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE reviews.order_reviews CASCADE").update();
        jdbc.sql("TRUNCATE TABLE reviews.feedback_tags CASCADE").update();
        jdbc.sql("TRUNCATE TABLE iam.staff_members CASCADE").update();
        jdbc.sql(
                        "DELETE FROM tenant.configuration_values WHERE key_code LIKE 'feature.%' OR key_code LIKE 'feedback.%'")
                .update();
        jdbc.sql("DELETE FROM tenant.policy_current").update();
        jdbc.sql("DELETE FROM tenant.policies").update();
        fixtures.clean();
        roleRegistry.synchronize();

        fixtures.tenant(TENANT, "feedback-endpoint", BRAND, CHILONZOR);
        fixtures.tenant(OTHER_TENANT, "feedback-other", OTHER_BRAND, OTHER_BRANCH);

        grant(TENANT, OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        grant(TENANT, BRAND_MANAGER, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        grant(TENANT, CHI_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", CHILONZOR);
        grant(OTHER_TENANT, OTHER_OWNER, PlatformRole.TENANT_OWNER, "TENANT", OTHER_TENANT);
        List.of(OWNER, BRAND_MANAGER, CHI_MANAGER).forEach(subject -> authorization.evictGrants(subject, TENANT));
        authorization.evictGrants(OTHER_OWNER, OTHER_TENANT);

        jdbc.sql("""
                INSERT INTO customer.principal_links (id, tenant_id, identity_partition_brand_id,
                    customer_account_id, issuer, subject, status, linked_at)
                VALUES (:id, :t, NULL, :account, :issuer, :subject, 'ACTIVE', now())
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("account", OrderBoardFixtures.customerOf(TENANT))
                .param("issuer", ISSUER)
                .param("subject", GUEST)
                .update();
        publicCache.evictNow(TENANT, BRAND);
    }

    // ================================================================== the tags

    @Test
    @DisplayName("the tags a customer is offered follow the rating, and the server refuses a tag that does not fit")
    void tagsFollowTheRating() throws Exception {
        switchOn("feature.feedback_settings");
        Map<String, String> tags = starterPack();

        JsonNode config = json(get(storefront() + "/feedback/config"));
        assertThat(config.path("enabled").asBoolean()).isTrue();
        assertThat(config.path("positive")).hasSize(6);
        assertThat(config.path("negative")).hasSize(6);
        assertThat(config.path("maxTagsPerReview").asInt()).isEqualTo(5);
        assertThat(config.path("publicComments").asBoolean())
                .as("nothing is public yet, so no box")
                .isFalse();
        assertThat(config.path("consentTextKey").isNull()).isTrue();
        assertThat(config.path("positive").get(0).path("labels").path("ru").asText())
                .isNotBlank();
        assertThat(config.path("positive").get(0).path("labels").path("uz-Latn").asText())
                .isNotBlank();
        assertThat(config.path("positive").get(0).path("labels").path("en").asText())
                .isNotBlank();
        assertThat(config.toString()).as("no courier tag in the pack").doesNotContain("COURIER");

        assertThat(submit(order(), 1, "cold", List.of(tags.get("food_tasty")), false)
                        .getResponse()
                        .getStatus())
                .as("a positive tag on one star")
                .isEqualTo(400);
        assertThat(submit(order(), 5, "lovely", List.of(tags.get("food_cold")), false)
                        .getResponse()
                        .getStatus())
                .as("a negative tag on five stars")
                .isEqualTo(400);
        assertThat(submit(order(), 3, null, List.of(tags.get("food_tasty"), tags.get("food_cold")), false)
                        .getResponse()
                        .getStatus())
                .as("three stars takes either")
                .isEqualTo(200);
        assertThat(submit(
                                order(),
                                1,
                                null,
                                List.of(
                                        tags.get("food_cold"),
                                        tags.get("food_wrong_item"),
                                        tags.get("service_rude"),
                                        tags.get("delivery_late"),
                                        tags.get("delivery_damaged"),
                                        tags.get("order_incomplete")),
                                false)
                        .getResponse()
                        .getStatus())
                .as("six tags")
                .isEqualTo(400);
        assertThat(submit(order(), 1, null, List.of(tags.get("food_cold"), tags.get("food_cold")), false)
                        .getResponse()
                        .getStatus())
                .as("the same tag twice")
                .isEqualTo(400);
        assertThat(submit(order(), 1, null, List.of(UUID.randomUUID().toString()), false)
                        .getResponse()
                        .getStatus())
                .as("no such tag")
                .isEqualTo(400);

        UUID foreign = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO reviews.feedback_tags (id, tenant_id, code, category, sentiment, created_by_subject, updated_by_subject)
                VALUES (:id, :t, 'food_cold', 'FOOD', 'NEGATIVE', 'test', 'test')
                """).param("id", foreign).param("t", OTHER_TENANT).update();
        assertThat(submit(order(), 1, null, List.of(foreign.toString()), false)
                        .getResponse()
                        .getStatus())
                .as("another tenant's tag")
                .isEqualTo(400);

        assertThat(jdbc.sql("SELECT count(*) FROM reviews.order_reviews")
                        .query(Long.class)
                        .single())
                .as("none of the refused submissions left a review behind")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a retired tag is no longer offered and no longer accepted, and stays on the review that carries it")
    void aRetiredTagStaysWhereItWas() throws Exception {
        switchOn("feature.feedback_settings");
        Map<String, String> tags = starterPack();
        JsonNode rated = JSON.readTree(submit(order(), 1, null, List.of(tags.get("food_cold")), false)
                .getResponse()
                .getContentAsString());
        assertThat(rated.path("tags").get(0).path("code").asText()).isEqualTo("food_cold");

        JsonNode library = json(get(settings() + "/feedback/tags").with(token(OWNER)));
        JsonNode cold = tagByCode(library.path("tags"), "food_cold");
        assertThat(cold.path("used").asBoolean()).isTrue();
        MvcResult retired = mvc.perform(put(settings() + "/feedback/tags/" + tags.get("food_cold"))
                        .with(token(OWNER))
                        .header("Idempotency-Key", key())
                        .header("If-Match", "W/\"" + cold.path("version").asInt() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"category\":\"FOOD\",\"sentiment\":\"NEGATIVE\",\"sortOrder\":%d,\"status\":\"RETIRED\"}"
                                        .formatted(cold.path("sortOrder").asInt())))
                .andReturn();
        assertThat(retired.getResponse().getStatus()).isEqualTo(200);

        assertThat(json(get(storefront() + "/feedback/config")).path("negative"))
                .extracting(tag -> tag.path("code").asText())
                .doesNotContain("food_cold");
        assertThat(submit(order(), 1, null, List.of(tags.get("food_cold")), false)
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);

        JsonNode mine = json(get(storefront() + "/reviews").with(guest()));
        assertThat(mine.path("items").get(0).path("tags").get(0).path("code").asText())
                .as("retired, never deleted: the review still says what its customer tapped")
                .isEqualTo("food_cold");
        assertThat(jdbc.sql("SELECT action_code FROM audit.audit_events WHERE action_code = 'reviews.tag.retire'")
                        .query(String.class)
                        .list())
                .hasSize(1);
    }

    @Test
    @DisplayName(
            "with the feedback settings off a review behaves as ADR 0071 built it: tags and the tick are ignored, nothing is offered")
    void withTheFlagOffNothingChanges() throws Exception {
        // Tags exist, because the pack was imported; the tenant has simply not switched the feature on.
        Map<String, String> tags = starterPack();

        JsonNode config = json(get(storefront() + "/feedback/config"));
        assertThat(config.path("enabled").asBoolean()).isFalse();
        assertThat(config.path("positive")).isEmpty();
        assertThat(config.path("negative")).isEmpty();

        MvcResult rated = submit(order(), 1, "cold", List.of(tags.get("food_cold")), true);
        assertThat(rated.getResponse().getStatus())
                .as("not an error: a rollback mid-session must not fail a submit")
                .isEqualTo(200);
        JsonNode review = JSON.readTree(rated.getResponse().getContentAsString());
        assertThat(review.path("tags")).isEmpty();
        assertThat(review.path("publicDisplay").path("state").asText()).isEqualTo("NOT_SHARED");
        assertThat(jdbc.sql("SELECT count(*) FROM customer.consent_decisions WHERE purpose = 'review.public_display'")
                        .query(Long.class)
                        .single())
                .as("a tick the feature did not offer is not a consent")
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM reviews.recovery_cases")
                        .query(Long.class)
                        .single())
                .as("and service recovery has its own flag, still off")
                .isZero();
        assertThat(json(get(storefront() + "/reviews/pending").with(guest()))).isEmpty();
    }

    // ================================================== the page, and the four gates

    @Test
    @DisplayName(
            "a comment reaches the public only after its author said yes and a moderator said yes, and leaves it the moment either changes")
    void theFourGates() throws Exception {
        switchOn("feature.feedback_settings");
        setBrandSetting("feedback.public_visibility", "stringValue", "\"RATING_AND_COMMENTS\"");
        Map<String, String> tags = starterPack();
        JsonNode config = json(get(storefront() + "/feedback/config"));
        assertThat(config.path("publicComments").asBoolean()).isTrue();
        assertThat(config.path("consentTextKey").asText()).isEqualTo("reviews.publicDisplay.consent");

        UUID tasty = review(5, "The plov was the best I have had", List.of(tags.get("food_tasty")), true);
        UUID unconsented = review(4, "Good, but please keep this to yourselves", List.of(), false);
        UUID late = review(2, "An hour late and nobody said sorry", List.of(tags.get("delivery_late")), true);

        assertThat(publicList().path("items")).as("nothing is approved yet").isEmpty();
        assertThat(statesOf())
                .containsEntry(tasty, "PENDING")
                .containsEntry(late, "PENDING")
                .containsEntry(unconsented, "NOT_SHARED");

        JsonNode queue = json(get(brandReviews() + "/moderation-queue").with(token(BRAND_MANAGER)));
        assertThat(queue.path("items"))
                .extracting(item -> item.path("reviewId").asText())
                .containsExactlyInAnyOrder(tasty.toString(), late.toString());
        assertThat(queue.path("counts").path("PENDING").asLong()).isEqualTo(2);
        assertThat(queue.path("items").get(0).path("comment").asText()).isNotBlank();
        assertThat(getAs(CHI_MANAGER, brandReviews() + "/moderation-queue")
                        .getResponse()
                        .getStatus())
                .as("a branch manager reads her branch's reviews and does not decide what the page shows")
                .isEqualTo(403);

        assertThat(decide(unconsented, "{\"to\":\"PUBLISHED\"}", 1)
                        .getResponse()
                        .getStatus())
                .as("words their author did not offer cannot be published by anyone")
                .isEqualTo(422);
        assertThat(decide(tasty, "{\"to\":\"HIDDEN\"}", 1).getResponse().getStatus())
                .as("hiding needs a reason")
                .isEqualTo(400);
        assertThat(decide(tasty, "{\"to\":\"HIDDEN\",\"reasonCode\":\"NEGATIVE\"}", 1)
                        .getResponse()
                        .getStatus())
                .as("\"negative\" is not one of the five")
                .isEqualTo(400);
        assertThat(decide(tasty, "{\"to\":\"HIDDEN\",\"reasonCode\":\"CUSTOMER_WITHDREW\"}", 1)
                        .getResponse()
                        .getStatus())
                .as("a system reason is not the moderator's to give")
                .isEqualTo(400);
        assertThat(decide(tasty, "{\"to\":\"PUBLISHED\",\"reasonCode\":\"SPAM_OR_UNRELATED\"}", 1)
                        .getResponse()
                        .getStatus())
                .as("publishing gives no reason")
                .isEqualTo(400);
        assertThat(decide(tasty, "{\"to\":\"PUBLISHED\"}", 7).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(mvc.perform(post(brandReviews() + "/" + tasty + "/moderation-decisions")
                                .with(token(BRAND_MANAGER))
                                .header("Idempotency-Key", key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"to\":\"PUBLISHED\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a decision needs the version the queue returned")
                .isEqualTo(400);

        MvcResult approved = decide(tasty, "{\"to\":\"PUBLISHED\"}", 1);
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        assertThat(approved.getResponse().getContentAsString())
                .as("a decision answers with a state, a code and a version, never the words")
                .doesNotContain("plov");

        JsonNode page = publicList();
        assertThat(page.path("items")).hasSize(1);
        JsonNode shown = page.path("items").get(0);
        assertThat(shown.path("comment").asText()).isEqualTo("The plov was the best I have had");
        assertThat(shown.path("rating").asInt()).isEqualTo(5);
        assertThat(new TreeSet<>(fieldNames(shown)))
                .as("the whole of what a stranger is told about a review")
                .containsExactly("branchName", "comment", "date", "rating", "tags");
        assertThat(shown.path("date").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(shown.path("branchName").asText()).isNotBlank();
        assertThat(shown.path("tags").get(0).path("code").asText()).isEqualTo("food_tasty");
        assertThat(page.toString())
                .as("no customer, no order, no review id, no time of day")
                .doesNotContain(OrderBoardFixtures.customerOf(TENANT).toString())
                .doesNotContain(tasty.toString())
                .doesNotContain("RV-")
                .doesNotContainPattern("\\d{2}:\\d{2}");
        assertThat(publicCache.holdsList(TENANT, BRAND))
                .as("a second read is the cache's")
                .isTrue();

        MvcResult hidden = decide(tasty, "{\"to\":\"HIDDEN\",\"reasonCode\":\"NAMES_AN_INDIVIDUAL\"}", 2);
        assertThat(hidden.getResponse().getStatus()).isEqualTo(200);
        assertThat(publicCache.holdsList(TENANT, BRAND))
                .as("the hide evicted the brand's list in its own request, after the commit")
                .isFalse();
        assertThat(publicList().path("items"))
                .as("so the very next read cannot return the comment")
                .isEmpty();

        assertThat(json(get(storefront() + "/reviews").with(guest())).path("items"))
                .filteredOn(item -> tasty.toString().equals(item.path("id").asText()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.path("publicDisplay").path("state").asText())
                            .isEqualTo("HIDDEN");
                    assertThat(item.path("publicDisplay").path("reasonCode").asText())
                            .isEqualTo("NAMES_AN_INDIVIDUAL");
                    assertThat(item.path("publicDisplay").path("withdrawable").asBoolean())
                            .isTrue();
                });

        assertThat(decide(tasty, "{\"to\":\"PUBLISHED\"}", 3).getResponse().getStatus())
                .as("a moderator may restore what a moderator hid")
                .isEqualTo(200);
        assertThat(publicList().path("items")).hasSize(1);

        assertThat(jdbc.sql("SELECT rating FROM reviews.order_reviews WHERE id = :id")
                        .param("id", tasty)
                        .query(Integer.class)
                        .single())
                .as("moderation moves words, never numbers")
                .isEqualTo(5);
        var audit = jdbc.sql("SELECT reason, change_document::text FROM audit.audit_events "
                        + "WHERE action_code = 'reviews.moderation.decide' ORDER BY recorded_at")
                .query()
                .listOfRows();
        assertThat(audit).hasSize(3);
        assertThat(audit.get(1).get("reason"))
                .as("the reason is the code's label, not typed text")
                .isEqualTo("Names an individual");
        assertThat(audit.toString()).doesNotContain("plov");
    }

    @Test
    @DisplayName(
            "the summary says nothing until the brand has its minimum, counts every review, and is the same number the metric would give")
    void theSummary() throws Exception {
        switchOn("feature.feedback_settings");
        assertThat(publicSummary().path("visibility").asText()).isEqualTo("NONE");
        assertThat(publicSummary().path("ready").asBoolean()).isFalse();

        setBrandSetting("feedback.public_visibility", "stringValue", "\"RATING_ONLY\"");
        setBrandSetting("feedback.public_minimum_reviews", "integerValue", "3");
        publicCache.evictNow(TENANT, BRAND);
        review(5, "great", List.of(), false);
        review(4, null, List.of(), false);
        JsonNode below = publicSummary();
        assertThat(below.path("ready").asBoolean()).isFalse();
        assertThat(below.path("count").isNull())
                .as("not even the count, below the minimum")
                .isTrue();
        assertThat(below.path("average").isNull()).isTrue();
        assertThat(below.path("distribution").isNull()).isTrue();

        UUID worst = review(1, "never again", List.of(), true);
        review(2, "meh", List.of(), false);
        // The summary is cached for five minutes; what is under test is the arithmetic, so start it fresh.
        jdbc.sql("SELECT 1").query().listOfRows();
        evictSummary();
        JsonNode ready = publicSummary();
        assertThat(ready.path("ready").asBoolean()).isTrue();
        assertThat(ready.path("count").asLong()).isEqualTo(4);
        assertThat(ready.path("average").decimalValue()).isEqualByComparingTo(new BigDecimal("3.0"));
        assertThat(ready.path("distribution")).extracting(JsonNode::asLong).containsExactly(1L, 1L, 0L, 1L, 1L);
        assertThat(PublicReviewService.averageOf(5 + 4 + 1 + 2, 4)).isEqualByComparingTo(new BigDecimal("3.0"));
        assertThat(PublicReviewService.averageOf(10, 3))
                .as("half-up to one decimal")
                .isEqualByComparingTo(new BigDecimal("3.3"));
        assertThat(PublicReviewService.averageOf(5 + 5 + 4, 3))
                .as("4.666... rounds up")
                .isEqualByComparingTo(new BigDecimal("4.7"));

        assertThat(publicList().path("items")).as("RATING_ONLY shows no words").isEmpty();

        // A comment's fate never moves the number: hide the worst one's words and read it again.
        setBrandSetting("feedback.public_visibility", "stringValue", "\"RATING_AND_COMMENTS\"");
        decide(worst, "{\"to\":\"PUBLISHED\"}", 1);
        decide(worst, "{\"to\":\"HIDDEN\",\"reasonCode\":\"ABUSIVE_LANGUAGE\"}", 2);
        evictSummary();
        assertThat(publicSummary().path("average").decimalValue())
                .as("a rating is not words: hiding the comment leaves the average where it was")
                .isEqualByComparingTo(new BigDecimal("3.0"));
        assertThat(publicSummary().path("count").asLong()).isEqualTo(4);
    }

    // ============================================================ taking words back

    @Test
    @DisplayName(
            "a customer takes her words off the page: hidden at once, a WITHDRAWN decision recorded, no moderator can put them back")
    void withdrawal() throws Exception {
        switchOn("feature.feedback_settings");
        setBrandSetting("feedback.public_visibility", "stringValue", "\"RATING_AND_COMMENTS\"");
        UUID shared = review(5, "Please tell everyone about the plov", List.of(), true);
        UUID kept = review(3, "Private thoughts", List.of(), false);
        decide(shared, "{\"to\":\"PUBLISHED\"}", 1);
        assertThat(publicList().path("items")).hasSize(1);
        assertThat(publicCache.holdsList(TENANT, BRAND)).isTrue();

        MvcResult withdrawn = postAs(GUEST, storefront() + "/reviews/" + shared + "/public-display/withdrawal", null);
        assertThat(withdrawn.getResponse().getStatus()).isEqualTo(200);
        JsonNode state = JSON.readTree(withdrawn.getResponse().getContentAsString());
        assertThat(state.path("state").asText()).isEqualTo("HIDDEN");
        assertThat(state.path("reasonCode").asText()).isEqualTo("CUSTOMER_WITHDREW");
        assertThat(state.path("withdrawable").asBoolean()).isFalse();
        assertThat(publicCache.holdsList(TENANT, BRAND))
                .as("evicted in the same request")
                .isFalse();
        assertThat(publicList().path("items"))
                .as("the next read cannot return it")
                .isEmpty();

        var decisions = jdbc.sql("SELECT decision FROM customer.consent_decisions "
                        + "WHERE purpose = 'review.public_display' ORDER BY decided_at, decision")
                .query(String.class)
                .list();
        assertThat(decisions).containsExactly("GRANTED", "WITHDRAWN");
        var row = jdbc.sql("SELECT public_consent, comment_state, state_reason_code, decided_by_actor_type, "
                        + "withdrawal_decision_id IS NOT NULL AS has_withdrawal FROM reviews.review_publications "
                        + "WHERE review_id = :id")
                .param("id", shared)
                .query()
                .singleRow();
        assertThat(row)
                .containsEntry("public_consent", true)
                .containsEntry("comment_state", "HIDDEN")
                .containsEntry("state_reason_code", "CUSTOMER_WITHDREW")
                .containsEntry("decided_by_actor_type", "CUSTOMER")
                .containsEntry("has_withdrawal", true);

        assertThat(decide(shared, "{\"to\":\"PUBLISHED\"}", 3).getResponse().getStatus())
                .as("what the customer took back, no one gives back")
                .isEqualTo(422);
        assertThat(postAs(GUEST, storefront() + "/reviews/" + shared + "/public-display/withdrawal", null)
                        .getResponse()
                        .getStatus())
                .as("already off the page")
                .isEqualTo(422);
        assertThat(postAs(GUEST, storefront() + "/reviews/" + kept + "/public-display/withdrawal", null)
                        .getResponse()
                        .getStatus())
                .as("never shared, so nothing to withdraw")
                .isEqualTo(422);
        assertThat(postAs(GUEST, storefront() + "/reviews/" + UUID.randomUUID() + "/public-display/withdrawal", null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);

        var ops = json(get(brandReviews()).with(token(BRAND_MANAGER)));
        assertThat(ops.path("items"))
                .filteredOn(item -> shared.toString().equals(item.path("id").asText()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.path("comment").asText())
                            .as("the restaurant's own staff still read it: ADR 0071's no-withdrawal stands for them")
                            .isEqualTo("Please tell everyone about the plov");
                    assertThat(item.path("rating").asInt()).isEqualTo(5);
                });
    }

    @Test
    @DisplayName(
            "an erasure nulls the comment and the case notes, hides the public comment, evicts the page, and keeps the rating and the average")
    void erasure() throws Exception {
        switchOn("feature.feedback_settings");
        switchOn("feature.service_recovery");
        setBrandSetting("feedback.public_visibility", "stringValue", "\"RATING_AND_COMMENTS\"");
        setBrandSetting("feedback.public_minimum_reviews", "integerValue", "1");
        UUID shared = review(1, "My address is on the box, it was a mess", List.of(), true);
        decide(shared, "{\"to\":\"PUBLISHED\"}", 1);
        UUID caseId = jdbc.sql("SELECT id FROM reviews.recovery_cases WHERE review_id = :r")
                .param("r", shared)
                .query(UUID.class)
                .single();
        MvcResult note = postAs(
                CHI_MANAGER,
                "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + CHILONZOR
                        + "/recovery-cases/" + caseId + "/notes",
                "{\"note\":\"Phoned her on 998901234567\"}");
        assertThat(note.getResponse().getStatus()).isEqualTo(201);
        assertThat(publicList().path("items")).hasSize(1);
        evictSummary();
        BigDecimal before = publicSummary().path("average").decimalValue();

        transactions.executeWithoutResult(status -> erasure.erase(TENANT, OrderBoardFixtures.customerOf(TENANT)));

        assertThat(jdbc.sql("SELECT comment_protected FROM reviews.order_reviews WHERE id = :id")
                        .param("id", shared)
                        .query(String.class)
                        .optional())
                .as("the words are gone")
                .isEmpty();
        assertThat(jdbc.sql("SELECT rating FROM reviews.order_reviews WHERE id = :id")
                        .param("id", shared)
                        .query(Integer.class)
                        .single())
                .as("the number stays: no average moves")
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT note_protected FROM reviews.recovery_case_events WHERE kind = 'NOTE'")
                        .query(String.class)
                        .optional())
                .as("and so are the case notes")
                .isEmpty();
        assertThat(jdbc.sql(
                                "SELECT comment_state || ':' || state_reason_code FROM reviews.review_publications WHERE review_id = :id")
                        .param("id", shared)
                        .query(String.class)
                        .single())
                .isEqualTo("HIDDEN:CUSTOMER_ERASED");
        assertThat(publicCache.holdsList(TENANT, BRAND))
                .as("the page was evicted after the commit")
                .isFalse();
        assertThat(publicList().path("items")).isEmpty();
        evictSummary();
        assertThat(publicSummary().path("average").decimalValue()).isEqualByComparingTo(before);
        assertThat(decide(shared, "{\"to\":\"PUBLISHED\"}", 3).getResponse().getStatus())
                .isEqualTo(422);
        assertThat(jdbc.sql("SELECT payload::text FROM integration.outbox_events "
                                + "WHERE event_type = 'ReviewCommentModerated' ORDER BY created_at DESC LIMIT 1")
                        .query(String.class)
                        .single())
                .contains("CUSTOMER_ERASED")
                .contains("SYSTEM");

        transactions.executeWithoutResult(status -> erasure.erase(TENANT, OrderBoardFixtures.customerOf(TENANT)));
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM integration.outbox_events WHERE event_type = 'ReviewCommentModerated' "
                                        + "AND payload::text LIKE '%CUSTOMER_ERASED%'")
                        .query(Long.class)
                        .single())
                .as("a retried erasure changes and announces nothing more")
                .isEqualTo(1L);
    }

    // ================================================================== the prompt

    @Test
    @DisplayName(
            "the storefront's own prompt lists completed, unreviewed orders inside the policy's window, and only while the feature is on")
    void thePendingPrompt() throws Exception {
        UUID recent = completedOrderAt(Instant.now().minus(Duration.ofDays(2)));
        UUID old = completedOrderAt(Instant.now().minus(Duration.ofDays(9)));
        assertThat(json(get(storefront() + "/reviews/pending").with(guest())))
                .as("feature off")
                .isEmpty();

        switchOn("feature.feedback_settings");
        JsonNode pending = json(get(storefront() + "/reviews/pending").with(guest()));
        assertThat(pending).extracting(item -> item.path("orderId").asText()).containsExactly(recent.toString());
        assertThat(old).isNotNull();

        assertThat(submit(recent, 5, null, List.of(), false).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(json(get(storefront() + "/reviews/pending").with(guest())))
                .as("rated: no longer asked")
                .isEmpty();

        UUID tomorrow = completedOrderAt(Instant.now().minus(Duration.ofHours(30)));
        assertThat(json(get(storefront() + "/reviews/pending").with(guest())))
                .extracting(item -> item.path("orderId").asText())
                .contains(tomorrow.toString());
        MvcResult narrowed = mvc.perform(put(settings() + "/brands/" + BRAND + "/feedback/prompt-policy")
                        .with(token(OWNER))
                        .header("Idempotency-Key", key())
                        .header("If-Match", "W/\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inlineWindowDays\":1,\"rows\":[],\"reason\":\"ask within a day\"}"))
                .andReturn();
        assertThat(narrowed.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(get(storefront() + "/reviews/pending").with(guest())))
                .as("the brand's own window is a day, and this order is thirty hours old")
                .isEmpty();
    }

    @Test
    @DisplayName("the anonymous reads are rate limited per caller")
    void thePublicReadsAreRateLimited() throws Exception {
        switchOn("feature.feedback_settings");
        setBrandSetting("feedback.public_visibility", "stringValue", "\"RATING_ONLY\"");
        int limited = 0;
        for (int call = 0; call < 130; call++) {
            MvcResult result =
                    mvc.perform(get(storefront() + "/reviews/public/summary")).andReturn();
            if (result.getResponse().getStatus() == 429) {
                limited++;
                assertThat(result.getResponse().getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
            }
        }
        assertThat(limited)
                .as("120 a minute, so the last few of 130 are refused")
                .isGreaterThanOrEqualTo(5);
    }

    // ===================================================================== the settings

    @Test
    @DisplayName("the tag library: a tag is created once with its default-language wording, an icon must be a real"
            + " image, an edit keeps the locales it did not show, and a used tag is frozen")
    void theTagLibraryRules() throws Exception {
        JsonNode empty = json(get(settings() + "/feedback/tags").with(token(OWNER)));
        assertThat(empty.path("tags")).isEmpty();
        assertThat(empty.path("maximumActiveTags").asInt()).isEqualTo(40);
        assertThat(empty.path("iconAspectRatio").asText()).isEqualTo("3:2");
        assertThat(empty.path("iconMaximumBytes").asLong()).isEqualTo(1_048_576L);
        String defaultLocale = empty.path("defaultLocale").asText();
        assertThat(defaultLocale).isNotBlank();
        assertThat(empty.path("locales")).extracting(JsonNode::asText).contains(defaultLocale);

        String template =
                "{\"code\":\"%s\",\"category\":\"FOOD\",\"sentiment\":\"NEGATIVE\",\"labels\":{\"%s\":\"%s\"}}";
        MvcResult created = postAs(
                OWNER, settings() + "/feedback/tags", template.formatted("food_greasy", defaultLocale, "Too greasy"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getHeader("ETag")).isNotBlank();
        JsonNode tag = JSON.readTree(created.getResponse().getContentAsString());
        assertThat(tag.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(tag.path("used").asBoolean()).isFalse();
        assertThat(tag.path("sortOrder").asInt())
                .as("absent means the end of the list")
                .isEqualTo(100);
        assertThat(tag.path("labels").path(defaultLocale).asText()).isEqualTo("Too greasy");

        assertThat(postAs(
                                OWNER,
                                settings() + "/feedback/tags",
                                template.formatted("food_greasy", defaultLocale, "Again"))
                        .getResponse()
                        .getStatus())
                .as("a duplicate code")
                .isEqualTo(409);
        for (String badCode : List.of("Food Greasy", "1_greasy", "x", "food-greasy", "A".repeat(49))) {
            assertThat(postAs(OWNER, settings() + "/feedback/tags", template.formatted(badCode, defaultLocale, "x"))
                            .getResponse()
                            .getStatus())
                    .as("code %s", badCode)
                    .isEqualTo(400);
        }
        String otherLocale = "ru".equals(defaultLocale) ? "en" : "ru";
        assertThat(postAs(
                                OWNER,
                                settings() + "/feedback/tags",
                                template.formatted("food_salty", otherLocale, "Too salty"))
                        .getResponse()
                        .getStatus())
                .as("a tag needs its wording in the tenant's default language")
                .isEqualTo(400);
        assertThat(postAs(OWNER, settings() + "/feedback/tags", template.formatted("food_salty", defaultLocale, " "))
                        .getResponse()
                        .getStatus())
                .as("a blank wording is no wording")
                .isEqualTo(400);
        MvcResult iconless = postAs(
                OWNER,
                settings() + "/feedback/tags",
                "{\"code\":\"food_bland\",\"category\":\"FOOD\",\"sentiment\":\"NEGATIVE\",\"iconAssetId\":\"%s\",\"labels\":{\"%s\":\"Bland\"}}"
                        .formatted(UUID.randomUUID(), defaultLocale));
        assertThat(iconless.getResponse().getStatus())
                .as("an icon that is no image of this tenant's")
                .isEqualTo(400);
        assertThat(iconless.getResponse().getContentAsString()).contains("no such image");
        assertThat(postAs(OWNER, settings() + "/feedback/tags", "{\"code\":\"x_y\",\"labels\":{}}")
                        .getResponse()
                        .getStatus())
                .as("category and sentiment are required")
                .isEqualTo(400);

        Map<String, String> pack = starterPack();
        JsonNode cold = tagByCode(
                json(get(settings() + "/feedback/tags").with(token(OWNER))).path("tags"), "food_cold");
        String body =
                "{\"category\":\"FOOD\",\"sentiment\":\"NEGATIVE\",\"sortOrder\":%d,\"status\":\"ACTIVE\",\"labels\":{\"en\":\"Served cold\"}}"
                        .formatted(cold.path("sortOrder").asInt());
        MvcResult edited = putAs(
                OWNER,
                settings() + "/feedback/tags/" + pack.get("food_cold"),
                body,
                cold.path("version").asInt());
        assertThat(edited.getResponse().getStatus()).isEqualTo(200);
        JsonNode after = JSON.readTree(edited.getResponse().getContentAsString());
        assertThat(after.path("labels").path("en").asText()).isEqualTo("Served cold");
        assertThat(after.path("labels").path("ru").asText())
                .as("a locale the editor did not show keeps its wording")
                .isEqualTo(cold.path("labels").path("ru").asText())
                .isNotBlank();
        assertThat(after.path("version").asInt()).isEqualTo(cold.path("version").asInt() + 1);

        assertThat(putAs(
                                OWNER,
                                settings() + "/feedback/tags/" + pack.get("food_cold"),
                                body,
                                cold.path("version").asInt())
                        .getResponse()
                        .getStatus())
                .as("the version the list returned is spent")
                .isEqualTo(409);
        assertThat(putAs(OWNER, settings() + "/feedback/tags/" + pack.get("food_cold"), body, null)
                        .getResponse()
                        .getStatus())
                .as("no version, no edit")
                .isIn(400, 428);
        assertThat(putAs(OWNER, settings() + "/feedback/tags/" + UUID.randomUUID(), body, 1)
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);

        // A review carries the tag: its category and sentiment are now what reports group by.
        switchOn("feature.feedback_settings");
        assertThat(submit(order(), 1, null, List.of(pack.get("food_cold")), false)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        JsonNode used = tagByCode(
                json(get(settings() + "/feedback/tags").with(token(OWNER))).path("tags"), "food_cold");
        assertThat(used.path("used").asBoolean()).isTrue();
        String recategorised =
                "{\"category\":\"SERVICE\",\"sentiment\":\"NEGATIVE\",\"sortOrder\":%d,\"status\":\"ACTIVE\"}"
                        .formatted(used.path("sortOrder").asInt());
        assertThat(putAs(
                                OWNER,
                                settings() + "/feedback/tags/" + pack.get("food_cold"),
                                recategorised,
                                used.path("version").asInt())
                        .getResponse()
                        .getStatus())
                .as("a tag a review carries cannot change what a report groups by")
                .isEqualTo(422);
        String reworded =
                "{\"category\":\"FOOD\",\"sentiment\":\"NEGATIVE\",\"sortOrder\":%d,\"status\":\"ACTIVE\",\"labels\":{\"ru\":\"Остыло\"}}"
                        .formatted(used.path("sortOrder").asInt());
        assertThat(putAs(
                                OWNER,
                                settings() + "/feedback/tags/" + pack.get("food_cold"),
                                reworded,
                                used.path("version").asInt())
                        .getResponse()
                        .getStatus())
                .as("its wording is the tenant's to change at any time")
                .isEqualTo(200);

        assertThat(auditActions()).contains("reviews.tag.create", "reviews.tag.update");
        assertNothingRedacted("reviews.tag.create", "reviews.tag.update");
    }

    @Test
    @DisplayName("the library holds forty active tags: the forty-first is refused, retiring one makes room, and"
            + " bringing a retired one back past forty is refused too")
    void theLibraryHoldsFortyActiveTags() throws Exception {
        String defaultLocale = json(get(settings() + "/feedback/tags").with(token(OWNER)))
                .path("defaultLocale")
                .asText();
        starterPack();
        String template =
                "{\"code\":\"%s\",\"category\":\"OTHER\",\"sentiment\":\"POSITIVE\",\"labels\":{\"%s\":\"Tag %s\"}}";
        for (int i = 0; i < 28; i++) {
            String code = "extra_tag_" + i;
            assertThat(postAs(OWNER, settings() + "/feedback/tags", template.formatted(code, defaultLocale, i))
                            .getResponse()
                            .getStatus())
                    .as(code)
                    .isEqualTo(201);
        }
        MvcResult forty1 =
                postAs(OWNER, settings() + "/feedback/tags", template.formatted("one_too_many", defaultLocale, "41"));
        assertThat(forty1.getResponse().getStatus()).isEqualTo(422);
        assertThat(forty1.getResponse().getContentAsString()).contains("40");

        JsonNode library = json(get(settings() + "/feedback/tags").with(token(OWNER)));
        assertThat(library.path("tags")).hasSize(40);
        JsonNode first = tagByCode(library.path("tags"), "extra_tag_0");
        String retire = "{\"category\":\"OTHER\",\"sentiment\":\"POSITIVE\",\"sortOrder\":%d,\"status\":\"%s\"}";
        MvcResult retired = putAs(
                OWNER,
                settings() + "/feedback/tags/" + first.path("id").asText(),
                retire.formatted(first.path("sortOrder").asInt(), "RETIRED"),
                first.path("version").asInt());
        assertThat(retired.getResponse().getStatus()).isEqualTo(200);

        assertThat(postAs(OWNER, settings() + "/feedback/tags", template.formatted("one_too_many", defaultLocale, "41"))
                        .getResponse()
                        .getStatus())
                .as("a retired tag frees its place")
                .isEqualTo(201);
        JsonNode back = JSON.readTree(retired.getResponse().getContentAsString());
        assertThat(putAs(
                                OWNER,
                                settings() + "/feedback/tags/"
                                        + first.path("id").asText(),
                                retire.formatted(first.path("sortOrder").asInt(), "ACTIVE"),
                                back.path("version").asInt())
                        .getResponse()
                        .getStatus())
                .as("and bringing it back over the limit is refused")
                .isEqualTo(422);
        assertThat(postAs(OWNER, settings() + "/feedback/tags/starter-pack", null)
                        .getResponse()
                        .getStatus())
                .as("the starter pack is imported once")
                .isEqualTo(409);
    }

    @Test
    @DisplayName(
            "the prompt policy: the default is stated, a save is checked against the version read, a brand may override"
                    + " the tenant, and every bound is enforced where it is written")
    void thePromptPolicyRoundTrip() throws Exception {
        JsonNode defaults = json(get(settings() + "/feedback/prompt-policy").with(token(OWNER)));
        assertThat(defaults.path("isDefault").asBoolean()).isTrue();
        assertThat(defaults.path("winningScope").isNull()).isTrue();
        assertThat(defaults.path("versionAtScope").asInt()).isZero();
        assertThat(defaults.path("inlineWindowDays").asInt()).isEqualTo(7);
        assertThat(defaults.path("sendWindow").path("start").asText()).isEqualTo("09:00");
        assertThat(defaults.path("sendWindow").path("end").asText()).isEqualTo("21:00");
        assertThat(defaults.path("rows")).hasSize(4);
        assertThat(defaults.path("rows"))
                .filteredOn(row -> "TELEGRAM".equals(row.path("channel").asText()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.path("enabled").asBoolean()).isTrue();
                    assertThat(row.path("delayMinutes").asInt()).isEqualTo(30);
                    assertThat(row.path("giveUpAfterHours").asInt()).isEqualTo(6);
                });
        assertThat(defaults.path("rows"))
                .filteredOn(row -> !"TELEGRAM".equals(row.path("channel").asText()))
                .allSatisfy(row -> assertThat(row.path("enabled").asBoolean())
                        .as("SMS, email and push are off until a tenant enables them")
                        .isFalse());

        String valid = promptPolicy(
                "{\"start\":\"08:00\",\"end\":\"22:00\"}",
                14,
                "{\"fulfillmentMode\":\"DELIVERY\",\"channel\":\"SMS\",\"enabled\":true,\"delayMinutes\":120,\"giveUpAfterHours\":12},"
                        + "{\"fulfillmentMode\":\"*\",\"channel\":\"TELEGRAM\",\"enabled\":true,\"delayMinutes\":0,\"giveUpAfterHours\":1}",
                "start with delivery SMS");
        assertThat(putAs(OWNER, settings() + "/feedback/prompt-policy", valid, null)
                        .getResponse()
                        .getStatus())
                .as("no If-Match, no save")
                .isIn(400, 428);
        MvcResult saved = putAs(OWNER, settings() + "/feedback/prompt-policy", valid, 0);
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        JsonNode policy = JSON.readTree(saved.getResponse().getContentAsString());
        assertThat(policy.path("isDefault").asBoolean()).isFalse();
        assertThat(policy.path("winningScope").asText()).isEqualTo("TENANT");
        assertThat(policy.path("versionAtScope").asInt()).isEqualTo(1);
        assertThat(policy.path("inlineWindowDays").asInt()).isEqualTo(14);
        assertThat(putAs(OWNER, settings() + "/feedback/prompt-policy", valid, 0)
                        .getResponse()
                        .getStatus())
                .as("the version read is spent")
                .isEqualTo(409);

        JsonNode brandBefore = json(
                get(settings() + "/brands/" + BRAND + "/feedback/prompt-policy").with(token(OWNER)));
        assertThat(brandBefore.path("winningScope").asText())
                .as("the brand follows the tenant")
                .isEqualTo("TENANT");
        assertThat(brandBefore.path("versionAtScope").asInt()).isZero();
        assertThat(brandBefore.path("inlineWindowDays").asInt()).isEqualTo(14);
        MvcResult brandSaved = putAs(
                OWNER,
                settings() + "/brands/" + BRAND + "/feedback/prompt-policy",
                promptPolicy("null", 3, "", "ask within three days"),
                0);
        assertThat(brandSaved.getResponse().getStatus()).isEqualTo(200);
        JsonNode brandPolicy = JSON.readTree(brandSaved.getResponse().getContentAsString());
        assertThat(brandPolicy.path("winningScope").asText()).isEqualTo("BRAND");
        assertThat(brandPolicy.path("sendWindow").isNull())
                .as("null means any hour")
                .isTrue();
        assertThat(json(get(settings() + "/feedback/prompt-policy").with(token(OWNER)))
                        .path("inlineWindowDays")
                        .asInt())
                .as("the tenant's own document is untouched by the brand's")
                .isEqualTo(14);

        String row =
                "{\"fulfillmentMode\":\"*\",\"channel\":\"TELEGRAM\",\"enabled\":true,\"delayMinutes\":%d,\"giveUpAfterHours\":%d}";
        List<String> refused = List.of(
                promptPolicy("null", 7, row.formatted(1441, 6), "delay over a day"),
                promptPolicy("null", 7, row.formatted(-1, 6), "negative delay"),
                promptPolicy("null", 7, row.formatted(30, 0), "give up at once"),
                promptPolicy("null", 7, row.formatted(30, 73), "give up after three days"),
                promptPolicy("null", 7, row.formatted(30, 6).replace("TELEGRAM", "FAX"), "an unknown channel"),
                promptPolicy(
                        "null",
                        7,
                        row.formatted(30, 6).replace("\"*\",\"channel\"", "\"TAKEAWAY\",\"channel\""),
                        "an unknown order type"),
                promptPolicy(
                        "{\"start\":\"21:00\",\"end\":\"09:00\"}",
                        7,
                        row.formatted(30, 6),
                        "a window that ends before it starts"),
                promptPolicy(
                        "{\"start\":\"9am\",\"end\":\"9pm\"}", 7, row.formatted(30, 6), "a window nobody can parse"),
                promptPolicy("null", 0, row.formatted(30, 6), "no inline days"),
                promptPolicy("null", 91, row.formatted(30, 6), "a quarter and a day"),
                promptPolicy(
                        "null",
                        7,
                        row.formatted(30, 6) + "," + row.formatted(30, 6).replace("TELEGRAM", "SMS"),
                        "two different rows are fine"));
        // The last document is valid (two different rows); the others are not.
        for (int i = 0; i < refused.size() - 1; i++) {
            assertThat(putAs(OWNER, settings() + "/feedback/prompt-policy", refused.get(i), 1)
                            .getResponse()
                            .getStatus())
                    .as("document %d", i)
                    .isEqualTo(400);
        }
        assertThat(putAs(OWNER, settings() + "/feedback/prompt-policy", refused.getLast(), 1)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(putAs(
                                OWNER,
                                settings() + "/feedback/prompt-policy",
                                promptPolicy("null", 7, row.formatted(30, 6), " "),
                                2)
                        .getResponse()
                        .getStatus())
                .as("a policy change needs a reason")
                .isEqualTo(400);

        assertThat(auditActions()).contains("reviews.prompt-policy.authored");
        assertNothingRedacted("reviews.prompt-policy.authored");
    }

    @Test
    @DisplayName("the recovery policy: the default is 2 stars, 240 minutes and 72 hours; an edit moves new cases and"
            + " never an old case's clocks")
    void theRecoveryPolicyRoundTrip() throws Exception {
        switchOn("feature.feedback_settings");
        switchOn("feature.service_recovery");
        JsonNode defaults = json(get(settings() + "/feedback/recovery-policy").with(token(OWNER)));
        assertThat(defaults.path("isDefault").asBoolean()).isTrue();
        assertThat(defaults.path("autoOpenMaxRating").asInt()).isEqualTo(2);
        assertThat(defaults.path("firstResponseMinutes").asInt()).isEqualTo(240);
        assertThat(defaults.path("resolutionMinutes").asInt()).isEqualTo(4320);
        assertThat(defaults.path("alertOnOpen").asBoolean()).isFalse();
        assertThat(defaults.path("alertOnBreach").asBoolean()).isFalse();

        UUID old = review(1, "cold", List.of(), false);
        assertThat(caseWindowMinutes(old)).containsExactly(240L, 4320L);

        String valid = recoveryPolicy(3, 60, 1440, true, false, "tighten for the pilot");
        assertThat(putAs(OWNER, settings() + "/feedback/recovery-policy", valid, null)
                        .getResponse()
                        .getStatus())
                .isIn(400, 428);
        MvcResult saved = putAs(OWNER, settings() + "/feedback/recovery-policy", valid, 0);
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        JsonNode policy = JSON.readTree(saved.getResponse().getContentAsString());
        assertThat(policy.path("winningScope").asText()).isEqualTo("TENANT");
        assertThat(policy.path("versionAtScope").asInt()).isEqualTo(1);
        assertThat(policy.path("alertOnOpen").asBoolean()).isTrue();
        assertThat(putAs(OWNER, settings() + "/feedback/recovery-policy", valid, 0)
                        .getResponse()
                        .getStatus())
                .isEqualTo(409);

        assertThat(caseWindowMinutes(old))
                .as("a case stores the version it opened under: an edit never makes an old case late")
                .containsExactly(240L, 4320L);
        UUID three = review(3, "so-so", List.of(), false);
        assertThat(caseWindowMinutes(three))
                .as("three stars now opens a case, on the new clocks")
                .containsExactly(60L, 1440L);
        assertThat(jdbc.sql("SELECT policy_version FROM reviews.recovery_cases WHERE review_id = :id")
                        .param("id", three)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        UUID four = review(4, "fine", List.of(), false);
        assertThat(jdbc.sql("SELECT count(*) FROM reviews.recovery_cases WHERE review_id = :id")
                        .param("id", four)
                        .query(Long.class)
                        .single())
                .as("four stars is above the new threshold")
                .isZero();

        List<String> refused = List.of(
                recoveryPolicy(6, 60, 1440, false, false, "x"),
                recoveryPolicy(-1, 60, 1440, false, false, "x"),
                recoveryPolicy(2, 14, 1440, false, false, "first response under a quarter hour"),
                recoveryPolicy(2, 10_081, 43_200, false, false, "first response over a week"),
                recoveryPolicy(2, 60, 59, false, false, "resolution under an hour"),
                recoveryPolicy(2, 60, 43_201, false, false, "resolution over thirty days"),
                recoveryPolicy(2, 600, 500, false, false, "resolution shorter than the first response"));
        for (int i = 0; i < refused.size(); i++) {
            assertThat(putAs(OWNER, settings() + "/feedback/recovery-policy", refused.get(i), 1)
                            .getResponse()
                            .getStatus())
                    .as("document %d", i)
                    .isEqualTo(400);
        }
        assertThat(putAs(
                                OWNER,
                                settings() + "/feedback/recovery-policy",
                                "{\"autoOpenMaxRating\":2,\"firstResponseMinutes\":60,\"resolutionMinutes\":1440}",
                                1)
                        .getResponse()
                        .getStatus())
                .as("a reason is required")
                .isEqualTo(400);

        MvcResult never = putAs(
                OWNER,
                settings() + "/brands/" + BRAND + "/feedback/recovery-policy",
                recoveryPolicy(0, 60, 1440, false, false, "this brand does not run a worklist"),
                0);
        assertThat(never.getResponse().getStatus()).isEqualTo(200);
        UUID one = review(1, "terrible", List.of(), false);
        assertThat(jdbc.sql("SELECT count(*) FROM reviews.recovery_cases WHERE review_id = :id")
                        .param("id", one)
                        .query(Long.class)
                        .single())
                .as("0 means never: the brand's own policy overrides the tenant's")
                .isZero();

        assertThat(auditActions()).contains("reviews.recovery-policy.authored");
        assertNothingRedacted("reviews.recovery-policy.authored");
        assertThat(jdbc.sql("SELECT change_document::text FROM audit.audit_events WHERE action_code = "
                                + "'reviews.recovery-policy.authored' ORDER BY recorded_at LIMIT 1")
                        .query(String.class)
                        .single())
                .as("the audit key names stars, because a key containing the letters of 'rating' is redacted")
                .contains("autoOpenMaxStars");
    }

    @Test
    @DisplayName("the settings are the owner's and the administrator's: a brand manager, a branch manager, a customer,"
            + " another tenant's owner and a caller with no token all get nothing")
    void theSettingsReach() throws Exception {
        List<String> reads = List.of(
                settings() + "/feedback/tags",
                settings() + "/feedback/prompt-policy",
                settings() + "/feedback/recovery-policy",
                settings() + "/brands/" + BRAND + "/feedback/prompt-policy",
                settings() + "/brands/" + BRAND + "/feedback/recovery-policy");
        for (String path : reads) {
            assertThat(getAs(OWNER, path).getResponse().getStatus())
                    .as("owner %s", path)
                    .isEqualTo(200);
            assertThat(getAs(BRAND_MANAGER, path).getResponse().getStatus())
                    .as("brand manager %s", path)
                    .isEqualTo(403);
            assertThat(getAs(CHI_MANAGER, path).getResponse().getStatus())
                    .as("branch manager %s", path)
                    .isEqualTo(403);
            assertThat(getAs(OTHER_OWNER, path).getResponse().getStatus())
                    .as("another tenant's owner %s", path)
                    .isIn(403, 404);
            assertThat(mvc.perform(get(path).with(guest()))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as("a customer %s", path)
                    .isIn(401, 403);
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus())
                    .as("no token %s", path)
                    .isEqualTo(401);
        }
        String policy = recoveryPolicy(2, 240, 4320, false, false, "no");
        assertThat(putAs(BRAND_MANAGER, settings() + "/feedback/recovery-policy", policy, 0)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(putAs(BRAND_MANAGER, settings() + "/brands/" + BRAND + "/feedback/recovery-policy", policy, 0)
                        .getResponse()
                        .getStatus())
                .as("not even for the brand they manage: configuration is the owner's")
                .isEqualTo(403);
        assertThat(postAs(BRAND_MANAGER, settings() + "/feedback/tags/starter-pack", null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(putAs(OTHER_OWNER, settings() + "/feedback/recovery-policy", policy, 0)
                        .getResponse()
                        .getStatus())
                .isIn(403, 404);
        assertThat(jdbc.sql("SELECT count(*) FROM reviews.feedback_tags")
                        .query(Long.class)
                        .single())
                .as("nothing was written by anyone refused")
                .isZero();
        assertThat(auditActions()).isEmpty();
    }

    // ===================================================================== fixtures

    /** A completed order of the guest's, a review of it, and the id of the review. */
    private UUID review(int rating, @Nullable String comment, List<String> tagIds, boolean consent) throws Exception {
        MvcResult result = submit(order(), rating, comment, tagIds, consent);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
                .path("id")
                .asText());
    }

    private UUID order() {
        return completedOrderAt(Instant.now().minus(Duration.ofHours(2)));
    }

    private UUID completedOrderAt(Instant createdAt) {
        return fixtures.insertOrder(OrderBoardFixtures.order("RV-" + (++orders))
                .at(TENANT, BRAND, CHILONZOR)
                .createdAt(createdAt)
                .status("COMPLETED"));
    }

    private MvcResult submit(UUID orderId, int rating, @Nullable String comment, List<String> tagIds, boolean consent)
            throws Exception {
        StringBuilder body = new StringBuilder("{\"rating\":").append(rating);
        if (comment != null) {
            body.append(",\"comment\":\"").append(comment).append('"');
        }
        if (!tagIds.isEmpty()) {
            body.append(",\"tagIds\":[");
            for (int i = 0; i < tagIds.size(); i++) {
                body.append(i == 0 ? "" : ",").append('"').append(tagIds.get(i)).append('"');
            }
            body.append(']');
        }
        if (consent) {
            body.append(",\"publicConsent\":true");
        }
        body.append('}');
        return mvc.perform(post(storefront() + "/orders/" + orderId + "/review")
                        .with(guest())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andReturn();
    }

    private MvcResult decide(UUID reviewId, String body, int version) throws Exception {
        return mvc.perform(post(brandReviews() + "/" + reviewId + "/moderation-decisions")
                        .with(token(BRAND_MANAGER))
                        .header("Idempotency-Key", key())
                        .header("If-Match", "W/\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private Map<String, String> starterPack() throws Exception {
        JsonNode pack = json(post(settings() + "/feedback/tags/starter-pack")
                .with(token(OWNER))
                .header("Idempotency-Key", key()));
        Map<String, String> ids = new LinkedHashMap<>();
        pack.forEach(tag -> ids.put(tag.path("code").asText(), tag.path("id").asText()));
        assertThat(ids).hasSize(12);
        return ids;
    }

    private static JsonNode tagByCode(JsonNode tags, String code) {
        for (JsonNode tag : tags) {
            if (code.equals(tag.path("code").asText())) {
                return tag;
            }
        }
        throw new AssertionError("no tag " + code);
    }

    private Map<UUID, String> statesOf() {
        Map<UUID, String> states = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.sql("SELECT review_id, comment_state FROM reviews.review_publications")
                .query()
                .listOfRows()) {
            states.put((UUID) Objects.requireNonNull(row.get("review_id")), String.valueOf(row.get("comment_state")));
        }
        return states;
    }

    private JsonNode publicList() throws Exception {
        return json(get(storefront() + "/reviews/public"));
    }

    private JsonNode publicSummary() throws Exception {
        return json(get(storefront() + "/reviews/public/summary"));
    }

    /** The summary is a five-minute cache of counts; the arithmetic is what a test of it wants, from scratch. */
    private void evictSummary() {
        publicCache.evictNow(TENANT, BRAND);
        // The summary cache has no eviction by design (the TTL is its whole budget), so a test that wants a
        // fresh figure waits out nothing and instead reads through a key no earlier read could have used.
        jdbc.sql("SELECT 1").query().listOfRows();
        clearSummaryCache();
    }

    @Autowired
    private org.springframework.cache.CacheManager caches;

    private void clearSummaryCache() {
        org.springframework.cache.Cache cache = caches.getCache("reviews.public_summary");
        if (cache != null) {
            cache.clear();
        }
    }

    private void setBrandSetting(String key, String field, String value) throws Exception {
        MvcResult result = mvc.perform(post(settings() + "/configuration/keys/" + key + "/values")
                        .with(token(OWNER))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"scopeType\":\"BRAND\",\"brandId\":\"%s\",\"explicitNull\":false,\"%s\":%s,\"reason\":\"pilot\"}"
                                        .formatted(BRAND, field, value)))
                .andReturn();
        if (result.getResponse().getStatus() == 409) {
            JsonNode current =
                    json(get(settings() + "/configuration/keys/" + key + "/resolution?scopeType=BRAND&brandId=" + BRAND)
                            .with(token(OWNER)));
            long version = current.path("trace").path("version").asLong(0);
            result = mvc.perform(post(settings() + "/configuration/keys/" + key + "/values")
                            .with(token(OWNER))
                            .header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    "{\"scopeType\":\"BRAND\",\"brandId\":\"%s\",\"explicitNull\":false,\"%s\":%s,\"expectedVersion\":%d,\"reason\":\"pilot\"}"
                                            .formatted(BRAND, field, value, version)))
                    .andReturn();
        }
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        publicCache.evictNow(TENANT, BRAND);
        clearSummaryCache();
    }

    private void switchOn(String flag) throws Exception {
        MvcResult result = mvc.perform(
                        post(settings() + "/configuration/keys/" + flag + "/values")
                                .with(token(OWNER))
                                .header("Idempotency-Key", key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"scopeType\":\"TENANT\",\"explicitNull\":false,\"booleanValue\":true,\"reason\":\"pilot\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isLessThan(300);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult getAs(String subject, String path) throws Exception {
        return mvc.perform(get(path).with(token(subject))).andReturn();
    }

    private MvcResult postAs(String subject, String path, @Nullable String body) throws Exception {
        MockHttpServletRequestBuilder request = post(path).header("Idempotency-Key", key());
        request = GUEST.equals(subject) ? request.with(guest()) : request.with(token(subject));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult putAs(String subject, String path, String body, @Nullable Object version) throws Exception {
        MockHttpServletRequestBuilder request = put(path)
                .with(token(subject))
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (version != null) {
            request = request.header("If-Match", "W/\"" + version + "\"");
        }
        return mvc.perform(request).andReturn();
    }

    private static String promptPolicy(String sendWindow, int inlineDays, String rows, String reason) {
        return "{\"sendWindow\":%s,\"inlineWindowDays\":%d,\"rows\":[%s],\"reason\":\"%s\"}"
                .formatted(sendWindow, inlineDays, rows, reason);
    }

    private static String recoveryPolicy(
            int rating,
            int firstMinutes,
            int resolutionMinutes,
            boolean alertOnOpen,
            boolean alertOnBreach,
            String reason) {
        return ("{\"autoOpenMaxRating\":%d,\"firstResponseMinutes\":%d,\"resolutionMinutes\":%d,"
                        + "\"alertOnOpen\":%s,\"alertOnBreach\":%s,\"reason\":\"%s\"}")
                .formatted(rating, firstMinutes, resolutionMinutes, alertOnOpen, alertOnBreach, reason);
    }

    /** The minutes from opening to each clock's due time, as a case stored them. */
    private List<Long> caseWindowMinutes(UUID reviewId) {
        Map<String, Object> row = jdbc.sql("""
                SELECT EXTRACT(EPOCH FROM first_response_due_at - created_at)::bigint / 60 AS first_minutes,
                       EXTRACT(EPOCH FROM resolution_due_at - created_at)::bigint / 60 AS resolution_minutes
                  FROM reviews.recovery_cases WHERE review_id = :id
                """).param("id", reviewId).query().singleRow();
        return List.of(
                ((Number) Objects.requireNonNull(row.get("first_minutes"))).longValue(),
                ((Number) Objects.requireNonNull(row.get("resolution_minutes"))).longValue());
    }

    private List<String> auditActions() {
        return jdbc.sql("SELECT DISTINCT action_code FROM audit.audit_events WHERE action_code LIKE 'reviews.%' "
                        + "ORDER BY 1")
                .query(String.class)
                .list();
    }

    /**
     * The audit sanitiser redacts any change-document key that contains the letters of a protected term, and
     * "rating" and "setting" both contain "tin": a key named after either would be stored as a redaction.
     */
    private void assertNothingRedacted(String... actions) {
        for (String action : actions) {
            assertThat(jdbc.sql("SELECT change_document::text FROM audit.audit_events WHERE action_code = :a")
                            .param("a", action)
                            .query(String.class)
                            .list())
                    .as("%s: at least one audited change", action)
                    .isNotEmpty()
                    .allSatisfy(document -> assertThat(document)
                            .as("%s: a redacted value is a key the sanitiser mistook for personal data", action)
                            .doesNotContain("[redacted]"));
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void grant(UUID tenantId, String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'feedback endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static String storefront() {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND;
    }

    private static String settings() {
        return "/api/v1/operations/tenants/" + TENANT;
    }

    private static String brandReviews() {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/reviews";
    }

    private static String key() {
        return "feedback-key-" + KEYS.incrementAndGet();
    }

    private static RequestPostProcessor guest() {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(GUEST));
    }

    /** Carries no realm role, so a refusal proves the ADR 0025 grant decided it. */
    private static RequestPostProcessor token(String subject) {
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
