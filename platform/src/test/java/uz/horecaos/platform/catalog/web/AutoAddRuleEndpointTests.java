package uz.horecaos.platform.catalog.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0158's authoring surface over HTTP, with the console's JSON (ADR 0025, ADR 0031): create a rule as a
 * draft, replace it with its three child sets in one transaction, activate it, archive it, and run a
 * hypothetical basket through the simulator.
 *
 * <p>The bodies below are what the console sends, not records built in Java: the console omits every field
 * the author left alone, including the booleans, so a primitive the server insisted on (Jackson 3 refuses a
 * body that omits one) would show up here as a 400 and not in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class AutoAddRuleEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fd100-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fd100-4000-7000-8000-0000000000b1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fd100-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fd100-4000-7000-8000-0000000000b2");
    private static final UUID LOCATION = UUID.fromString("018fd100-4000-7000-8000-0000000000c1");
    private static final UUID LOCATION_TWO = UUID.fromString("018fd100-4000-7000-8000-0000000000c2");

    private static final UUID BURGER = UUID.fromString("018fd100-4000-7000-8000-000000000101");
    private static final UUID PLOV = UUID.fromString("018fd100-4000-7000-8000-000000000102");
    private static final UUID CUTLERY = UUID.fromString("018fd100-4000-7000-8000-000000000103");
    private static final UUID BAG = UUID.fromString("018fd100-4000-7000-8000-000000000104");
    private static final UUID FOREIGN = UUID.fromString("018fd100-4000-7000-8000-000000000199");

    private static final String OWNER = "auto-add-owner";
    private static final String NO_GRANT = "auto-add-no-grant";
    private static final String IDEMPOTENCY_HEADER = IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER;

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private int requests;

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
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        requests = 0;
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE catalog.auto_add_rule_triggers, catalog.auto_add_rule_bands, "
                        + "catalog.auto_add_rule_locations, catalog.auto_add_rules, "
                        + "catalog.variant_physical_attributes, catalog.fiscal_classifications, catalog.fees, "
                        + "catalog.translations, catalog.variants, catalog.products CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        insertTenant(TENANT, "auto-add-endpoint");
        insertBrand(TENANT, BRAND, "auto-add-brand");
        insertLocation(TENANT, BRAND, LOCATION, "ONE");
        insertLocation(TENANT, BRAND, LOCATION_TWO, "TWO");
        insertTenant(OTHER_TENANT, "auto-add-other");
        insertBrand(OTHER_TENANT, OTHER_BRAND, "auto-add-other-brand");
        insertVariant(TENANT, BRAND, BURGER, "BURGER");
        insertVariant(TENANT, BRAND, PLOV, "PLOV");
        insertVariant(TENANT, BRAND, CUTLERY, "CUTLERY");
        insertVariant(TENANT, BRAND, BAG, "BAG");
        insertVariant(OTHER_TENANT, OTHER_BRAND, FOREIGN, "FOREIGN");

        grant(OWNER, PlatformRole.TENANT_OWNER);
    }

    // ------------------------------------------------------------------------ create and read

    @Test
    @DisplayName("the console's body creates a draft with the defaults it left out, an ETag to quote and an audit fact")
    void createsADraftFromTheConsolesBody() throws Exception {
        MvcResult created = create("""
                {"code":"cutlery","kind":"PLAIN","addVariantId":"%s","quantity":1,
                 "fulfillmentModes":["DELIVERY"],"channelSystemTypes":["WEB","TELEGRAM"],
                 "customerText":{"uz":"Qoshiq-sanchqi","ru":"Приборы"}}""".formatted(CUTLERY));

        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(200);
        String body = created.getResponse().getContentAsString();
        assertThat(created.getResponse().getHeader("ETag")).isEqualTo("W/\"1\"");
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("DRAFT");
        assertThat(JsonPath.<String>read(body, "$.mode"))
                .as("omitted, so INJECT")
                .isEqualTo("INJECT");
        assertThat(JsonPath.<Boolean>read(body, "$.mandatory")).isFalse();
        assertThat(JsonPath.<Boolean>read(body, "$.repeatPerMultiple")).isFalse();
        assertThat(JsonPath.<Number>read(body, "$.minimumQuantity").intValue()).isEqualTo(1);
        assertThat(JsonPath.<Number>read(body, "$.maximumQuantity").intValue()).isEqualTo(20);
        assertThat(JsonPath.<List<String>>read(body, "$.channelSystemTypes")).containsExactly("TELEGRAM", "WEB");
        assertThat(JsonPath.<String>read(body, "$.customerText.ru")).isEqualTo("Приборы");

        Map<String, Object> fact = jdbc.sql("""
                        SELECT capability_used, scope_type, scope_id, change_document::text AS document
                          FROM audit.audit_events WHERE action_code = 'catalog.autoAddRule.created'
                        """).query().singleRow();
        assertThat(fact.get("capability_used")).isEqualTo(Capability.CATALOG_AUTHOR.code());
        assertThat(fact.get("scope_type")).isEqualTo("BRAND");
        assertThat((String) fact.get("document"))
                .as("ids, codes and counts; the customer text is not audited")
                .contains("PLAIN")
                .doesNotContain("Приборы")
                .doesNotContain("Qoshiq");

        MvcResult list = mvc.perform(get(rulesPath()).with(tokenFor(OWNER))).andReturn();
        assertThat(JsonPath.<List<String>>read(list.getResponse().getContentAsString(), "$[*].code"))
                .containsExactly("cutlery");
    }

    @Test
    @DisplayName("a rule cannot name an aggregator or POS channel, and nothing is stored")
    void anAggregatorChannelCannotBeNamed() throws Exception {
        for (String channel : List.of("AGGREGATOR", "POS")) {
            MvcResult refused = create("""
                    {"code":"bag","kind":"PLAIN","addVariantId":"%s","quantity":1,
                     "fulfillmentModes":["DELIVERY"],"channelSystemTypes":["WEB","%s"]}""".formatted(BAG, channel));
            assertThat(refused.getResponse().getStatus()).as(channel).isEqualTo(422);
            assertThat(refused.getResponse().getContentAsString()).contains("AUTO_ADD_CHANNEL_NOT_ALLOWED");
        }
        assertThat(count("catalog.auto_add_rules")).isZero();
    }

    @Test
    @DisplayName("the schema says the same as the service: an aggregator channel is rejected by the CHECK constraint")
    void theSchemaRejectsAnAggregatorChannelToo() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO catalog.auto_add_rules (id, tenant_id, brand_id, code, kind, add_variant_id,
                                    quantity, fulfillment_modes, channel_system_types)
                                VALUES (:id, :t, :b, 'sneaky', 'PLAIN', :v, 1, ARRAY['DELIVERY'], ARRAY['AGGREGATOR'])
                                """)
                        .param("id", UUID.randomUUID())
                        .param("t", TENANT)
                        .param("b", BRAND)
                        .param("v", BAG)
                        .update())
                .hasMessageContaining("ck_auto_add_rule_channels");
    }

    @Test
    @DisplayName("overlapping bands, a duplicate code, a foreign variant and a missing field are each refused by name")
    void shapeErrorsAreRefusedByName() throws Exception {
        MvcResult overlap = create("""
                {"code":"sets","kind":"PORTION_BAND","fulfillmentModes":["DELIVERY"],
                 "channelSystemTypes":["WEB"],
                 "bands":[{"portionsFrom":1,"portionsTo":4,"addVariantId":"%s","quantity":1},
                          {"portionsFrom":3,"portionsTo":6,"addVariantId":"%s","quantity":2}]}""".formatted(CUTLERY, CUTLERY));
        assertThat(overlap.getResponse().getStatus()).isEqualTo(422);
        assertThat(overlap.getResponse().getContentAsString()).contains("AUTO_ADD_BANDS_OVERLAP");

        assertThat(create(plainBody("dup", CUTLERY)).getResponse().getStatus()).isEqualTo(200);
        MvcResult duplicate = create(plainBody("dup", BAG));
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
        assertThat(duplicate.getResponse().getContentAsString()).contains("AUTO_ADD_RULE_CODE_TAKEN");

        MvcResult foreign = create(plainBody("foreign", FOREIGN));
        assertThat(foreign.getResponse().getStatus())
                .as("a variant of another tenant is a 404, the same answer as a variant that does not exist")
                .isEqualTo(404);

        MvcResult plainWithoutVariant = create("""
                {"code":"nothing","kind":"PLAIN","fulfillmentModes":["DELIVERY"],"channelSystemTypes":["WEB"]}""");
        assertThat(plainWithoutVariant.getResponse().getStatus()).isEqualTo(422);
        assertThat(plainWithoutVariant.getResponse().getContentAsString()).contains("AUTO_ADD_RULE_INVALID");

        MvcResult mandatoryOffer = create("""
                {"code":"must","kind":"PLAIN","mode":"OFFER","mandatory":true,"addVariantId":"%s","quantity":1,
                 "fulfillmentModes":["DELIVERY"],"channelSystemTypes":["WEB"]}""".formatted(BAG));
        assertThat(mandatoryOffer.getResponse().getStatus())
                .as("a customer cannot decline a suggestion they were never asked to accept")
                .isEqualTo(422);
        assertThat(count("catalog.auto_add_rules")).isEqualTo(1);
    }

    // --------------------------------------------------------------------------- replace and archive

    @Test
    @DisplayName(
            "a replace swaps the trigger, band and location sets with the version bump and the .updated audit fact")
    void aReplaceSwapsTheChildSetsInOneTransaction() throws Exception {
        MvcResult created = create("""
                {"code":"bag-per-three","kind":"PRODUCT_TRIGGERED","addVariantId":"%s","quantity":1,
                 "triggerThreshold":3,"repeatPerMultiple":true,
                 "fulfillmentModes":["DELIVERY","PICKUP"],"channelSystemTypes":["WEB"],
                 "triggers":[{"variantId":"%s"},{"variantId":"%s"}],
                 "locationIds":["%s","%s"]}""".formatted(BAG, BURGER, PLOV, LOCATION, LOCATION_TWO));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(200);
        UUID ruleId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.ruleId"));
        assertThat(count("catalog.auto_add_rule_triggers")).isEqualTo(2);
        assertThat(count("catalog.auto_add_rule_locations")).isEqualTo(2);

        MvcResult replaced = replace(ruleId, "1", """
                {"addVariantId":"%s","quantity":2,"triggerThreshold":4,
                 "fulfillmentModes":["DELIVERY","PICKUP"],"channelSystemTypes":["WEB"],
                 "triggers":[{"variantId":"%s"}]}""".formatted(BAG, BURGER));

        assertThat(replaced.getResponse().getStatus())
                .as(replaced.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(replaced.getResponse().getHeader("ETag")).isEqualTo("W/\"2\"");
        assertThat(count("catalog.auto_add_rule_triggers"))
                .as("exactly the new set: one trigger node remains")
                .isEqualTo(1);
        assertThat(count("catalog.auto_add_rule_locations"))
                .as("no location rows means every location, and the PUT said none")
                .isZero();
        Map<String, Object> row = jdbc.sql("SELECT quantity, trigger_threshold, repeat_per_multiple, version "
                        + "FROM catalog.auto_add_rules WHERE id = :id")
                .param("id", ruleId)
                .query()
                .singleRow();
        assertThat(row.get("version")).isEqualTo(2);
        assertThat(row.get("repeat_per_multiple")).as("omitted, so false again").isEqualTo(false);
        assertThat(count("audit.audit_events WHERE action_code = 'catalog.autoAddRule.updated'"))
                .isEqualTo(1);

        MvcResult stale = replace(ruleId, "1", staleReplace());
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");
        assertThat(count("catalog.auto_add_rule_triggers"))
                .as("a stale replace writes nothing")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("activating is a replace that says ACTIVE, audited separately; archiving ends edits and keeps the row")
    void activateThenArchive() throws Exception {
        UUID ruleId = createdId(create(plainBody("cutlery", CUTLERY)));

        MvcResult activated = replace(ruleId, "1", """
                {"status":"ACTIVE","addVariantId":"%s","quantity":1,
                 "fulfillmentModes":["DELIVERY"],"channelSystemTypes":["WEB"]}""".formatted(CUTLERY));
        assertThat(activated.getResponse().getStatus())
                .as(activated.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(JsonPath.<String>read(activated.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("ACTIVE");
        assertThat(count("audit.audit_events WHERE action_code = 'catalog.autoAddRule.activated'"))
                .isEqualTo(1);

        MvcResult noIfMatch = mvc.perform(post(rulesPath() + "/" + ruleId + "/archive")
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "archive-" + requests++))
                .andReturn();
        assertThat(noIfMatch.getResponse().getStatus()).isEqualTo(400);

        MvcResult archived = mvc.perform(post(rulesPath() + "/" + ruleId + "/archive")
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "archive-" + requests++)
                        .header("If-Match", "2"))
                .andReturn();
        assertThat(archived.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<String>read(archived.getResponse().getContentAsString(), "$.status"))
                .isEqualTo("ARCHIVED");
        assertThat(count("catalog.auto_add_rules"))
                .as("archived, never deleted")
                .isEqualTo(1);
        assertThat(count("audit.audit_events WHERE action_code = 'catalog.autoAddRule.archived'"))
                .isEqualTo(1);

        MvcResult edit = replace(ruleId, "3", plainReplace());
        assertThat(edit.getResponse().getStatus()).isEqualTo(422);
        assertThat(edit.getResponse().getContentAsString()).contains("AUTO_ADD_RULE_ARCHIVED");
    }

    @Test
    @DisplayName("a write needs the right capability at the brand, an Idempotency-Key and the same tenant")
    void authorisationAndPreconditions() throws Exception {
        MvcResult noGrant = mvc.perform(post(rulesPath())
                        .with(tokenFor(NO_GRANT))
                        .header(IDEMPOTENCY_HEADER, "refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(plainBody("nope", BAG)))
                .andReturn();
        assertThat(noGrant.getResponse().getStatus()).isEqualTo(403);
        assertThat(noGrant.getResponse().getContentAsString()).contains(Capability.CATALOG_AUTHOR.code());

        MvcResult noKey = mvc.perform(post(rulesPath())
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(plainBody("nope", BAG)))
                .andReturn();
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(noKey.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");

        MvcResult otherTenant = mvc.perform(get("/api/v1/control-plane/tenants/" + OTHER_TENANT + "/brands/"
                                + OTHER_BRAND + "/catalog/auto-add-rules")
                        .with(tokenFor(OWNER)))
                .andReturn();
        assertThat(otherTenant.getResponse().getStatus()).isEqualTo(403);
        assertThat(count("catalog.auto_add_rules")).isZero();
    }

    // --------------------------------------------------------------------------------- simulator

    @Test
    @DisplayName(
            "the simulator runs a hypothetical basket through draft and active rules with the cart's own evaluator")
    void theSimulatorPreviewsWhatTheCartWouldDo() throws Exception {
        // Two portions per burger, authored through the existing physical-attributes endpoint.
        MvcResult physical = mvc.perform(put("/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND
                                + "/catalog/variants/" + BURGER + "/physical-attributes")
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "physical-" + requests++)
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"portionsPerUnit\":2}"))
                .andReturn();
        assertThat(physical.getResponse().getStatus())
                .as(physical.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(JsonPath.<Number>read(physical.getResponse().getContentAsString(), "$.portionsPerUnit")
                        .intValue())
                .isEqualTo(2);

        create("""
                {"code":"bag-per-two","kind":"PRODUCT_TRIGGERED","addVariantId":"%s","quantity":1,
                 "triggerThreshold":2,"repeatPerMultiple":true,
                 "fulfillmentModes":["DELIVERY"],"channelSystemTypes":["WEB"],
                 "triggers":[{"variantId":"%s"}]}""".formatted(BAG, BURGER));
        create("""
                {"code":"cutlery-sets","kind":"PORTION_BAND","fulfillmentModes":["DELIVERY"],
                 "channelSystemTypes":["WEB"],
                 "bands":[{"portionsFrom":1,"portionsTo":4,"addVariantId":"%s","quantity":1},
                          {"portionsFrom":4,"addVariantId":"%s","quantity":2}]}""".formatted(CUTLERY, CUTLERY));

        String writtenBefore = whatASimulationCouldWrite();
        MvcResult one = simulate("""
                {"fulfillmentMode":"DELIVERY","channelSystemType":"WEB",
                 "lines":[{"variantId":"%s","quantity":1}]}""".formatted(BURGER));
        String body = one.getResponse().getContentAsString();
        assertThat(one.getResponse().getStatus()).as(body).isEqualTo(200);
        assertThat(JsonPath.<Number>read(body, "$.portions").intValue()).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(body, "$.outcomes[?(@.ruleCode=='cutlery-sets')].outcome"))
                .containsExactly("INJECT");
        assertThat(JsonPath.<List<String>>read(body, "$.outcomes[?(@.ruleCode=='bag-per-two')].outcome"))
                .as("one burger is below the two-unit step")
                .isEmpty();

        MvcResult three = simulate("""
                {"fulfillmentMode":"DELIVERY","channelSystemType":"WEB",
                 "lines":[{"variantId":"%s","quantity":3}]}""".formatted(BURGER));
        body = three.getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(body, "$.outcomes[?(@.ruleCode=='bag-per-two')].quantity")
                        .get(0)
                        .intValue())
                .isEqualTo(1);
        assertThat(JsonPath.<List<Number>>read(body, "$.outcomes[?(@.ruleCode=='cutlery-sets')].quantity")
                        .get(0)
                        .intValue())
                .as("six portions is the open-ended band, two sets")
                .isEqualTo(2);

        MvcResult pickup = simulate("""
                {"fulfillmentMode":"PICKUP","channelSystemType":"WEB",
                 "lines":[{"variantId":"%s","quantity":3}]}""".formatted(BURGER));
        assertThat(JsonPath.<List<Object>>read(pickup.getResponse().getContentAsString(), "$.outcomes"))
                .as("both rules are delivery-only")
                .isEmpty();

        UUID cutleryRule = UUID.fromString(JsonPath.<List<String>>read(
                        mvc.perform(get(rulesPath()).with(tokenFor(OWNER)))
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "$[?(@.code=='cutlery-sets')].ruleId")
                .get(0));
        MvcResult declined = simulate("""
                {"fulfillmentMode":"DELIVERY","channelSystemType":"WEB","declinedRuleIds":["%s"],
                 "lines":[{"variantId":"%s","quantity":3}]}""".formatted(cutleryRule, BURGER));
        assertThat(JsonPath.<List<String>>read(
                        declined.getResponse().getContentAsString(),
                        "$.outcomes[?(@.ruleCode=='cutlery-sets')].reason"))
                .containsExactly("DECLINED");
        assertThat(whatASimulationCouldWrite())
                .as("a simulation is a POST only because its input is a basket: it writes no cart, line, "
                        + "decline, rule version or audit fact, which is why it needs no Idempotency-Key")
                .isEqualTo(writtenBefore);
    }

    /** Every place a simulation could leave a trace, as one comparable string. */
    private String whatASimulationCouldWrite() {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM ordering.carts) || '/'
                            || (SELECT count(*) FROM ordering.cart_lines) || '/'
                            || (SELECT count(*) FROM ordering.cart_auto_add_declines) || '/'
                            || (SELECT coalesce(sum(version), 0) FROM catalog.auto_add_rules) || '/'
                            || (SELECT count(*) FROM audit.audit_events)
                        """).query(String.class).single();
    }

    @Test
    @DisplayName("a negative or over-precise portions-per-unit is refused by name, and blank is stored as nothing")
    void portionsPerUnitIsValidated() throws Exception {
        assertThat(physicalWrite(PLOV, "0", "{\"portionsPerUnit\":-1}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        MvcResult tooPrecise = physicalWrite(PLOV, "0", "{\"portionsPerUnit\":1.2345}");
        assertThat(tooPrecise.getResponse().getStatus()).isEqualTo(400);
        assertThat(JsonPath.<String>read(tooPrecise.getResponse().getContentAsString(), "$.reason"))
                .isEqualTo("PORTIONS_PER_UNIT_TOO_PRECISE");
        assertThat(count("catalog.variant_physical_attributes")).isZero();

        assertThat(physicalWrite(PLOV, "0", "{\"portionsPerUnit\":0.5}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(jdbc.sql("SELECT portions_per_unit FROM catalog.variant_physical_attributes WHERE variant_id = :v")
                        .param("v", PLOV)
                        .query(java.math.BigDecimal.class)
                        .single())
                .isEqualByComparingTo("0.5");
    }

    // ------------------------------------------------------------------------------------ helpers

    private static String plainBody(String code, UUID variant) {
        return """
                {"code":"%s","kind":"PLAIN","addVariantId":"%s","quantity":1,
                 "fulfillmentModes":["DELIVERY"],"channelSystemTypes":["WEB"]}""".formatted(code, variant);
    }

    private static String staleReplace() {
        return """
                {"addVariantId":"%s","quantity":1,"triggerThreshold":2,
                 "fulfillmentModes":["DELIVERY"],"channelSystemTypes":["WEB"],
                 "triggers":[{"variantId":"%s"}]}""".formatted(BAG, PLOV);
    }

    private static String plainReplace() {
        return """
                {"addVariantId":"%s","quantity":1,"fulfillmentModes":["DELIVERY"],"channelSystemTypes":["WEB"]}""".formatted(BAG);
    }

    private static UUID createdId(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.ruleId"));
    }

    private static String rulesPath() {
        return "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/catalog/auto-add-rules";
    }

    private MvcResult create(String body) throws Exception {
        return mvc.perform(post(rulesPath())
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "rule-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult replace(UUID ruleId, String ifMatch, String body) throws Exception {
        return mvc.perform(put(rulesPath() + "/" + ruleId)
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "rule-" + requests++)
                        .header("If-Match", ifMatch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult simulate(String body) throws Exception {
        return mvc.perform(post(rulesPath() + "/simulations")
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult physicalWrite(UUID variantId, String ifMatch, String body) throws Exception {
        return mvc.perform(put("/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/catalog/variants/"
                                + variantId + "/physical-attributes")
                        .with(tokenFor(OWNER))
                        .header(IDEMPOTENCY_HEADER, "physical-" + requests++)
                        .header("If-Match", ifMatch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private long count(String tableAndWhere) {
        return jdbc.sql("SELECT count(*) FROM " + tableAndWhere)
                .query(Long.class)
                .single();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertBrand(UUID tenantId, UUID brandId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("slug", slug)
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

    private void insertVariant(UUID tenantId, UUID brandId, UUID variantId, String code) {
        UUID productId = UUID.nameUUIDFromBytes(("product-" + variantId).getBytes(UTF_8));
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :tenantId, :brandId, :productId, true, 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'auto-add endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }
}
