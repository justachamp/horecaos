package uz.horecaos.platform.reviews.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.reviews.application.RecoverySlaSweeper;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The service-recovery worklist over HTTP (ADR 0159, Decisions 8-11), with real grants, a real schema and
 * the real remedy, grant, consent and staff tables behind it.
 *
 * <p>Every principal holds the platform's own {@link PlatformRole} bundle through a real {@code
 * iam.grants} row, so the suite fails if a bundle loses {@code recovery.case.manage}, if a route's
 * declared scope stops being the one a grant covers, or if the worklist starts returning a row outside a
 * reader's reach. The assertions that matter most look for what must <em>not</em> be there -- a note, a
 * comment -- in the places a bug would put it: the response of a mutation, the idempotency store, the
 * outbox and the audit trail.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceRecoveryEndpointTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ISSUER = "https://issuer.test/realms/horecaos";

    private static final UUID TENANT = UUID.fromString("018f9f10-b000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-b000-7000-8000-0000000000b1");
    private static final UUID CHILONZOR = UUID.fromString("018f9f10-b000-7000-8000-0000000000c1");
    private static final UUID YUNUSOBOD = UUID.fromString("018f9f10-b000-7000-8000-0000000000c2");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-b000-7000-8000-0000000000e1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f10-b000-7000-8000-0000000000e2");
    private static final UUID OTHER_BRANCH = UUID.fromString("018f9f10-b000-7000-8000-0000000000e3");

    private static final String OWNER = "recovery-owner";
    private static final String BRAND_MANAGER = "recovery-brand-manager";
    private static final String CHI_MANAGER = "recovery-chilonzor-manager";
    private static final String YUN_MANAGER = "recovery-yunusobod-manager";
    private static final String COOK = "recovery-chilonzor-cook";
    private static final String SUPPORT = "recovery-support";
    private static final String FINANCE = "recovery-finance";
    private static final String OTHER_OWNER = "recovery-other-owner";
    private static final String GUEST = "recovery-guest";

    private static final String COMMENT = "The soup arrived cold and the courier shouted at my neighbour";
    private static final String NOTE = "Rang her on 998901234567; she wants a call back after six";

    private static final AtomicInteger KEYS = new AtomicInteger();

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the recovery endpoint test");
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
        // The sweeper is run by the test, on purpose; its own timer must not race the test for the case.
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
    private RecoverySlaSweeper sweeper;

    private OrderBoardFixtures fixtures;
    private int orders;
    private UUID support;

    @BeforeEach
    void reset() throws Exception {
        fixtures = new OrderBoardFixtures(jdbc);
        orders = 0;
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE integration.outbox_events").update();
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE reviews.order_reviews CASCADE").update();
        jdbc.sql("TRUNCATE TABLE iam.staff_members CASCADE").update();
        jdbc.sql(
                        "DELETE FROM tenant.configuration_values WHERE key_code LIKE 'feature.%' OR key_code LIKE 'feedback.%'")
                .update();
        fixtures.clean();
        roleRegistry.synchronize();

        fixtures.tenant(TENANT, "recovery-endpoint", BRAND, CHILONZOR);
        fixtures.location(TENANT, BRAND, YUNUSOBOD, "YUN", "Yunusobod");
        fixtures.tenant(OTHER_TENANT, "recovery-other", OTHER_BRAND, OTHER_BRANCH);

        grant(TENANT, OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        grant(TENANT, BRAND_MANAGER, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        grant(TENANT, CHI_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", CHILONZOR);
        grant(TENANT, YUN_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", YUNUSOBOD);
        grant(TENANT, COOK, PlatformRole.LOCATION_STAFF, "LOCATION", CHILONZOR);
        grant(TENANT, SUPPORT, PlatformRole.SUPPORT_AGENT, "TENANT", TENANT);
        grant(TENANT, FINANCE, PlatformRole.TENANT_FINANCE, "TENANT", TENANT);
        grant(OTHER_TENANT, OTHER_OWNER, PlatformRole.TENANT_OWNER, "TENANT", OTHER_TENANT);
        List.of(OWNER, BRAND_MANAGER, CHI_MANAGER, YUN_MANAGER, COOK, SUPPORT, FINANCE)
                .forEach(subject -> authorization.evictGrants(subject, TENANT));
        authorization.evictGrants(OTHER_OWNER, OTHER_TENANT);

        support = staffMember(SUPPORT, "S-0001");
        staffMember(CHI_MANAGER, "S-0002");
        staffMember(BRAND_MANAGER, "S-0003");
        staffMember(FINANCE, "S-0004");

        // The guest's account exists (the fixture made it); her sign-in is a link from the identity provider.
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

        switchOn(OWNER, "feature.service_recovery");
        switchOn(OWNER, "feature.feedback_settings");
    }

    // ================================================================ the whole story

    @Test
    @DisplayName(
            "a one-star review with two tags opens a case; a support agent works it to REMEDY_GRANTED with an apology code and a remedy")
    void theExitCriteriaStory() throws Exception {
        JsonNode pack = json(post(settings() + "/feedback/tags/starter-pack")
                .with(token(OWNER))
                .header("Idempotency-Key", key()));
        assertThat(pack).as("the twelve starter tags").hasSize(12);
        Map<String, String> tagIds = new java.util.LinkedHashMap<>();
        for (JsonNode tag : pack) {
            tagIds.put(tag.path("code").asText(), tag.path("id").asText());
        }
        UUID orderId = completedOrder("RC-STORY", CHILONZOR);

        MvcResult rated =
                submit(orderId, 1, COMMENT, List.of(tagIds.get("food_cold"), tagIds.get("delivery_late")), true);

        assertThat(rated.getResponse().getStatus()).isEqualTo(200);
        JsonNode review = JSON.readTree(rated.getResponse().getContentAsString());
        assertThat(review.path("tags")).hasSize(2);
        assertThat(review.path("publicDisplay").path("state").asText()).isEqualTo("PENDING");

        Map<String, Object> opened = jdbc.sql(
                        "SELECT id, state, source, EXTRACT(EPOCH FROM first_response_due_at - created_at)::bigint AS first_gap, "
                                + "EXTRACT(EPOCH FROM resolution_due_at - created_at)::bigint AS second_gap FROM reviews.recovery_cases")
                .query()
                .singleRow();
        assertThat(opened).containsEntry("state", "NEW").containsEntry("source", "AUTO");
        assertThat(opened.get("first_gap")).as("240 minutes").isEqualTo(240L * 60);
        assertThat(opened.get("second_gap")).as("72 hours").isEqualTo(72L * 3600);
        UUID caseId = Objects.requireNonNull((UUID) opened.get("id"));

        assertThat(jdbc.sql("SELECT decision || ':' || purpose || ':' || policy_version || ':' || source "
                                + "FROM customer.consent_decisions WHERE purpose = 'review.public_display'")
                        .query(String.class)
                        .single())
                .as("her tick is a consent decision, recorded as what she was shown")
                .isEqualTo("GRANTED:review.public_display:1:STOREFRONT");
        assertThat(jdbc.sql("SELECT event_type FROM integration.outbox_events ORDER BY created_at, event_type")
                        .query(String.class)
                        .list())
                .containsExactlyInAnyOrder("ReviewSubmitted", "RecoveryCaseOpened");
        assertThat(allOutboxPayloads()).doesNotContain("soup").doesNotContain("shouted");

        // The support agent starts work: she becomes the owner, and this is the first response.
        MvcResult started = transition(SUPPORT, CHILONZOR, caseId, "{\"to\":\"IN_PROGRESS\"}", 1);
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        JsonNode startedBody = JSON.readTree(started.getResponse().getContentAsString());
        assertThat(startedBody.path("state").asText()).isEqualTo("IN_PROGRESS");
        assertThat(startedBody.path("ownerStaffMemberId").asText()).isEqualTo(support.toString());
        assertThat(started.getResponse().getContentAsString())
                .as("a mutation answers with ids, states and versions")
                .doesNotContain("soup");

        JsonNode drawer = drawer(SUPPORT, CHILONZOR, caseId);
        assertThat(drawer.path("comment").asText())
                .as("a support agent reads the customer's words")
                .isEqualTo(COMMENT);
        assertThat(drawer.path("commentWithheld").asBoolean()).isFalse();
        assertThat(drawer.path("summary").path("owner").path("displayReference").asText())
                .isEqualTo("S-0001");
        assertThat(drawer.path("summary").path("rating").asInt()).isEqualTo(1);
        assertThat(drawer.path("tags"))
                .extracting(tag -> tag.path("code").asText())
                .containsExactlyInAnyOrder("food_cold", "delivery_late");

        // The owner mints an apology code for this customer and the support agent links it.
        MvcResult minted = mvc.perform(post("/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND
                                + "/benefit-grants")
                        .with(token(OWNER))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"customerAccountId\":\"%s\",\"shape\":\"FIXED_AMOUNT_OFF_ORDER\",\"value\":20000,"
                                        + "\"currency\":\"UZS\",\"minBasketMinor\":0,\"sourceType\":\"RECOVERY_CASE\","
                                        + "\"sourceId\":\"%s\"}")
                                .formatted(OrderBoardFixtures.customerOf(TENANT), caseId)))
                .andReturn();
        assertThat(minted.getResponse().getStatus()).isEqualTo(201);
        String grantId = JSON.readTree(minted.getResponse().getContentAsString())
                .path("grantId")
                .asText();

        MvcResult attachedGrant = postAs(
                SUPPORT,
                locationCases(CHILONZOR) + "/" + caseId + "/compensations",
                "{\"kind\":\"BENEFIT_GRANT\",\"refId\":\"%s\"}".formatted(grantId));
        assertThat(attachedGrant.getResponse().getStatus()).isEqualTo(201);

        UUID remedy = remedyOf(orderId, 45_000);
        MvcResult attachedRemedy = postAs(
                SUPPORT,
                locationCases(CHILONZOR) + "/" + caseId + "/compensations",
                "{\"kind\":\"ORDER_REMEDY\",\"refId\":\"%s\"}".formatted(remedy));
        assertThat(attachedRemedy.getResponse().getStatus()).isEqualTo(201);
        JsonNode remedyBody = JSON.readTree(attachedRemedy.getResponse().getContentAsString());
        assertThat(remedyBody.path("amountMinor").asLong())
                .as("copied from the remedy at this moment")
                .isEqualTo(45_000);
        assertThat(remedyBody.path("currency").asText()).isEqualTo("UZS");

        MvcResult resolved =
                transition(SUPPORT, CHILONZOR, caseId, "{\"to\":\"RESOLVED\",\"outcomeCode\":\"REMEDY_GRANTED\"}", 2);
        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);

        JsonNode closed = drawer(SUPPORT, CHILONZOR, caseId);
        assertThat(closed.path("summary").path("state").asText()).isEqualTo("RESOLVED");
        assertThat(closed.path("summary").path("outcomeCode").asText()).isEqualTo("REMEDY_GRANTED");
        assertThat(closed.path("timeline"))
                .extracting(line -> line.path("kind").asText())
                .as("every step, in order")
                .containsExactly("OPENED", "STARTED", "COMPENSATION_ATTACHED", "COMPENSATION_ATTACHED", "RESOLVED");
        assertThat(closed.path("compensations")).hasSize(2);
        assertThat(closed.path("summary").path("compensationCount").asInt()).isEqualTo(2);

        assertThat(jdbc.sql(
                                "SELECT event_type FROM integration.outbox_events WHERE event_type = 'RecoveryCaseStateChanged'")
                        .query(String.class)
                        .list())
                .hasSize(2);
        assertThat(jdbc.sql(
                                "SELECT payload->>'ownerReference' || ':' || (payload->>'outcomeCode') FROM integration.outbox_events "
                                        + "WHERE event_type = 'RecoveryCaseStateChanged' ORDER BY created_at DESC LIMIT 1")
                        .query(String.class)
                        .single())
                .as("the non-personal reference and the outcome, never the owner's name")
                .isEqualTo("S-0001:REMEDY_GRANTED");
        assertThat(jdbc.sql(
                                "SELECT DISTINCT action_code FROM audit.audit_events WHERE action_code LIKE 'reviews.case.%' ORDER BY 1")
                        .query(String.class)
                        .list())
                .containsExactly("reviews.case.compensate", "reviews.case.open", "reviews.case.transition");
    }

    // =========================================================== who may do what

    @Test
    @DisplayName("a branch manager works her own branch's cases and no other's; the brand worklist needs the brand")
    void reachFollowsTheGrant() throws Exception {
        UUID chilonzorCase = openCase("RC-CHI", CHILONZOR);
        UUID yunusobodCase = openCase("RC-YUN", YUNUSOBOD);

        assertThat(listIds(CHI_MANAGER, locationCases(CHILONZOR))).containsExactly(chilonzorCase.toString());
        assertThat(getAs(CHI_MANAGER, locationCases(YUNUSOBOD)).getResponse().getStatus())
                .as("another branch's list is refused, not empty")
                .isEqualTo(403);
        assertThat(getAs(CHI_MANAGER, locationCases(CHILONZOR) + "/" + yunusobodCase)
                        .getResponse()
                        .getStatus())
                .as("a case of another branch addressed through hers is not found")
                .isEqualTo(404);
        assertThat(transition(CHI_MANAGER, CHILONZOR, yunusobodCase, "{\"to\":\"IN_PROGRESS\"}", 1)
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(getAs(CHI_MANAGER, locationCases(YUNUSOBOD) + "/" + yunusobodCase)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);

        MvcResult brandWide = getAs(CHI_MANAGER, brandCases());
        assertThat(brandWide.getResponse().getStatus()).isEqualTo(403);
        assertRefused(brandWide, Capability.RECOVERY_CASE_MANAGE);
        assertThat(listIds(BRAND_MANAGER, brandCases()))
                .as("a brand manager sees every branch's cases")
                .containsExactlyInAnyOrder(chilonzorCase.toString(), yunusobodCase.toString());
        assertThat(listIds(BRAND_MANAGER, brandCases() + "?locationId=" + YUNUSOBOD))
                .containsExactly(yunusobodCase.toString());
        assertThat(transition(BRAND_MANAGER, YUNUSOBOD, yunusobodCase, "{\"to\":\"IN_PROGRESS\"}", 1)
                        .getResponse()
                        .getStatus())
                .as("and works them through the same routes")
                .isEqualTo(200);

        assertThat(getAs(COOK, locationCases(CHILONZOR)).getResponse().getStatus())
                .as("the line holds no recovery capability")
                .isEqualTo(403);
        assertThat(getAs(OTHER_OWNER, locationCases(CHILONZOR)).getResponse().getStatus())
                .as("another tenant's owner is no one here")
                .isEqualTo(403);
        assertThat(getAs(
                                OTHER_OWNER,
                                "/api/v1/operations/tenants/" + OTHER_TENANT + "/brands/" + OTHER_BRAND + "/locations/"
                                        + OTHER_BRANCH + "/recovery-cases/" + chilonzorCase)
                        .getResponse()
                        .getStatus())
                .as("and a case id of mine, asked for under theirs, is not found")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("the worklist can be filtered by the rating of the review, on the brand and on the branch")
    void theRatingFilterOverHttp() throws Exception {
        UUID oneStar = openCase("RC-ONE", CHILONZOR);
        MvcResult rated = submit(completedOrder("RC-TWO", CHILONZOR), 2, COMMENT, List.of(), false);
        assertThat(rated.getResponse().getStatus()).isEqualTo(200);
        UUID twoStars = jdbc.sql("SELECT id FROM reviews.recovery_cases WHERE review_id = :review")
                .param(
                        "review",
                        UUID.fromString(JSON.readTree(rated.getResponse().getContentAsString())
                                .path("id")
                                .asText()))
                .query(UUID.class)
                .single();

        assertThat(listIds(BRAND_MANAGER, brandCases() + "?minRating=2&maxRating=2"))
                .containsExactly(twoStars.toString());
        assertThat(listIds(BRAND_MANAGER, brandCases() + "?maxRating=1")).containsExactly(oneStar.toString());
        assertThat(listIds(BRAND_MANAGER, brandCases() + "?minRating=1&maxRating=2"))
                .containsExactlyInAnyOrder(oneStar.toString(), twoStars.toString());
        assertThat(listIds(CHI_MANAGER, locationCases(CHILONZOR) + "?minRating=2"))
                .as("the branch's own list takes the same filter")
                .containsExactly(twoStars.toString());
        assertThat(listIds(BRAND_MANAGER, brandCases() + "?minRating=4")).isEmpty();
        assertThat(getAs(BRAND_MANAGER, brandCases() + "?minRating=0")
                        .getResponse()
                        .getStatus())
                .as("a bound outside one to five is refused, not answered with a list that looks like a quiet week")
                .isEqualTo(400);
        assertThat(getAs(BRAND_MANAGER, brandCases() + "?minRating=4&maxRating=2")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("a case worker without the payment console's capability still sees the order's remedies and the"
            + " customer's codes in the drawer's picker, and never a code")
    void theCompensationPickerOverHttp() throws Exception {
        UUID orderId = completedOrder("RC-PICK", CHILONZOR);
        MvcResult rated = submit(orderId, 1, COMMENT, List.of(), false);
        UUID caseId = jdbc.sql("SELECT id FROM reviews.recovery_cases WHERE review_id = :review")
                .param(
                        "review",
                        UUID.fromString(JSON.readTree(rated.getResponse().getContentAsString())
                                .path("id")
                                .asText()))
                .query(UUID.class)
                .single();
        UUID remedy = remedyOf(orderId, 45_000);
        remedyOf(completedOrder("RC-OTHER", CHILONZOR), 10_000);
        MvcResult minted = mvc.perform(post("/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND
                                + "/benefit-grants")
                        .with(token(OWNER))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"customerAccountId\":\"%s\",\"shape\":\"FIXED_AMOUNT_OFF_ORDER\",\"value\":20000,"
                                        + "\"currency\":\"UZS\",\"minBasketMinor\":0,\"sourceType\":\"RECOVERY_CASE\","
                                        + "\"sourceId\":\"%s\"}")
                                .formatted(OrderBoardFixtures.customerOf(TENANT), caseId)))
                .andReturn();
        assertThat(minted.getResponse().getStatus()).isEqualTo(201);
        JsonNode mintedBody = JSON.readTree(minted.getResponse().getContentAsString());
        String grantId = mintedBody.path("grantId").asText();
        String plaintext = mintedBody.path("plaintextCode").asText();
        String picker = locationCases(CHILONZOR) + "/" + caseId + "/compensation-candidates";

        MvcResult asSupport = getAs(SUPPORT, picker);

        assertThat(asSupport.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(asSupport.getResponse().getContentAsString());
        assertThat(body.path("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(body.path("customerAccountId").asText())
                .isEqualTo(OrderBoardFixtures.customerOf(TENANT).toString());
        assertThat(body.path("remedies"))
                .as("this order's remedy only, not the other order's")
                .hasSize(1);
        assertThat(body.path("remedies").get(0).path("refId").asText()).isEqualTo(remedy.toString());
        assertThat(body.path("remedies").get(0).path("amountMinor").asLong()).isEqualTo(45_000L);
        assertThat(body.path("remedies").get(0).path("label").asText()).isEqualTo("ORDER_REFUND");
        assertThat(body.path("remedies").get(0).path("taken").asBoolean()).isFalse();
        assertThat(body.path("grants")).hasSize(1);
        assertThat(body.path("grants").get(0).path("grantId").asText()).isEqualTo(grantId);
        assertThat(body.path("grants").get(0).path("benefitType").asText()).isEqualTo("FIXED_AMOUNT_OFF_ORDER");
        assertThat(body.path("grants").get(0).path("value").asLong()).isEqualTo(20_000L);
        assertThat(asSupport.getResponse().getContentAsString())
                .as("the picker lists a code by its last characters and never the code")
                .doesNotContain(plaintext);

        assertThat(postAs(
                                SUPPORT,
                                locationCases(CHILONZOR) + "/" + caseId + "/compensations",
                                "{\"kind\":\"ORDER_REMEDY\",\"refId\":\"%s\"}".formatted(remedy))
                        .getResponse()
                        .getStatus())
                .isEqualTo(201);
        JsonNode after = JSON.readTree(getAs(SUPPORT, picker).getResponse().getContentAsString());
        assertThat(after.path("remedies").get(0).path("taken").asBoolean())
                .as("once linked it is marked, so the picker never offers a conflict")
                .isTrue();

        assertThat(getAs(COOK, picker).getResponse().getStatus())
                .as("the line holds no recovery capability")
                .isEqualTo(403);
        assertThat(getAs(CHI_MANAGER, locationCases(YUNUSOBOD) + "/" + caseId + "/compensation-candidates")
                        .getResponse()
                        .getStatus())
                .as("another branch's route is refused")
                .isEqualTo(403);
        assertThat(getAs(BRAND_MANAGER, locationCases(YUNUSOBOD) + "/" + caseId + "/compensation-candidates")
                        .getResponse()
                        .getStatus())
                .as("and a case of mine asked for through another branch is not found")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("finance can work a case without reading the customer's words: the comment is withheld, with a marker")
    void financeWorksACaseButCannotReadTheComment() throws Exception {
        UUID caseId = openCase("RC-FIN", CHILONZOR);

        JsonNode asFinance = drawer(FINANCE, CHILONZOR, caseId);
        assertThat(asFinance.path("hasComment").asBoolean()).isTrue();
        assertThat(asFinance.path("comment").isNull()).isTrue();
        assertThat(asFinance.path("commentWithheld").asBoolean()).isTrue();
        assertThat(asFinance.toString()).doesNotContain("soup");
        assertThat(transition(
                                FINANCE,
                                CHILONZOR,
                                caseId,
                                "{\"to\":\"CLOSED_UNRESOLVED\",\"outcomeCode\":\"NOT_ACTIONABLE\"}",
                                1)
                        .getResponse()
                        .getStatus())
                .as("withheld words do not stop the case being worked")
                .isEqualTo(200);

        JsonNode asManager = drawer(CHI_MANAGER, CHILONZOR, caseId);
        assertThat(asManager.path("comment").asText())
                .as("a branch manager now holds review.read for her branch")
                .isEqualTo(COMMENT);
    }

    // ===================================================== the conventions of ADR 0031

    @Test
    @DisplayName("a move needs an Idempotency-Key and a version; a replay is one move; a stale version is 409")
    void theConventions() throws Exception {
        UUID caseId = openCase("RC-CONV", CHILONZOR);
        String path = locationCases(CHILONZOR) + "/" + caseId + "/transitions";

        MvcResult noKey = mvc.perform(post(path)
                        .with(token(CHI_MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "W/\"1\"")
                        .content("{\"to\":\"IN_PROGRESS\"}"))
                .andReturn();
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(noKey.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");

        MvcResult noVersion = mvc.perform(post(path)
                        .with(token(CHI_MANAGER))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"IN_PROGRESS\"}"))
                .andReturn();
        assertThat(noVersion.getResponse().getStatus()).isEqualTo(400);
        assertThat(noVersion.getResponse().getContentAsString()).contains("INVALID_REQUEST");

        String replayKey = key();
        MvcResult first = mvc.perform(post(path)
                        .with(token(CHI_MANAGER))
                        .header("Idempotency-Key", replayKey)
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"IN_PROGRESS\"}"))
                .andReturn();
        MvcResult replay = mvc.perform(post(path)
                        .with(token(CHI_MANAGER))
                        .header("Idempotency-Key", replayKey)
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"IN_PROGRESS\"}"))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM reviews.recovery_case_events WHERE case_id = :id AND kind = 'STARTED'")
                        .param("id", caseId)
                        .query(Long.class)
                        .single())
                .as("the second call was the first call's answer, not a second start")
                .isEqualTo(1L);

        MvcResult stale = mvc.perform(post(path)
                        .with(token(CHI_MANAGER))
                        .header("Idempotency-Key", key())
                        .header("If-Match", "W/\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"NEW\"}"))
                .andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");

        MvcResult bodyVersion = mvc.perform(post(path)
                        .with(token(CHI_MANAGER))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"NEW\",\"expectedVersion\":2}"))
                .andReturn();
        assertThat(bodyVersion.getResponse().getStatus())
                .as("the version may travel in the body, as the record's own table writes it")
                .isEqualTo(200);

        MvcResult edgeThatDoesNotExist = mvc.perform(post(path)
                        .with(token(CHI_MANAGER))
                        .header("Idempotency-Key", key())
                        .header("If-Match", "W/\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"RESOLVED\",\"outcomeCode\":\"OFFLINE_GESTURE\"}"))
                .andReturn();
        assertThat(edgeThatDoesNotExist.getResponse().getStatus()).isEqualTo(422);
        assertThat(edgeThatDoesNotExist.getResponse().getContentAsString()).contains("UNPROCESSABLE_STATE");

        MvcResult outcomeThatDoesNotFit = mvc.perform(post(path)
                        .with(token(CHI_MANAGER))
                        .header("Idempotency-Key", key())
                        .header("If-Match", "W/\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"CLOSED_UNRESOLVED\",\"outcomeCode\":\"OFFLINE_GESTURE\"}"))
                .andReturn();
        assertThat(outcomeThatDoesNotFit.getResponse().getStatus()).isEqualTo(400);
        assertThat(outcomeThatDoesNotFit.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
    }

    @Test
    @DisplayName(
            "a note is in the drawer and nowhere else: not its own response, not the idempotency store, not the outbox, not the audit trail")
    void aNoteStaysInTheDrawer() throws Exception {
        UUID caseId = openCase("RC-NOTE", CHILONZOR);

        MvcResult added = postAs(
                CHI_MANAGER, locationCases(CHILONZOR) + "/" + caseId + "/notes", "{\"note\":\"%s\"}".formatted(NOTE));
        assertThat(added.getResponse().getStatus()).isEqualTo(201);
        assertThat(added.getResponse().getContentAsString())
                .as("the response is two ids")
                .doesNotContain("998901234567")
                .doesNotContain("call back");

        assertThat(drawer(CHI_MANAGER, CHILONZOR, caseId).path("timeline"))
                .filteredOn(line -> "NOTE".equals(line.path("kind").asText()))
                .singleElement()
                .satisfies(line -> assertThat(line.path("note").asText()).isEqualTo(NOTE));

        String idempotency = jdbc.sql(
                        "SELECT string_agg(coalesce(response_body, ''), ' ') FROM platform.idempotency_records")
                .query(String.class)
                .single();
        assertThat(idempotency)
                .as("the idempotency store")
                .doesNotContain("998901234567")
                .doesNotContain("call back");
        assertThat(allOutboxPayloads()).as("the outbox").doesNotContain("998901234567");
        assertThat(jdbc.sql(
                                "SELECT coalesce(string_agg(coalesce(change_document::text, '') || coalesce(reason, ''), ' '), '') "
                                        + "FROM audit.audit_events")
                        .query(String.class)
                        .single())
                .as("the audit trail")
                .doesNotContain("998901234567")
                .doesNotContain("call back");
        assertThat(jdbc.sql("SELECT note_protected FROM reviews.recovery_case_events WHERE kind = 'NOTE'")
                        .query(String.class)
                        .single())
                .as("and at rest it is ciphertext")
                .doesNotContain("998901234567");
    }

    @Test
    @DisplayName("the customer's comment is not in the idempotency store either, in the clear")
    void theSubmitResponseIsNotStoredReadable() throws Exception {
        MvcResult rated = submit(completedOrder("RC-STORE", CHILONZOR), 4, COMMENT, List.of(), false);
        assertThat(rated.getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbc.sql("SELECT string_agg(coalesce(response_body, ''), ' ') FROM platform.idempotency_records")
                        .query(String.class)
                        .single())
                .doesNotContain("soup")
                .doesNotContain("shouted");
    }

    // ======================================================================= the clocks

    @Test
    @DisplayName("an untouched case is marked breached once, appears in the timeline, and is published once")
    void theSweeperOverHttp() throws Exception {
        UUID caseId = openCase("RC-SLA", CHILONZOR);
        assertThat(sweeper.runOnce()).as("nothing is due yet").isZero();

        // Time passing, as production has it, is the clock; the closest a single-JVM context test can get
        // is moving the due time, which RecoveryCaseServiceTests proves with a real clock advancing.
        jdbc.sql("UPDATE reviews.recovery_cases SET first_response_due_at = now() - interval '1 minute', "
                        + "created_at = now() - interval '5 hours' WHERE id = :id")
                .param("id", caseId)
                .update();

        assertThat(sweeper.runOnce()).isEqualTo(1);
        assertThat(sweeper.runOnce()).as("once").isZero();
        assertThat(jdbc.sql(
                                "SELECT event_type FROM integration.outbox_events WHERE event_type = 'RecoveryCaseSlaBreached'")
                        .query(String.class)
                        .list())
                .hasSize(1);
        JsonNode drawer = drawer(CHI_MANAGER, CHILONZOR, caseId);
        assertThat(drawer.path("timeline"))
                .extracting(line -> line.path("kind").asText())
                .containsExactly("OPENED", "FIRST_RESPONSE_BREACHED");
        assertThat(drawer.path("summary").path("firstResponseBreached").asBoolean())
                .isTrue();
        assertThat(drawer.path("summary").path("slaState").asText()).isEqualTo("BREACHED");

        JsonNode summary = json(get(brandCases() + "/summary").with(token(BRAND_MANAGER)));
        assertThat(summary.path("byState").path("NEW").asLong()).isEqualTo(1);
        assertThat(summary.path("bySla").path("BREACHED").asLong()).isEqualTo(1);
    }

    // =================================================================== manual opening

    @Test
    @DisplayName("a review the policy did not flag can be taken into the worklist by hand, once")
    void manualOpeningOverHttp() throws Exception {
        UUID orderId = completedOrder("RC-MANUAL", CHILONZOR);
        JsonNode review = JSON.readTree(
                submit(orderId, 5, "wonderful", List.of(), false).getResponse().getContentAsString());
        UUID reviewId = UUID.fromString(review.path("id").asText());
        assertThat(jdbc.sql("SELECT count(*) FROM reviews.recovery_cases")
                        .query(Long.class)
                        .single())
                .isZero();

        MvcResult opened = postAs(CHI_MANAGER, locationCases(CHILONZOR), "{\"reviewId\":\"%s\"}".formatted(reviewId));
        assertThat(opened.getResponse().getStatus()).isEqualTo(201);
        assertThat(JSON.readTree(opened.getResponse().getContentAsString())
                        .path("state")
                        .asText())
                .isEqualTo("NEW");
        assertThat(jdbc.sql("SELECT source FROM reviews.recovery_cases")
                        .query(String.class)
                        .single())
                .isEqualTo("MANUAL");

        MvcResult again = postAs(CHI_MANAGER, locationCases(CHILONZOR), "{\"reviewId\":\"%s\"}".formatted(reviewId));
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(postAs(YUN_MANAGER, locationCases(YUNUSOBOD), "{\"reviewId\":\"%s\"}".formatted(reviewId))
                        .getResponse()
                        .getStatus())
                .as("another branch's manager cannot take it")
                .isEqualTo(404);
        assertThat(mvc.perform(post(locationCases(CHILONZOR))
                                .with(token(CHI_MANAGER))
                                .header("Idempotency-Key", key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a body without a review is a validation failure, not a server error")
                .isEqualTo(400);
    }

    @Test
    @DisplayName(
            "the operations review list says which tags a review carries, where its comment stands, and which case answers it")
    void theReviewListLinksToTheCase() throws Exception {
        UUID caseId = openCase("RC-LINK", CHILONZOR);

        JsonNode list = json(get("/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/reviews")
                .with(token(BRAND_MANAGER)));
        assertThat(list.path("items")).hasSize(1);
        JsonNode row = list.path("items").get(0);
        assertThat(row.path("caseId").asText()).isEqualTo(caseId.toString());
        assertThat(row.path("caseState").asText()).isEqualTo("NEW");
        assertThat(row.path("publicState").asText()).isEqualTo("NOT_SHARED");
        assertThat(row.path("comment").asText()).isEqualTo(COMMENT);
    }

    // ===================================================================== fixtures

    /** A one-star review of a completed order, submitted by the guest, and the case it opened. */
    private UUID openCase(String seed, UUID location) throws Exception {
        MvcResult rated = submit(completedOrder(seed, location), 1, COMMENT, List.of(), false);
        assertThat(rated.getResponse().getStatus()).isEqualTo(200);
        return jdbc.sql("SELECT id FROM reviews.recovery_cases WHERE review_id = :review")
                .param(
                        "review",
                        UUID.fromString(JSON.readTree(rated.getResponse().getContentAsString())
                                .path("id")
                                .asText()))
                .query(UUID.class)
                .single();
    }

    private UUID completedOrder(String seed, UUID location) {
        return fixtures.insertOrder(OrderBoardFixtures.order(seed + "-" + (++orders))
                .at(TENANT, BRAND, location)
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
        return mvc.perform(post("/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/orders/" + orderId
                                + "/review")
                        .with(jwt().jwt(builder -> builder.issuer(ISSUER).subject(GUEST)))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andReturn();
    }

    private MvcResult transition(String subject, UUID location, UUID caseId, String body, int version)
            throws Exception {
        return mvc.perform(post(locationCases(location) + "/" + caseId + "/transitions")
                        .with(token(subject))
                        .header("Idempotency-Key", key())
                        .header("If-Match", "W/\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult postAs(String subject, String path, String body) throws Exception {
        return mvc.perform(post(path)
                        .with(token(subject))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult getAs(String subject, String path) throws Exception {
        return mvc.perform(get(path).with(token(subject))).andReturn();
    }

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isLessThan(300);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode drawer(String subject, UUID location, UUID caseId) throws Exception {
        return json(get(locationCases(location) + "/" + caseId).with(token(subject)));
    }

    private List<String> listIds(String subject, String path) throws Exception {
        MvcResult result = getAs(subject, path);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        List<String> ids = new ArrayList<>();
        JSON.readTree(result.getResponse().getContentAsString())
                .path("items")
                .forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    private String allOutboxPayloads() {
        return jdbc.sql("SELECT coalesce(string_agg(payload::text, ' '), '') FROM integration.outbox_events")
                .query(String.class)
                .single();
    }

    private void switchOn(String owner, String flag) throws Exception {
        MvcResult result = mvc.perform(
                        post("/api/v1/operations/tenants/" + TENANT + "/configuration/keys/" + flag + "/values")
                                .with(token(owner))
                                .header("Idempotency-Key", key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"scopeType\":\"TENANT\",\"explicitNull\":false,\"booleanValue\":true,\"reason\":\"pilot\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private UUID remedyOf(UUID orderId, long amountMinor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.order_remedies (id, tenant_id, brand_id, order_id, remedy_type, reason_code, reason,
                    currency, amount_minor, attested_money_minor, platform_settled_minor, settlement_basis,
                    execution_channel, executed_by, executed_at, recorded_by, recorded_at, idempotency_key, version)
                VALUES (:id, :t, :b, :o, 'ORDER_REFUND', 'LATE_DELIVERY', 'cold soup', 'UZS', :amount, :amount, 0,
                    'OPERATOR_ATTESTED', 'CASH_DRAWER', 'manager-1', now(), 'manager-1', now(), :key, 1)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("o", orderId)
                .param("amount", amountMinor)
                .param("key", "remedy-" + id)
                .update();
        return id;
    }

    private UUID staffMember(String subject, String reference) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO iam.staff_members (id, tenant_id, principal_subject, display_reference, employment_status)
                VALUES (:id, :t, :subject, :reference, 'ACTIVE')
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("subject", subject)
                .param("reference", reference)
                .update();
        return id;
    }

    private void grant(UUID tenantId, String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'recovery endpoint test', :validFrom)
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

    private void assertRefused(MvcResult result, Capability missing) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(missing.code());
    }

    private static String settings() {
        return "/api/v1/operations/tenants/" + TENANT;
    }

    private static String brandCases() {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/recovery-cases";
    }

    private static String locationCases(UUID location) {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + location
                + "/recovery-cases";
    }

    private static String key() {
        return "recovery-key-" + KEYS.incrementAndGet();
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
