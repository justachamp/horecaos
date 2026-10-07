package uz.horecaos.platform.assistant.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
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

/**
 * The assistant's two operations surfaces over real HTTP, through the real
 * interceptor chain and the role-to-capability grants {@code PlatformRole}
 * produces (ADR 0025, ADR 0031): who may write what the assistant tells
 * customers, that an entry is never edited in place, and that spend is visible.
 *
 * <p>Bodies are the JSON the console actually sends -- only the fields it fills
 * in -- because Jackson 3 refuses a missing primitive with a 400 and a test that
 * built the request from the DTO would never see it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssistantControllersEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-4000-7000-8000-0000000000c1");
    private static final UUID BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000d1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9b20-4000-7000-8000-0000000000d2");
    private static final UUID LOCATION = UUID.fromString("018f9b20-4000-7000-8000-0000000000e1");

    private static final String OWNER = "assistant-owner";
    private static final String BRAND_MANAGER = "assistant-brand-manager";
    private static final String FINANCE = "assistant-finance";

    private static final String TENANT_KNOWLEDGE = "/api/v1/operations/tenants/" + TENANT + "/assistant/knowledge";
    private static final String BRAND_KNOWLEDGE =
            "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/assistant/knowledge";

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

    private int keys;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql(
                        "TRUNCATE TABLE assistant.turns, assistant.knowledge_entry_versions, assistant.knowledge_entries CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        seedTenancy();
        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        grant(BRAND_MANAGER, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        grant(FINANCE, PlatformRole.TENANT_FINANCE, "TENANT", TENANT);
    }

    private static final String NEW_ENTRY = """
            {"locale":"en","questionForm":"Is there parking at your restaurant?",
             "answerBody":"Yes, free parking behind the building.","reason":"Frequently asked"}""";

    private MvcResult send(String path, String subject, String body, @Nullable String ifMatch) throws Exception {
        var request = post(path)
                .with(tokenFor(subject))
                .header("Idempotency-Key", "assistant-key-" + (++keys))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(UTF_8));
    }

    // =================================================================== authoring

    @Test
    @DisplayName(
            "a tenant owner creates an entry, publishes its next version under If-Match, reads its history and retires it")
    void theWholeLifeOfAnEntry() throws Exception {
        MvcResult created = send(BRAND_KNOWLEDGE, OWNER, NEW_ENTRY, null);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getHeader("ETag")).isEqualTo("W/\"1\"");
        JsonNode entry = body(created);
        String id = entry.get("id").asString();
        assertThat(entry.get("scope").asString()).isEqualTo("BRAND");
        assertThat(entry.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(entry.get("authoredBy").asString()).isEqualTo(OWNER);

        MvcResult listed =
                mvc.perform(get(BRAND_KNOWLEDGE).with(tokenFor(OWNER))).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(listed)).hasSize(1);

        MvcResult published = send(BRAND_KNOWLEDGE + "/" + id + "/versions", OWNER, """
                {"questionForm":"Is there parking at your restaurant?","answerBody":"Yes, free parking, 30 spaces.","reason":"Counted"}""", "W/\"1\"");
        assertThat(published.getResponse().getStatus()).isEqualTo(200);
        assertThat(published.getResponse().getHeader("ETag")).isEqualTo("W/\"2\"");
        assertThat(body(published).get("answerBody").asString()).isEqualTo("Yes, free parking, 30 spaces.");

        MvcResult staleWrite = send(BRAND_KNOWLEDGE + "/" + id + "/versions", OWNER, """
                {"questionForm":"Is there parking at your restaurant?","answerBody":"Lost update.","reason":"Stale"}""", "W/\"1\"");
        assertThat(staleWrite.getResponse().getStatus()).isEqualTo(409);
        assertThat(staleWrite.getResponse().getContentAsString(UTF_8)).contains("STALE_VERSION");

        MvcResult history = mvc.perform(
                        get(BRAND_KNOWLEDGE + "/" + id + "/versions").with(tokenFor(OWNER)))
                .andReturn();
        JsonNode versions = body(history);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(1).get("answerBody").asString())
                .as("the words version 1 said are still exactly what version 1 said")
                .isEqualTo("Yes, free parking behind the building.");
        assertThat(versions.get(0).get("reason").asString()).isEqualTo("Counted");

        MvcResult retired =
                send(BRAND_KNOWLEDGE + "/" + id + "/retirements", OWNER, "{\"reason\":\"Closed the lot\"}", "W/\"2\"");
        assertThat(retired.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(retired).get("status").asString()).isEqualTo("RETIRED");
    }

    @Test
    @DisplayName(
            "a mutating call without an Idempotency-Key, or a publish without If-Match, is refused before anything is written")
    void replaySafetyIsEnforced() throws Exception {
        MvcResult noKey = mvc.perform(post(BRAND_KNOWLEDGE)
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NEW_ENTRY))
                .andReturn();
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(noKey.getResponse().getContentAsString(UTF_8)).contains("IDEMPOTENCY_KEY_REQUIRED");

        String id =
                body(send(BRAND_KNOWLEDGE, OWNER, NEW_ENTRY, null)).get("id").asString();
        MvcResult noIfMatch = send(BRAND_KNOWLEDGE + "/" + id + "/versions", OWNER, """
                {"questionForm":"Is there parking?","answerBody":"Yes.","reason":"x"}""", null);
        assertThat(noIfMatch.getResponse().getStatus()).isEqualTo(400);

        Long entries = jdbc.sql("SELECT count(*) FROM assistant.knowledge_entries")
                .query(Long.class)
                .single();
        assertThat(entries).isEqualTo(1);
    }

    @Test
    @DisplayName("the same Idempotency-Key replays the same entry and writes it once")
    void anIdempotentReplayWritesOnce() throws Exception {
        var request = post(BRAND_KNOWLEDGE)
                .with(tokenFor(OWNER))
                .header("Idempotency-Key", "same-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(NEW_ENTRY);

        MvcResult first = mvc.perform(request).andReturn();
        MvcResult second = mvc.perform(request).andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getContentAsString(UTF_8))
                .isEqualTo(first.getResponse().getContentAsString(UTF_8));
        assertThat(jdbc.sql("SELECT count(*) FROM assistant.knowledge_entries")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a location entry names its branch, and a branch of another brand is not found")
    void locationScope() throws Exception {
        MvcResult atBranch = send(
                BRAND_KNOWLEDGE,
                OWNER,
                "{\"locationId\":\"" + LOCATION + "\",\"locale\":\"ru\",\"questionForm\":\"Есть ли парковка?\","
                        + "\"answerBody\":\"Да, во дворе.\",\"reason\":\"FAQ\"}",
                null);
        assertThat(atBranch.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(atBranch).get("scope").asString()).isEqualTo("LOCATION");
        assertThat(body(atBranch).get("locationId").asString()).isEqualTo(LOCATION.toString());

        MvcResult foreign = send(
                BRAND_KNOWLEDGE,
                OWNER,
                "{\"locationId\":\"" + UUID.randomUUID()
                        + "\",\"locale\":\"en\",\"questionForm\":\"Is there parking?\","
                        + "\"answerBody\":\"Yes.\",\"reason\":\"FAQ\"}",
                null);
        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName(
            "tenant-wide entries are written at tenant scope, and are not reachable from a brand's path or the other way round")
    void tenantAndBrandPathsAreSeparate() throws Exception {
        String tenantWide =
                body(send(TENANT_KNOWLEDGE, OWNER, NEW_ENTRY, null)).get("id").asString();
        String branded =
                body(send(BRAND_KNOWLEDGE, OWNER, NEW_ENTRY, null)).get("id").asString();

        assertThat(mvc.perform(get(BRAND_KNOWLEDGE + "/" + tenantWide).with(tokenFor(OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(get(TENANT_KNOWLEDGE + "/" + branded).with(tokenFor(OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(body(mvc.perform(get(TENANT_KNOWLEDGE).with(tokenFor(OWNER))).andReturn()))
                .hasSize(1);
        assertThat(body(mvc.perform(get(BRAND_KNOWLEDGE).with(tokenFor(OWNER))).andReturn()))
                .hasSize(1);

        // An entry of this brand is not reachable through a sibling brand's path either.
        MvcResult sibling = mvc.perform(get("/api/v1/operations/tenants/" + TENANT + "/brands/" + OTHER_BRAND
                                + "/assistant/knowledge/" + branded)
                        .with(tokenFor(OWNER)))
                .andReturn();
        assertThat(sibling.getResponse().getStatus()).isEqualTo(404);
    }

    // ================================================================ authorization

    @Test
    @DisplayName("finance holds neither capability and is refused with the one it lacked, on every verb")
    void financeIsRefused() throws Exception {
        MvcResult read =
                mvc.perform(get(BRAND_KNOWLEDGE).with(tokenFor(FINANCE))).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(403);
        assertThat(read.getResponse().getContentAsString(UTF_8))
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.ASSISTANT_READ.code());

        MvcResult write = send(BRAND_KNOWLEDGE, FINANCE, NEW_ENTRY, null);
        assertThat(write.getResponse().getStatus()).isEqualTo(403);
        assertThat(write.getResponse().getContentAsString(UTF_8))
                .contains(Capability.ASSISTANT_KNOWLEDGE_MANAGE.code());

        MvcResult usage = mvc.perform(get("/api/v1/operations/tenants/" + TENANT + "/assistant/usage")
                        .with(tokenFor(FINANCE)))
                .andReturn();
        assertThat(usage.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM assistant.knowledge_entries")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("a brand manager authors at their own brand and cannot write tenant-wide")
    void aBrandManagersReach() throws Exception {
        assertThat(send(BRAND_KNOWLEDGE, BRAND_MANAGER, NEW_ENTRY, null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(201);

        MvcResult tenantWide = send(TENANT_KNOWLEDGE, BRAND_MANAGER, NEW_ENTRY, null);
        assertThat(tenantWide.getResponse().getStatus()).isEqualTo(403);

        MvcResult otherBrand = send(
                "/api/v1/operations/tenants/" + TENANT + "/brands/" + OTHER_BRAND + "/assistant/knowledge",
                BRAND_MANAGER,
                NEW_ENTRY,
                null);
        assertThat(otherBrand.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("a body that fails validation is a 400 naming nothing it should not, and writes nothing")
    void validation() throws Exception {
        MvcResult noAnswer = send(
                BRAND_KNOWLEDGE,
                OWNER,
                "{\"locale\":\"en\",\"questionForm\":\"Is there parking?\",\"reason\":\"x\"}",
                null);
        assertThat(noAnswer.getResponse().getStatus()).isEqualTo(400);

        MvcResult badLocale = send(
                BRAND_KNOWLEDGE,
                OWNER,
                "{\"locale\":\"fr\",\"questionForm\":\"Is there parking?\",\"answerBody\":\"Oui\",\"reason\":\"x\"}",
                null);
        assertThat(badLocale.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM assistant.knowledge_entries")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // ===================================================================== usage

    @Test
    @DisplayName(
            "the usage screen reads the same ledger the ceiling is decided from, and says whether the ceiling is reached")
    void usage() throws Exception {
        String path = "/api/v1/operations/tenants/" + TENANT + "/assistant/usage";
        JsonNode fresh = body(mvc.perform(get(path).with(tokenFor(OWNER))).andReturn());
        assertThat(fresh.get("turns").asLong()).isZero();
        assertThat(fresh.get("ceilingUsdCents").asLong()).isEqualTo(2_500);
        assertThat(fresh.get("ceilingReached").asBoolean()).isFalse();
        assertThat(fresh.get("switchedOn").asBoolean())
                .as("the assistant ships off")
                .isFalse();
        assertThat(fresh.get("entitled").asBoolean())
                .as("under the pilot's meter-only enforcement a feature check cannot refuse (ADR 0021)")
                .isTrue();
        assertThat(fresh.get("providerConfigured").asBoolean())
                .as("no provider key is stored yet")
                .isFalse();

        Instant now = Instant.now();
        insertTurn("ANSWERED", 24_000_000L, 1_000, 100, now.minus(Duration.ofSeconds(5)), false);
        insertTurn("ANSWERED", 0, 0, 0, now.minus(Duration.ofSeconds(4)), true);
        insertTurn("REFUSED", 1_000_000L, 800, 20, now.minus(Duration.ofSeconds(3)), false);
        insertTurn("ESCALATED", 0, 0, 0, now.minus(Duration.ofSeconds(2)), false);

        JsonNode busy = body(mvc.perform(get(path).with(tokenFor(OWNER))).andReturn());
        assertThat(busy.get("turns").asLong()).isEqualTo(4);
        assertThat(busy.get("answered").asLong()).isEqualTo(2);
        assertThat(busy.get("refused").asLong()).isEqualTo(1);
        assertThat(busy.get("escalated").asLong()).isEqualTo(1);
        assertThat(busy.get("servedFromCache").asLong()).isEqualTo(1);
        assertThat(busy.get("costUsdMicros").asLong()).isEqualTo(25_000_000L);
        assertThat(busy.get("inputTokens").asLong()).isEqualTo(1_800);
        assertThat(busy.get("ceilingReached").asBoolean()).isTrue();
    }

    // ============================================================ a turn's provenance

    @Test
    @DisplayName(
            "an operator reads why the assistant said what it said -- how the turn ended and which facts it stood on -- from its own brand, with the right capability, and no words")
    void aTurnsProvenance() throws Exception {
        UUID knowledgeEntry = UUID.randomUUID();
        UUID turn = insertTurnWithFacts(knowledgeEntry);
        String path = "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/assistant/turns/" + turn;

        MvcResult read = mvc.perform(get(path).with(tokenFor(OWNER))).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = body(read);
        assertThat(view.get("turnId").asString()).isEqualTo(turn.toString());
        assertThat(view.get("outcome").asString()).isEqualTo("ANSWERED");
        assertThat(view.get("locale").asString()).isEqualTo("ru");
        assertThat(view.get("questionKinds")).hasSize(2);
        assertThat(view.get("facts")).hasSize(2);
        assertThat(view.get("facts").get(0).get("kind").asString()).isEqualTo("PRICE");
        assertThat(view.get("facts").get(0).get("cited").asBoolean()).isTrue();
        assertThat(view.get("facts").get(1).get("kind").asString()).isEqualTo("KNOWLEDGE");
        assertThat(view.get("facts").get(1).get("cited").asBoolean()).isFalse();
        assertThat(view.get("knowledgeVersions")).hasSize(1);
        assertThat(view.get("knowledgeVersions").get(0).get("entryId").asString())
                .isEqualTo(knowledgeEntry.toString());
        assertThat(view.get("knowledgeVersions").get(0).get("version").asInt()).isEqualTo(3);
        assertThat(read.getResponse().getContentAsString(UTF_8))
                .as("provenance is ids and kinds; the ledger holds no words and neither does this")
                .doesNotContain("customer_pseudonym")
                .doesNotContain("variantId");

        assertThat(mvc.perform(get(path).with(tokenFor(BRAND_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(get(path).with(tokenFor(FINANCE)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("finance holds neither assistant capability")
                .isEqualTo(403);
    }

    @Test
    @DisplayName(
            "a turn is found only from its own brand's path: another brand's path, an unknown id and another tenant all read as not found")
    void aTurnIsNotFoundFromAnyPathButItsOwn() throws Exception {
        UUID turn = insertTurnWithFacts(UUID.randomUUID());
        String base = "/api/v1/operations/tenants/" + TENANT + "/brands/";

        assertThat(mvc.perform(get(base + OTHER_BRAND + "/assistant/turns/" + turn)
                                .with(tokenFor(OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(get(base + BRAND + "/assistant/turns/" + UUID.randomUUID())
                                .with(tokenFor(OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(get(base + BRAND + "/assistant/turns/" + turn).with(tokenFor("a-stranger")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("no grant at all")
                .isEqualTo(403);
    }

    // ================================================== the settings card's endpoints

    private static final String CONFIG = "/api/v1/operations/tenants/" + TENANT + "/configuration";

    @Test
    @DisplayName(
            "the console's switch and wording go through the tenant configuration endpoints: the visible keys are listed, the ceiling is not, and what is written is what the usage screen and the assistant read")
    void theSettingsCardThroughTheTenantEndpoints() throws Exception {
        JsonNode keys =
                body(mvc.perform(get(CONFIG + "/keys").with(tokenFor(OWNER))).andReturn());
        List<String> codes = new java.util.ArrayList<>();
        keys.forEach(key -> codes.add(key.get("code").asString()));
        assertThat(codes)
                .contains(
                        "assistant.enabled",
                        "assistant.disclosure_text_en",
                        "assistant.disclosure_text_ru",
                        "assistant.disclosure_text_uz")
                .as("what HorecaOS pays the processor is the platform's to set")
                .doesNotContain("assistant.monthly_spend_ceiling_usd_cents");
        MvcResult ceiling = mvc.perform(get(CONFIG + "/keys/assistant.monthly_spend_ceiling_usd_cents/resolution")
                        .param("scopeType", "TENANT")
                        .with(tokenFor(OWNER)))
                .andReturn();
        assertThat(ceiling.getResponse().getStatus()).isEqualTo(404);
        MvcResult ceilingWrite = send(
                CONFIG + "/keys/assistant.monthly_spend_ceiling_usd_cents/values",
                OWNER,
                "{\"scopeType\":\"TENANT\",\"explicitNull\":false,\"integerValue\":99999,\"reason\":\"more\"}",
                null);
        assertThat(ceilingWrite.getResponse().getStatus())
                .as("a tenant cannot raise what the platform pays for it")
                .isEqualTo(404);

        String usagePath = "/api/v1/operations/tenants/" + TENANT + "/assistant/usage";
        assertThat(body(mvc.perform(get(usagePath).with(tokenFor(OWNER))).andReturn())
                        .get("switchedOn")
                        .asBoolean())
                .isFalse();
        MvcResult switchedOn = send(
                CONFIG + "/keys/assistant.enabled/values",
                OWNER,
                "{\"scopeType\":\"TENANT\",\"explicitNull\":false,\"booleanValue\":true,\"reason\":\"pilot\"}",
                null);
        assertThat(switchedOn.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(mvc.perform(get(usagePath).with(tokenFor(OWNER))).andReturn())
                        .get("switchedOn")
                        .asBoolean())
                .isTrue();

        MvcResult worded = send(
                CONFIG + "/keys/assistant.disclosure_text_ru/values",
                OWNER,
                "{\"scopeType\":\"BRAND\",\"brandId\":\"" + BRAND
                        + "\",\"explicitNull\":false,\"stringValue\":\"Вам отвечает бот сети.\",\"reason\":\"our wording\"}",
                null);
        assertThat(worded.getResponse().getStatus()).isEqualTo(200);
        JsonNode resolved = body(mvc.perform(get(CONFIG + "/keys/assistant.disclosure_text_ru/resolution")
                        .param("scopeType", "BRAND")
                        .param("brandId", BRAND.toString())
                        .with(tokenFor(OWNER)))
                .andReturn());
        assertThat(resolved.get("value").asString()).isEqualTo("Вам отвечает бот сети.");
        assertThat(resolved.get("cameFromDefault").asBoolean()).isFalse();
    }

    @Test
    @DisplayName(
            "a disclosure that is too long or carries a control character is a 400 where it is written, and leaves nothing behind")
    void aBadDisclosureIsRefusedAtTheDoor() throws Exception {
        MvcResult tooLong = send(
                CONFIG + "/keys/assistant.disclosure_text_en/values",
                OWNER,
                "{\"scopeType\":\"TENANT\",\"explicitNull\":false,\"stringValue\":\"" + "x".repeat(501)
                        + "\",\"reason\":\"too much\"}",
                null);
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
        MvcResult control = send(
                CONFIG + "/keys/assistant.disclosure_text_en/values",
                OWNER,
                "{\"scopeType\":\"TENANT\",\"explicitNull\":false,\"stringValue\":\"bell\\u0007\",\"reason\":\"x\"}",
                null);
        assertThat(control.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM tenant.configuration_values WHERE key_code LIKE 'assistant.disclosure%'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // ===================================================================== fixtures

    private void insertTurn(
            String outcome, long costMicros, long inputTokens, long outputTokens, Instant at, boolean cached) {
        jdbc.sql("""
                INSERT INTO assistant.turns
                    (id, tenant_id, brand_id, conversation_id, occurred_at, locale, question_kinds, outcome,
                     refusal_reason, input_tokens, output_tokens, cost_usd_micros, served_from_cache)
                VALUES (:id, :tenantId, :brandId, :conversationId, :at, 'en', 'KNOWLEDGE', :outcome,
                        :refusal, :input, :output, :cost, :cached)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("conversationId", UUID.randomUUID())
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("outcome", outcome)
                .param("refusal", "REFUSED".equals(outcome) ? "NO_GROUNDING" : null)
                .param("input", inputTokens)
                .param("output", outputTokens)
                .param("cost", costMicros)
                .param("cached", cached)
                .update();
    }

    private UUID insertTurnWithFacts(UUID knowledgeEntry) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO assistant.turns
                    (id, tenant_id, brand_id, conversation_id, occurred_at, locale, question_kinds, outcome,
                     model_id, facts, cited_fact_ids, knowledge_versions, customer_pseudonym)
                VALUES (:id, :tenantId, :brandId, :conversationId, :at, 'ru', 'KNOWLEDGE,PRICE', 'ANSWERED',
                        'claude-sonnet-5-5',
                        CAST(:facts AS jsonb), CAST(:cited AS jsonb), CAST(:knowledge AS jsonb), 'pseudonym-1')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("conversationId", UUID.randomUUID())
                .param("at", Instant.now().atOffset(ZoneOffset.UTC))
                .param(
                        "facts",
                        "[{\"id\":\"f1\",\"kind\":\"PRICE\",\"variantId\":\"v-1\"},{\"id\":\"f2\",\"kind\":\"KNOWLEDGE\"}]")
                .param("cited", "[\"f1\"]")
                .param("knowledge", "[{\"entryId\":\"" + knowledgeEntry + "\",\"version\":3}]")
                .update();
        return id;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'assistant-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        for (Map.Entry<UUID, String> brand :
                Map.of(BRAND, "MAIN", OTHER_BRAND, "OTHER").entrySet()) {
            jdbc.sql("""
                    INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                    VALUES (:id, :t, :code, :slug, :code, 'ACTIVE', 0)
                    """)
                    .param("id", brand.getKey())
                    .param("t", TENANT)
                    .param("code", brand.getValue())
                    .param("slug", brand.getValue().toLowerCase())
                    .update();
        }
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'assistant endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                // Backdated, as the inbox endpoint test does: the grant is compared against this
                // JVM's clock and a container's own can skew under load.
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Carries no realm role, so a refusal proves the ADR 0025 grant decided it. */
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
