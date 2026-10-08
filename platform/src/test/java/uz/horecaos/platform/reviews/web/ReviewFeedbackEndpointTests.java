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
