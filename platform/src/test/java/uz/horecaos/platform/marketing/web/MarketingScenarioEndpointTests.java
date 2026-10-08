package uz.horecaos.platform.marketing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.customers.infrastructure.security.PresetVerificationCodeSource;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.marketing.api.ScenarioParticipantStopped;
import uz.horecaos.platform.marketing.api.ScenarioStepDecided;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0112's console and storefront surface, over HTTP with the bodies a console sends:
 * scenarios, offers, the contact policy, the banners a storefront polls for, and the events
 * the engine appends to the outbox.
 *
 * <p>Authoring is split the way production roles are: {@code TENANT_ADMIN} holds {@code
 * campaign.author}, {@code marketing.offer.manage} and {@code marketing.contact_policy.manage};
 * {@code TENANT_OWNER} holds {@code campaign.approve} and none of those. State-machine proofs
 * of the engine itself are the hand-wired scenario tests' job; this class proves the
 * controllers' authorization, request and response shapes, optimistic concurrency, brand and
 * tenant isolation, and what reaches the outbox.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class MarketingScenarioEndpointTests {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-000000000e11");
    private static final UUID BRAND = UUID.fromString("018f9b20-4000-7000-8000-000000000e12");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-4000-7000-8000-000000000e13");
    private static final UUID SEED_AUDIENCE = UUID.fromString("018f9b20-4000-7000-8000-000000000e14");

    private static final String OWNER = "018f9b20-4000-7000-8000-000000000f11";
    private static final String ADMINISTRATOR = "018f9b20-4000-7000-8000-000000000f12";

    private static final String PRESET_PHONE = "+998000000000";
    private static final String PRESET_CODE = "000000";

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
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
        registry.add(PresetVerificationCodeSource.PHONE_PROPERTY, () -> PRESET_PHONE);
        registry.add(PresetVerificationCodeSource.CODE_PROPERTY, () -> PRESET_CODE);
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
    private ApplicationEventPublisher publisher;

    @Autowired
    @SuppressWarnings("NullAway")
    private TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE integration.outbox_events").update();
        jdbc.sql("TRUNCATE TABLE marketing.scenario_step_decisions, marketing.scenario_participant_state, "
                        + "marketing.scenario_steps, marketing.presented_offers, marketing.offers, "
                        + "marketing.contact_policy_overrides, marketing.campaign_recipients, "
                        + "marketing.campaign_batches, marketing.campaigns, marketing.audience_snapshot_members, "
                        + "marketing.audience_snapshots, marketing.audience_predicates, marketing.audiences CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.promotions, loyalty.accrual_rules CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedFixtures();
        roleRegistry.synchronize();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN);
    }

    // ================================================================ scenarios

    @Test
    @DisplayName("a scenario is drafted from the console's minimal body, listed, and read back with its steps")
    void aScenarioIsDraftedAndReadBack() throws Exception {
        MvcResult created = send(
                post(scenarios()),
                ADMINISTRATOR,
                "scenario-create-1",
                scenarioBody("Win-back", "[" + sms(0) + "," + sms(3600) + "]"));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(created);
        assertThat(body.at("/campaign/status").asText()).isEqualTo("DRAFT");
        assertThat(body.at("/campaign/controlGroupPercent").isNull()).isTrue();
        assertThat(body.at("/campaign/supersedesCampaignId").isNull()).isTrue();
        assertThat(body.path("steps")).hasSize(2);
        assertThat(body.at("/steps/1/waitAfterPreviousSeconds").asInt()).isEqualTo(3600);
        assertThat(body.at("/steps/0/continuationCondition").asText()).isEqualTo("ALWAYS");
        assertThat(body.at("/steps/0/stopCondition").asText()).isEqualTo("NONE");
        assertThat(body.path("participants").isEmpty()).isTrue();
        assertThat(body.path("decisions").isEmpty()).isTrue();
        String id = body.at("/campaign/campaignId").asText();

        assertThat(json(mvc.perform(get(scenarios()).with(token(ADMINISTRATOR))).andReturn())
                        .findValuesAsString("campaignId"))
                .contains(id);
        assertThat(json(mvc.perform(get(scenarios() + "/" + id).with(token(ADMINISTRATOR)))
                                .andReturn())
                        .at("/campaign/name")
                        .asText())
                .isEqualTo("Win-back");
        // It is a campaign like any other as far as the console's campaign list goes, and says what kind.
        assertThat(json(mvc.perform(get(campaigns() + "/" + id).with(token(ADMINISTRATOR)))
                                .andReturn())
                        .path("kind")
                        .asText())
                .isEqualTo("SCENARIO");
    }

    @Test
    @DisplayName("the same Idempotency-Key drafts one scenario, not two")
    void aReplayedCreateIsOneScenario() throws Exception {
        String request = scenarioBody("Once", "[" + sms(0) + "]");

        MvcResult first = send(post(scenarios()), ADMINISTRATOR, "scenario-replay", request);
        MvcResult second = send(post(scenarios()), ADMINISTRATOR, "scenario-replay", request);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(second).at("/campaign/campaignId").asText())
                .isEqualTo(json(first).at("/campaign/campaignId").asText());
        assertThat(count("marketing.campaigns WHERE kind = 'SCENARIO'")).isEqualTo(1);
    }

    @Test
    @DisplayName("an owner holds the second signature and not the pen: authoring a scenario is refused")
    void anOwnerCannotAuthorAScenario() throws Exception {
        MvcResult refused =
                send(post(scenarios()), OWNER, "scenario-owner", scenarioBody("Refused", "[" + sms(0) + "]"));

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains("campaign.author");
        assertThat(count("marketing.campaigns")).isZero();
    }

    @Test
    @DisplayName("what cannot be a scenario is a 400 that says why, including a call-centre step")
    void invalidScenariosAreRefusedWithReasons() throws Exception {
        assertBadRequest(
                scenarioBody("Call", "[" + sms(0) + "," + step("CALL_CENTRE", "CALL", 0) + "]"), "CHANNEL_NOT_WIRED");
        assertBadRequest(scenarioBodyWith("No ceiling", "[" + sms(0) + "]", "null", "null"), "cost ceiling");
        assertBadRequest(scenarioBodyWith("Too much", "[" + sms(0) + "]", "100000", "101"), "controlGroupPercent");
        assertBadRequest(scenarioBody("No steps", "[]"), "steps");
        assertBadRequest(scenarioBody("Pigeon", "[" + step("CARRIER_PIGEON", "T", 0) + "]"), "CARRIER_PIGEON");
        assertBadRequest(
                scenarioBody("In app, no offer", "[" + sms(0) + "," + step("IN_APP", "BANNER", 0) + "]"),
                "names no offer");
        assertThat(count("marketing.campaigns")).isZero();
    }

    @Test
    @DisplayName(
            "a draft's steps are replaced; once the scenario is approved they are fixed and a revision is a new draft")
    void stepsAreFixedAfterApprovalAndRevisionIsANewDraft() throws Exception {
        String id = draftScenario("Fixed once approved", "[" + sms(0) + "]");

        MvcResult replaced = send(
                put(scenarios() + "/" + id + "/steps"),
                ADMINISTRATOR,
                "replace-1",
                "{\"steps\":[" + sms(0) + "," + sms(60) + "]}");
        assertThat(replaced.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(replaced).path("steps")).hasSize(2);

        // A revision of a draft is refused: a draft is edited in place.
        MvcResult tooEarly = send(post(scenarios() + "/" + id + "/revisions"), ADMINISTRATOR, "revise-early", null);
        assertThat(tooEarly.getResponse().getStatus()).isEqualTo(422);

        approve(id);
        MvcResult fixed = send(
                put(scenarios() + "/" + id + "/steps"), ADMINISTRATOR, "replace-2", "{\"steps\":[" + sms(0) + "]}");
        assertThat(fixed.getResponse().getStatus()).isEqualTo(422);
        assertThat(fixed.getResponse().getContentAsString())
                .contains("UNPROCESSABLE_STATE")
                .contains("revise");
        assertThat(json(mvc.perform(get(scenarios() + "/" + id).with(token(ADMINISTRATOR)))
                                .andReturn())
                        .path("steps"))
                .hasSize(2);

        MvcResult revised = send(post(scenarios() + "/" + id + "/revisions"), ADMINISTRATOR, "revise-1", null);
        assertThat(revised.getResponse().getStatus()).isEqualTo(201);
        JsonNode revision = json(revised);
        assertThat(revision.at("/campaign/status").asText()).isEqualTo("DRAFT");
        assertThat(revision.at("/campaign/supersedesCampaignId").asText()).isEqualTo(id);
        assertThat(revision.path("steps")).hasSize(2);
    }

    @Test
    @DisplayName("a scenario cannot be launched into a channel that cannot deliver: the refusal names the step")
    void launchIsRefusedWhereNothingIsWired() throws Exception {
        String id = draftScenario("Unwired", "[" + sms(0) + "]");
        approve(id);

        MvcResult refused = send(post(campaigns() + "/" + id + "/launches"), OWNER, "launch-1", null);

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString()).contains("step 1");
    }

    @Test
    @DisplayName(
            "decisions answer why a guest did not get a step, filter by guest, and results refuse what they cannot compute")
    void decisionsAndResults() throws Exception {
        String id = draftScenario("Measured", "[" + sms(0) + "]");
        UUID blocked = seedAccount();
        UUID sent = seedAccount();
        seedDecision(
                UUID.fromString(id),
                blocked,
                "BLOCKED",
                "CONSENT_WITHHELD",
                "Consent for MARKETING_PROMOTIONS on SMS is no longer granted");
        seedDecision(UUID.fromString(id), sent, "SENT", null, null);
        seedParticipant(UUID.fromString(id), blocked, false, "STOPPED_BY_CONSENT_WITHDRAWN");
        seedParticipant(UUID.fromString(id), sent, false, "COMPLETED");

        JsonNode all =
                json(mvc.perform(get(scenarios() + "/" + id + "/decisions").with(token(ADMINISTRATOR)))
                        .andReturn());
        assertThat(all).hasSize(2);
        JsonNode one = json(mvc.perform(get(scenarios() + "/" + id + "/decisions")
                        .param("accountId", blocked.toString())
                        .with(token(ADMINISTRATOR)))
                .andReturn());
        assertThat(one).singleElement().satisfies(row -> {
            assertThat(row.path("decision").asText()).isEqualTo("BLOCKED");
            assertThat(row.path("refusalReason").asText()).isEqualTo("CONSENT_WITHHELD");
            assertThat(row.path("reasonText").asText()).contains("no longer granted");
        });
        assertThat(status(get(scenarios() + "/" + id + "/decisions").param("limit", "0"), ADMINISTRATOR))
                .isEqualTo(400);

        JsonNode read = json(mvc.perform(get(scenarios() + "/" + id).with(token(ADMINISTRATOR)))
                .andReturn());
        assertThat(read.at("/participants/COMPLETED").asInt()).isEqualTo(1);
        assertThat(read.at("/decisions/SENT").asInt()).isEqualTo(1);
        assertThat(read.at("/decisions/CONSENT_WITHHELD").asInt()).isEqualTo(1);

        MvcResult results = mvc.perform(get(scenarios() + "/" + id + "/results")
                        .param("model", "LAST_TOUCH")
                        .param("windowDays", "7")
                        .with(token(ADMINISTRATOR)))
                .andReturn();
        assertThat(results.getResponse().getStatus()).isEqualTo(200);
        JsonNode summary = json(results);
        assertThat(summary.path("model").asText()).isEqualTo("LAST_TOUCH");
        assertThat(summary.path("windowDays").asInt()).isEqualTo(7);
        assertThat(summary.path("hasControlGroup").asBoolean()).isFalse();
        assertThat(summary.path("lift").isNull()).isTrue();
        assertThat(status(get(scenarios() + "/" + id + "/results").param("model", "BEST_TOUCH"), ADMINISTRATOR))
                .isEqualTo(400);
        assertThat(status(get(scenarios() + "/" + id + "/results").param("windowDays", "0"), ADMINISTRATOR))
                .isEqualTo(400);
        assertThat(status(get(scenarios() + "/" + id + "/results").param("windowDays", "91"), ADMINISTRATOR))
                .isEqualTo(400);
    }

    @Test
    @DisplayName("a scenario of another brand is not found by its id, and is absent from this brand's list")
    void scenariosAreScopedToTheirBrand() throws Exception {
        String id = draftScenario("Mine", "[" + sms(0) + "]");
        String otherScenarios = "/api/v1/tenants/" + TENANT + "/brands/" + OTHER_BRAND + "/marketing/scenarios";

        assertThat(status(get(otherScenarios + "/" + id), ADMINISTRATOR)).isEqualTo(404);
        assertThat(status(get(otherScenarios + "/" + id + "/decisions"), ADMINISTRATOR))
                .isEqualTo(404);
        assertThat(status(get(otherScenarios + "/" + id + "/results"), ADMINISTRATOR))
                .isEqualTo(404);
        assertThat(json(mvc.perform(get(otherScenarios).with(token(ADMINISTRATOR)))
                        .andReturn()))
                .isEmpty();
    }

    // ===================================================================== offers

    @Test
    @DisplayName(
            "an offer is drafted against exactly one promotion, published with the version that was read, and announced once")
    void offerLifecycle() throws Exception {
        UUID promotion = seedPromotion(BRAND, "ACTIVE");

        MvcResult created = send(post(offers()), ADMINISTRATOR, "offer-1", offerBody("Autumn ten", promotion, null));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getHeader("ETag")).isEqualTo("W/\"1\"");
        JsonNode draft = json(created);
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("versionNumber").asInt()).isEqualTo(1);
        assertThat(draft.path("pricingPromotionId").asText()).isEqualTo(promotion.toString());
        assertThat(draft.path("loyaltyAccrualRuleId").isNull()).isTrue();
        String id = draft.path("offerId").asText();

        // No If-Match, no publication; a version that has moved on, no publication either.
        assertThat(sendWith(post(offers() + "/" + id + "/publications"), ADMINISTRATOR, "publish-0", null, null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        MvcResult stale =
                sendWith(post(offers() + "/" + id + "/publications"), ADMINISTRATOR, "publish-stale", null, "\"9\"");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");

        MvcResult published =
                sendWith(post(offers() + "/" + id + "/publications"), ADMINISTRATOR, "publish-1", null, "\"1\"");
        assertThat(published.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(published).path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(json(published).path("publishedAt").isNull()).isFalse();
        assertThat(published.getResponse().getHeader("ETag")).isEqualTo("W/\"2\"");

        // A published version is not rewritten.
        MvcResult rewrite = sendWith(
                put(offers() + "/" + id), ADMINISTRATOR, "rewrite-1", offerBody("Renamed", promotion, null), "\"2\"");
        assertThat(rewrite.getResponse().getStatus()).isEqualTo(422);
        assertThat(rewrite.getResponse().getContentAsString()).contains("make a new version");

        // The fact is in the outbox, in identifiers only.
        assertThat(count(
                        "integration.outbox_events WHERE event_type = 'OfferPublished' AND topic = 'marketing.events'"))
                .isEqualTo(1);
        String payload = jdbc.sql(
                        "SELECT payload::text FROM integration.outbox_events WHERE event_type = 'OfferPublished'")
                .query(String.class)
                .single();
        assertThat(payload).contains(id).doesNotContain("Autumn ten");
    }

    @Test
    @DisplayName("a new version supersedes the published one only when it is published, and the lineage lists both")
    void offerVersions() throws Exception {
        UUID promotion = seedPromotion(BRAND, "ACTIVE");
        String first = publishOffer("Autumn ten", promotion);

        MvcResult next = send(
                post(offers() + "/" + first + "/versions"),
                ADMINISTRATOR,
                "version-1",
                offerBody("Autumn twelve", promotion, null));
        assertThat(next.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(next).path("versionNumber").asInt()).isEqualTo(2);
        String second = json(next).path("offerId").asText();
        assertThat(json(mvc.perform(get(offers() + "/" + first).with(token(ADMINISTRATOR)))
                                .andReturn())
                        .path("status")
                        .asText())
                .as("the draft does not displace what is in force")
                .isEqualTo("PUBLISHED");

        assertThat(sendWith(post(offers() + "/" + second + "/publications"), ADMINISTRATOR, "publish-2", null, "\"1\"")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        JsonNode lineage =
                json(mvc.perform(get(offers() + "/" + first + "/versions").with(token(ADMINISTRATOR)))
                        .andReturn());
        assertThat(lineage).hasSize(2);
        assertThat(statusOf(lineage, first)).isEqualTo("SUPERSEDED");
        assertThat(statusOf(lineage, second)).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName(
            "an offer states no discount: none or both references is a 400, and a reference that does not exist is refused")
    void offerReferences() throws Exception {
        UUID promotion = seedPromotion(BRAND, "ACTIVE");
        UUID rule = seedAccrualRule(BRAND, "ACTIVE");

        assertThat(send(post(offers()), ADMINISTRATOR, "ref-both", offerBody("Both", promotion, rule))
                        .getResponse()
                        .getContentAsString())
                .contains("exactly one");
        assertThat(send(post(offers()), ADMINISTRATOR, "ref-neither", offerBody("Neither", null, null))
                        .getResponse()
                        .getContentAsString())
                .contains("exactly one");
        MvcResult unknown =
                send(post(offers()), ADMINISTRATOR, "ref-unknown", offerBody("Unknown", UUID.randomUUID(), null));
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(unknown.getResponse().getContentAsString()).contains("No promotion");
        MvcResult archived = send(
                post(offers()),
                ADMINISTRATOR,
                "ref-archived",
                offerBody("Archived", seedPromotion(BRAND, "ARCHIVED"), null));
        assertThat(archived.getResponse().getContentAsString()).contains("archived");
        MvcResult sibling = send(
                post(offers()),
                ADMINISTRATOR,
                "ref-sibling",
                offerBody("Sibling", seedPromotion(OTHER_BRAND, "ACTIVE"), null));
        assertThat(sibling.getResponse().getContentAsString()).contains("No promotion");

        // The accrual-rule form works too.
        MvcResult byRule = send(post(offers()), ADMINISTRATOR, "ref-rule", offerBody("Points", null, rule));
        assertThat(byRule.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(byRule).path("loyaltyAccrualRuleId").asText()).isEqualTo(rule.toString());
        assertThat(count("marketing.offers")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "retiring an offer needs the version and a reason, and a retired offer cannot be named by a new scenario")
    void retirement() throws Exception {
        UUID promotion = seedPromotion(BRAND, "ACTIVE");
        String id = publishOffer("Autumn ten", promotion);

        assertThat(sendWith(
                                post(offers() + "/" + id + "/retirements"),
                                ADMINISTRATOR,
                                "retire-blank",
                                "{\"reason\":\" \"}",
                                "\"2\"")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        MvcResult retired = sendWith(
                post(offers() + "/" + id + "/retirements"),
                ADMINISTRATOR,
                "retire-1",
                "{\"reason\":\"The promotion ended early\"}",
                "\"2\"");
        assertThat(retired.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(retired).path("status").asText()).isEqualTo("RETIRED");

        MvcResult named = send(
                post(scenarios()),
                ADMINISTRATOR,
                "scenario-retired-offer",
                scenarioBody("Uses a retired offer", "[" + step("IN_APP", null, 0, id) + "," + sms(0) + "]"));
        assertThat(named.getResponse().getStatus()).isEqualTo(400);
        assertThat(named.getResponse().getContentAsString()).contains("only a published offer");
    }

    @Test
    @DisplayName("offers are the brand administrator's: an owner is refused, and another brand does not see them")
    void offerAuthorizationAndScope() throws Exception {
        assertThat(send(
                                post(offers()),
                                OWNER,
                                "offer-owner",
                                offerBody("Refused", seedPromotion(BRAND, "ACTIVE"), null))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        String id = publishOffer("Mine", seedPromotion(BRAND, "ACTIVE"));
        String otherOffers = "/api/v1/tenants/" + TENANT + "/brands/" + OTHER_BRAND + "/marketing/offers";

        assertThat(status(get(otherOffers + "/" + id), ADMINISTRATOR)).isEqualTo(404);
        assertThat(json(mvc.perform(get(otherOffers).with(token(ADMINISTRATOR))).andReturn()))
                .isEmpty();
        assertThat(json(mvc.perform(get(offers()).with(token(ADMINISTRATOR))).andReturn()))
                .hasSize(1);
    }

    // =========================================================== contact policy

    @Test
    @DisplayName("the platform's bounds are readable, and the defaults a console needs come with them")
    void contactPolicyBoundsAndDefaults() throws Exception {
        JsonNode policy = json(
                mvc.perform(get(contactPolicy()).with(token(ADMINISTRATOR))).andReturn());
        assertThat(policy.at("/platform/dailyCapCeiling").asInt()).isEqualTo(3);
        assertThat(policy.at("/platform/rolling30DayCapCeiling").asInt()).isEqualTo(8);
        assertThat(policy.at("/platform/quietHoursStartNoLaterThan").toString()).contains("21");
        assertThat(policy.path("overrides")).isEmpty();

        JsonNode defaults = json(mvc.perform(get(contactPolicy() + "/defaults").with(token(ADMINISTRATOR)))
                .andReturn());
        assertThat(defaults.path("inAppShowCapPerDay").asInt()).isEqualTo(3);
        assertThat(defaults.path("controlGroupPercentDefault").asInt()).isEqualTo(10);
        assertThat(defaults.path("channelPriorityOrder")).isEmpty();
    }

    @Test
    @DisplayName(
            "an override is created, replaced with the version that was read, and removed with a reason: tighten-only throughout")
    void contactPolicyOverrides() throws Exception {
        String cap = "{\"channel\":\"SMS\",\"campaignPurpose\":\"MARKETING_PROMOTIONS\",\"period\":\"DAILY\","
                + "\"capCount\":2,\"statedReason\":\"Two a day is plenty\"}";
        MvcResult created = send(put(contactPolicy() + "/overrides"), ADMINISTRATOR, "policy-1", cap);
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        assertThat(created.getResponse().getHeader("ETag")).isEqualTo("W/\"1\"");
        assertThat(json(created).path("capCount").asInt()).isEqualTo(2);
        assertThat(json(created).path("quietHoursStart").isNull()).isTrue();

        // A second create is a conflict; a replace needs the version.
        assertThat(send(put(contactPolicy() + "/overrides"), ADMINISTRATOR, "policy-2", cap)
                        .getResponse()
                        .getStatus())
                .isEqualTo(409);
        String tighter = cap.replace("\"capCount\":2", "\"capCount\":1").replace("Two a day", "One a day");
        MvcResult stale = sendWith(put(contactPolicy() + "/overrides"), ADMINISTRATOR, "policy-3", tighter, "\"5\"");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");
        MvcResult replaced = sendWith(put(contactPolicy() + "/overrides"), ADMINISTRATOR, "policy-4", tighter, "\"1\"");
        assertThat(replaced.getResponse().getStatus()).isEqualTo(200);
        assertThat(replaced.getResponse().getHeader("ETag")).isEqualTo("W/\"2\"");

        // Loosening is a 400 that names the platform's number, whatever the period.
        MvcResult loosened = send(
                put(contactPolicy() + "/overrides"),
                ADMINISTRATOR,
                "policy-5",
                "{\"channel\":\"SMS\",\"campaignPurpose\":\"P\",\"period\":\"WEEKLY\",\"capCount\":4,\"statedReason\":\"More\"}");
        assertThat(loosened.getResponse().getStatus()).isEqualTo(400);
        assertThat(loosened.getResponse().getContentAsString())
                .contains("never loosened")
                .contains("platform's 3");
        MvcResult quiet = send(
                put(contactPolicy() + "/overrides"),
                ADMINISTRATOR,
                "policy-6",
                "{\"channel\":\"SMS\",\"campaignPurpose\":\"P\",\"period\":\"DAILY\",\"quietHoursStart\":\"22:00\","
                        + "\"quietHoursEnd\":\"09:00\",\"statedReason\":\"Later evenings\"}");
        assertThat(quiet.getResponse().getStatus()).isEqualTo(400);
        // Quiet hours alone, as a console sends them with no cap at all, are accepted when they only widen.
        MvcResult widened = send(
                put(contactPolicy() + "/overrides"),
                ADMINISTRATOR,
                "policy-7",
                "{\"channel\":\"EMAIL\",\"campaignPurpose\":\"P\",\"period\":\"DAILY\",\"quietHoursStart\":\"20:00\","
                        + "\"quietHoursEnd\":\"11:00\",\"statedReason\":\"Evenings are for dinner\"}");
        assertThat(widened.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(widened).path("capCount").isNull()).isTrue();

        assertThat(json(mvc.perform(get(contactPolicy()).with(token(ADMINISTRATOR)))
                                .andReturn())
                        .path("overrides"))
                .hasSize(2);

        // Removal wants a reason, and the audit trail keeps it.
        assertThat(sendWith(
                                delete(contactPolicy() + "/overrides/SMS/MARKETING_PROMOTIONS/DAILY"),
                                ADMINISTRATOR,
                                "policy-del-0",
                                null,
                                null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        MvcResult removed = mvc.perform(delete(contactPolicy() + "/overrides/SMS/MARKETING_PROMOTIONS/DAILY")
                        .param("reason", "Policy review")
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "policy-del-1")
                        .with(token(ADMINISTRATOR)))
                .andReturn();
        assertThat(removed.getResponse().getStatus()).isEqualTo(204);
        assertThat(json(mvc.perform(get(contactPolicy()).with(token(ADMINISTRATOR)))
                                .andReturn())
                        .path("overrides"))
                .hasSize(1);
        assertThat(jdbc.sql(
                                "SELECT reason FROM audit.audit_events WHERE action_code = 'MARKETING_CONTACT_POLICY_REMOVED'")
                        .query(String.class)
                        .single())
                .isEqualTo("Policy review");
    }

    @Test
    @DisplayName(
            "the contact policy is the brand administrator's to change: an owner may not, and another brand's is separate")
    void contactPolicyAuthorizationAndScope() throws Exception {
        String body =
                "{\"channel\":\"SMS\",\"campaignPurpose\":\"P\",\"period\":\"DAILY\",\"capCount\":1,\"statedReason\":\"r\"}";
        MvcResult refused = send(put(contactPolicy() + "/overrides"), OWNER, "policy-owner", body);
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains("marketing.contact_policy.manage");

        assertThat(send(put(contactPolicy() + "/overrides"), ADMINISTRATOR, "policy-mine", body)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        String other = "/api/v1/tenants/" + TENANT + "/brands/" + OTHER_BRAND + "/marketing/contact-policy";
        assertThat(json(mvc.perform(get(other).with(token(ADMINISTRATOR))).andReturn())
                        .path("overrides"))
                .isEmpty();
    }

    // ======================================================= storefront banners

    @Test
    @DisplayName(
            "a signed-in customer polls for their banners, each poll counts against the day's cap, and a dismissed banner stays dismissed")
    void storefrontBanners() throws Exception {
        SignedIn customer = signIn();
        UUID offer = seedPublishedOffer("Autumn ten");
        UUID presentedId = seedPresentedOffer(offer, customer.accountId());
        String path = storefront();

        MvcResult first = mvc.perform(get(path).with(session(customer.token()))).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode banners = json(first);
        assertThat(banners).singleElement().satisfies(banner -> {
            assertThat(banner.path("presentedOfferId").asText()).isEqualTo(presentedId.toString());
            assertThat(banner.path("offerId").asText()).isEqualTo(offer.toString());
            assertThat(banner.path("name").asText()).isEqualTo("Autumn ten");
            assertThat(banner.path("priority").asInt()).isZero();
        });

        // Three showings a day by default: the third is the last.
        assertThat(json(mvc.perform(get(path).with(session(customer.token()))).andReturn()))
                .hasSize(1);
        assertThat(json(mvc.perform(get(path).with(session(customer.token()))).andReturn()))
                .hasSize(1);
        assertThat(json(mvc.perform(get(path).with(session(customer.token()))).andReturn()))
                .as("the fourth poll of the day")
                .isEmpty();

        assertThat(status(get(path).param("surface", "BILLBOARD"), session(customer.token())))
                .isEqualTo(400);
    }

    @Test
    @DisplayName(
            "dismissing a banner is idempotent in effect, refused the second time, and needs the customer's own session")
    void storefrontDismissal() throws Exception {
        SignedIn customer = signIn();
        UUID offer = seedPublishedOffer("Autumn ten");
        UUID presentedId = seedPresentedOffer(offer, customer.accountId());
        String dismissal = storefront() + "/" + presentedId + "/dismissals";

        MvcResult anonymous = mvc.perform(
                        post(dismissal).header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "dismiss-0"))
                .andReturn();
        assertThat(anonymous.getResponse().getStatus()).isIn(401, 403);

        MvcResult dismissed = mvc.perform(post(dismissal)
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "dismiss-1")
                        .with(session(customer.token())))
                .andReturn();
        assertThat(dismissed.getResponse().getStatus()).isEqualTo(204);
        MvcResult again = mvc.perform(post(dismissal)
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "dismiss-2")
                        .with(session(customer.token())))
                .andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(404);

        assertThat(json(mvc.perform(get(storefront()).with(session(customer.token())))
                        .andReturn()))
                .isEmpty();
    }

    @Test
    @DisplayName(
            "a session for one brand shows nothing under another brand's path, and another tenant's path is refused")
    void storefrontIsScopedToTheSessionsBrand() throws Exception {
        SignedIn customer = signIn();
        UUID offer = seedPublishedOffer("Autumn ten");
        seedPresentedOffer(offer, customer.accountId());
        String otherBrand = "/api/v1/storefront/tenants/" + TENANT + "/brands/" + OTHER_BRAND + "/presented-offers";

        // The account is the tenant's, but a banner is a brand's: under another brand's path the
        // same session is answered with nothing, not with the first brand's offer.
        MvcResult elsewhere =
                mvc.perform(get(otherBrand).with(session(customer.token()))).andReturn();
        assertThat(elsewhere.getResponse().getStatus()).isIn(200, 404);
        if (elsewhere.getResponse().getStatus() == 200) {
            assertThat(json(elsewhere)).isEmpty();
        }
        assertThat(json(mvc.perform(get(storefront()).with(session(customer.token())))
                        .andReturn()))
                .as("and the banner is still there under its own brand")
                .hasSize(1);

        // Another tenant's path is not this session's to read at all.
        String otherTenant =
                "/api/v1/storefront/tenants/" + UUID.randomUUID() + "/brands/" + BRAND + "/presented-offers";
        assertThat(mvc.perform(get(otherTenant).with(session(customer.token())))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isIn(401, 403, 404);
    }

    // ================================================================= events

    @Test
    @DisplayName(
            "the engine's facts reach the outbox in the transaction that decided them, in identifiers only, and a rolled-back decision leaves none")
    void engineEventsReachTheOutbox() {
        UUID campaign = UUID.randomUUID();
        UUID brand = BRAND;
        UUID guest = UUID.randomUUID();

        transactions.executeWithoutResult(status -> {
            publisher.publishEvent(new ScenarioStepDecided(
                    Ids.newId(), TENANT, brand, campaign, guest, 2, "BLOCKED", "CONSENT_WITHHELD", Instant.now()));
            publisher.publishEvent(new ScenarioParticipantStopped(
                    Ids.newId(), TENANT, brand, campaign, guest, "STOPPED_BY_CONSENT_WITHDRAWN", Instant.now()));
        });
        transactions.executeWithoutResult(status -> {
            publisher.publishEvent(new ScenarioStepDecided(
                    Ids.newId(), TENANT, brand, campaign, guest, 3, "SENT", null, Instant.now()));
            status.setRollbackOnly();
        });

        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT event_type, topic, partition_key, payload::text AS payload
                          FROM integration.outbox_events ORDER BY event_type
                        """).query().listOfRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("topic")).isEqualTo("marketing.events");
            assertThat(row.get("partition_key")).isEqualTo(campaign.toString());
            assertThat(String.valueOf(row.get("payload")))
                    .contains(guest.toString())
                    .doesNotContain("+998");
        });
        assertThat(rows.stream().map(row -> row.get("event_type")).toList())
                .containsExactly("ScenarioParticipantStopped", "ScenarioStepDecided");
        assertThat(String.valueOf(rows.get(1).get("payload")))
                .contains("CONSENT_WITHHELD")
                .doesNotContain("\"stepSequence\":3");
    }

    // =============================================================== helpers

    private String scenarios() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/marketing/scenarios";
    }

    private String campaigns() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/marketing/campaigns";
    }

    private String offers() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/marketing/offers";
    }

    private String contactPolicy() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/marketing/contact-policy";
    }

    private String storefront() {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/presented-offers";
    }

    private static String sms(int waitSeconds) {
        return step("SMS", "MARKETING_PROMOTION", waitSeconds);
    }

    private static String step(String channel, @Nullable String template, int waitSeconds) {
        return step(channel, template, waitSeconds, null);
    }

    private static String step(String channel, @Nullable String template, int waitSeconds, @Nullable String offerId) {
        StringBuilder step = new StringBuilder("{\"channel\":\"" + channel + "\"");
        if (template != null) {
            step.append(",\"templateKey\":\"").append(template).append('"');
        }
        if (offerId != null) {
            step.append(",\"offerId\":\"").append(offerId).append('"');
        }
        return step.append(",\"waitAfterPreviousSeconds\":")
                .append(waitSeconds)
                .append('}')
                .toString();
    }

    private static String scenarioBody(String name, String steps) {
        return scenarioBodyWith(name, steps, "100000", null);
    }

    /** The body a console sends: optional fields are omitted when null, never sent as a primitive default. */
    private static String scenarioBodyWith(
            String name, String steps, @Nullable String ceiling, @Nullable String controlGroupPercent) {
        StringBuilder body = new StringBuilder("{\"name\":\"" + name + "\",\"audienceId\":\"" + SEED_AUDIENCE
                + "\",\"consentPurpose\":\"MARKETING_PROMOTIONS\",\"recipientCap\":1000,\"currency\":\"UZS\"");
        if (ceiling != null && !ceiling.equals("null")) {
            body.append(",\"costCeilingMinor\":").append(ceiling);
        }
        if (controlGroupPercent != null && !controlGroupPercent.equals("null")) {
            body.append(",\"controlGroupPercent\":").append(controlGroupPercent);
        }
        return body.append(",\"steps\":").append(steps).append('}').toString();
    }

    private static String offerBody(String name, @Nullable UUID promotion, @Nullable UUID rule) {
        StringBuilder body = new StringBuilder("{\"displayName\":\"" + name + "\"");
        if (promotion != null) {
            body.append(",\"pricingPromotionId\":\"").append(promotion).append('"');
        }
        if (rule != null) {
            body.append(",\"loyaltyAccrualRuleId\":\"").append(rule).append('"');
        }
        return body.append(",\"validFrom\":\"")
                .append(Instant.now().minus(Duration.ofDays(1)))
                .append("\",\"validUntil\":\"")
                .append(Instant.now().plus(Duration.ofDays(30)))
                .append("\",\"allowedChannels\":[\"IN_APP\"],\"templateKey\":\"MARKETING_PROMOTION\"}")
                .toString();
    }

    private String draftScenario(String name, String steps) throws Exception {
        MvcResult created =
                send(post(scenarios()), ADMINISTRATOR, "draft-" + UUID.randomUUID(), scenarioBody(name, steps));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return json(created).at("/campaign/campaignId").asText();
    }

    /** Estimate, submit (as the author) and approve (as the owner), the way the console does. */
    private void approve(String campaignId) throws Exception {
        String base = campaigns() + "/" + campaignId;
        assertThat(send(post(base + "/estimates"), ADMINISTRATOR, "est-" + campaignId, null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(send(post(base + "/submissions"), ADMINISTRATOR, "sub-" + campaignId, null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(202);
        assertThat(send(post(base + "/approvals"), OWNER, "app-" + campaignId, "{\"reason\":\"Reviewed the steps\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(202);
    }

    private String publishOffer(String name, UUID promotion) throws Exception {
        MvcResult created =
                send(post(offers()), ADMINISTRATOR, "offer-" + UUID.randomUUID(), offerBody(name, promotion, null));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String id = json(created).path("offerId").asText();
        MvcResult published =
                sendWith(post(offers() + "/" + id + "/publications"), ADMINISTRATOR, "pub-" + id, null, "\"1\"");
        assertThat(published.getResponse().getStatus()).isEqualTo(200);
        return id;
    }

    private void assertBadRequest(String body, String mentions) throws Exception {
        MvcResult refused = send(post(scenarios()), ADMINISTRATOR, "bad-" + UUID.randomUUID(), body);
        assertThat(refused.getResponse().getStatus()).as(body).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).as(body).contains(mentions);
    }

    private static String statusOf(JsonNode versions, String offerId) {
        for (JsonNode version : versions) {
            if (version.path("offerId").asText().equals(offerId)) {
                return version.path("status").asText();
            }
        }
        return "absent";
    }

    private MvcResult send(MockHttpServletRequestBuilder request, String subject, String key, @Nullable String body)
            throws Exception {
        return sendWith(request, subject, key, body, null);
    }

    private MvcResult sendWith(
            MockHttpServletRequestBuilder request,
            String subject,
            String key,
            @Nullable String body,
            @Nullable String ifMatch)
            throws Exception {
        request.header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key).with(token(subject));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn();
    }

    private int status(MockHttpServletRequestBuilder request, String subject) throws Exception {
        return mvc.perform(request.with(token(subject)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int status(MockHttpServletRequestBuilder request, RequestPostProcessor credential) throws Exception {
        return mvc.perform(request.with(credential)).andReturn().getResponse().getStatus();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int count(String fromAndWhere) {
        return jdbc.sql("SELECT count(*) FROM " + fromAndWhere)
                .query(Integer.class)
                .single();
    }

    // ------------------------------------------------------------------ customers

    private record SignedIn(String token, UUID accountId) {}

    /** The whole customer journey: ask for a code, type it, exchange the grant (ADR 0051). */
    private SignedIn signIn() throws Exception {
        String identity = "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/identity";
        MvcResult challenge = mvc.perform(post(identity + "/verification-challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PRESET_PHONE + "\"}"))
                .andReturn();
        assertThat(challenge.getResponse().getStatus()).isEqualTo(202);
        String challengeId = json(challenge).path("challengeId").asText();

        MvcResult attempt = mvc.perform(post(identity + "/verification-challenges/" + challengeId + "/attempts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + PRESET_CODE + "\"}"))
                .andReturn();
        assertThat(attempt.getResponse().getStatus()).isEqualTo(200);
        String grant = json(attempt).path("grant").asText();

        MvcResult session = mvc.perform(post(identity + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grant\":\"" + grant + "\"}"))
                .andReturn();
        assertThat(session.getResponse().getStatus()).isIn(200, 201);
        JsonNode body = json(session);
        return new SignedIn(
                body.path("token").asText(),
                UUID.fromString(body.path("accountId").asText()));
    }

    private static RequestPostProcessor session(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    // -------------------------------------------------------------------- fixtures

    private void seedFixtures() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'marketing-scenario-endpoint', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'OTHER', 'other', 'Other brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO marketing.audiences (id, tenant_id, brand_id, name, created_by)
                VALUES (:id, :tenantId, :brandId, 'Everybody', :createdBy)
                """)
                .param("id", SEED_AUDIENCE)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("createdBy", UUID.randomUUID())
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'marketing scenario endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(StandardCharsets.UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private UUID seedAccount() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, preferred_locale, created_at)
                VALUES (:id, :tenantId, 'ACTIVE', 'ru', now())
                """).param("id", id).param("tenantId", TENANT).update();
        return id;
    }

    private UUID seedPromotion(UUID brandId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO pricing.promotions (
                    id, tenant_id, brand_id, code, name, scope, status, stacking_group,
                    requires_coupon, currency, valid_from, validated_at, activated_at)
                VALUES (:id, :tenantId, :brandId, :code, 'Autumn 10', 'ORDER', :status, 'default',
                    false, 'UZS', :validFrom, :validFrom, :validFrom)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("code", "P" + id.toString().substring(0, 8))
                .param("status", status)
                .param("validFrom", Instant.now().minus(Duration.ofDays(2)).atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    private UUID seedAccrualRule(UUID brandId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO loyalty.accrual_rules (id, tenant_id, brand_id, scope_type,
                    rate_basis_points, max_accrual_minor, earn_delay_hours, lot_lifetime_days,
                    expiry_warning_days, status, version, valid_from)
                VALUES (:id, :tenantId, :brandId, 'BRAND', 300, 30000, 24, 180, 14, :status, 1, :validFrom)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("status", status)
                .param("validFrom", Instant.now().minus(Duration.ofDays(2)).atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    private UUID seedPublishedOffer(String name) {
        UUID promotion = seedPromotion(BRAND, "ACTIVE");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO marketing.offers (id, tenant_id, brand_id, lineage_id, version_number, status,
                    display_name, pricing_promotion_id, valid_from, allowed_channels, template_key,
                    created_by, published_by, published_at)
                VALUES (:id, :tenantId, :brandId, :lineage, 1, 'PUBLISHED', :name, :promotion,
                    now() - interval '1 day', ARRAY['IN_APP'], 'MARKETING_PROMOTION', :by, :by, now())
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("lineage", UUID.randomUUID())
                .param("name", name)
                .param("promotion", promotion)
                .param("by", UUID.randomUUID())
                .update();
        return id;
    }

    private UUID seedPresentedOffer(UUID offerId, UUID accountId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO marketing.presented_offers (id, tenant_id, brand_id, offer_id, customer_account_id, surface)
                VALUES (:id, :tenantId, :brandId, :offerId, :accountId, 'STOREFRONT')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("offerId", offerId)
                .param("accountId", accountId)
                .update();
        return id;
    }

    private void seedDecision(
            UUID campaignId, UUID accountId, String decision, @Nullable String reason, @Nullable String text) {
        jdbc.sql("""
                INSERT INTO marketing.scenario_step_decisions (id, tenant_id, brand_id, campaign_id,
                    customer_account_id, step_sequence, decision, refusal_reason, reason_text, decided_at)
                VALUES (:id, :tenantId, :brandId, :campaignId, :accountId, 1, :decision, :reason, :text, :at)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("campaignId", campaignId)
                .param("accountId", accountId)
                .param("decision", decision)
                .param("reason", reason)
                .param("text", text)
                .param("at", OffsetDateTime.now())
                .update();
    }

    private void seedParticipant(UUID campaignId, UUID accountId, boolean control, String outcome) {
        jdbc.sql("""
                INSERT INTO marketing.scenario_participant_state (tenant_id, brand_id, campaign_id,
                    customer_account_id, in_control_group, outcome, entered_at, updated_at)
                VALUES (:tenantId, :brandId, :campaignId, :accountId, :control, :outcome, now(), now())
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("campaignId", campaignId)
                .param("accountId", accountId)
                .param("control", control)
                .param("outcome", outcome)
                .update();
    }
}
