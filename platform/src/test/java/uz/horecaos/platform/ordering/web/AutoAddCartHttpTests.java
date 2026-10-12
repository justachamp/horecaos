package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.catalog.api.CatalogConfigurationKeys;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.PhysicalAttributesAuthoringService;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.PhysicalAttributes;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.ordering.api.CustomerBotOrderingPort;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.reporting.application.DayCloseService;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationValueAuthor;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0158 end to end, against the real, fully wired application: a rule is authored over HTTP, published,
 * and the customer's cart (the storefront's own endpoints), the operator's quote and the Telegram bot's
 * repeat then build the same basket.
 *
 * <p>The cart is the one place every entry path shares, so these tests are about what a customer or an
 * operator can observe: the injected line is in the cart read, carries the rule's text, can be removed
 * unless the rule is mandatory, never comes back once removed, and is on the order afterwards with the rule
 * that put it there. The rules themselves are exercised in {@code AutoAddEvaluatorTests}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class AutoAddCartHttpTests {

    private static final String ISSUER = "https://issuer.test/realms/horecaos";
    private static final UUID TENANT = UUID.fromString("018fd200-c000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fd200-c000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fd200-c000-7000-8000-0000000000c1");
    private static final UUID SECOND_LOCATION = UUID.fromString("018fd200-c000-7000-8000-0000000000c2");

    private static final String CUSTOMER_SUBJECT = "auto-add-customer";
    private static final String OPERATOR = "auto-add-operator";
    private static final String PLATFORM_ADMIN = "auto-add-platform-admin";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String IDEMPOTENCY_HEADER = IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER;

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
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("horecaos.realtime.signals.publish", () -> "false");
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
        registry.add("horecaos.realtime.signals.publish", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private CatalogAuthoringService catalogAuthoring;

    @Autowired
    private CatalogPublicationService publication;

    @Autowired
    private PhysicalAttributesAuthoringService physical;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private DayCloseService dayClose;

    @Autowired
    private ConfigurationValueAuthor configuration;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private CustomerBotOrderingPort bot;

    private int requests;
    private UUID customerAccount;
    private UUID catalogId;
    private UUID burger;
    private UUID plov;
    private UUID cola;
    private UUID cutlery;
    private UUID bag;
    private UUID mainsCategory;

    @BeforeEach
    void reset() {
        requests = 0;
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("""
                TRUNCATE TABLE ordering.order_amendment_commands, ordering.order_amendments,
                    ordering.order_outcomes, ordering.order_outcome_reason_texts,
                    ordering.order_outcome_reasons, ordering.order_revisions,
                    ordering.order_process_states, ordering.order_timers,
                    ordering.approval_decisions, ordering.order_state_history,
                    ordering.order_customer_snapshots, ordering.order_adjustments,
                    ordering.order_line_modifiers, ordering.order_lines,
                    ordering.bulk_operation_items, ordering.bulk_operations, ordering.orders,
                    ordering.order_number_counters, ordering.checkout_attempts,
                    ordering.cart_auto_add_declines, ordering.cart_lines, ordering.carts CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE reporting.fact_order_line, reporting.fact_order, reporting.fact_order_tender,
                    reporting.fact_refund CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE payments.entitlement_redemptions, payments.remedy_entitlements,
                    payments.order_remedies, payments.tenders, payments.order_settlements CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE pricing.coupon_redemptions, pricing.coupon_customer_usage,
                    pricing.coupon_codes, pricing.promotion_actions, pricing.promotion_conditions,
                    pricing.promotions, pricing.quote_adjustments, pricing.quote_lines, pricing.quotes,
                    pricing.prices, pricing.price_book_assignments, pricing.price_books,
                    pricing.tax_profiles CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE inventory.reservation_lines, inventory.reservations,
                    inventory.movements, inventory.positions, inventory.stock_items CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE catalog.auto_add_rule_triggers, catalog.auto_add_rule_bands,
                    catalog.auto_add_rule_locations, catalog.auto_add_rules,
                    catalog.variant_physical_attributes, catalog.publication_items,
                    catalog.publications, catalog.location_offerings, catalog.translations,
                    catalog.category_products, catalog.categories,
                    catalog.combo_components, catalog.combo_groups,
                    catalog.product_modifier_groups, catalog.variant_modifier_groups,
                    catalog.modifier_options, catalog.modifier_groups,
                    catalog.catalog_products, catalog.fiscal_classifications, catalog.fees,
                    catalog.variants, catalog.products, catalog.catalogs CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE customer.principal_links, customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        roleRegistry.synchronize();

        seedTenancy();
        seedCatalogAndPricing();
        grant(OPERATOR, PlatformRole.TENANT_OWNER);
        gate(false, false);
        mandatoryAllowed(false);
    }

    // ------------------------------------------------------------------------------ the gate

    @Test
    @DisplayName("while ordering.auto_add_enabled is false the cart neither adds nor removes anything")
    void theGateClosedMeansNothingHappens() throws Exception {
        activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        UUID cart = openCart("STOREFRONT", "PICKUP");

        JsonNode view = putLine(cart, 1, "burger", burger, "1");

        assertThat(lineKeys(view)).containsExactly("burger");
        assertThat(count("ordering.cart_lines")).isEqualTo(1);
    }

    @Test
    @DisplayName("shadow mode counts what would have fired and writes nothing")
    void shadowModeOnlyCounts() throws Exception {
        activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(false, true);
        double before = shadowCount("plain");
        UUID cart = openCart("STOREFRONT", "PICKUP");

        JsonNode view = putLine(cart, 1, "burger", burger, "1");

        assertThat(lineKeys(view)).as("nothing is written in shadow mode").containsExactly("burger");
        assertThat(shadowCount("plain") - before)
                .as("horecaos.autoadd.rules{kind=plain,outcome=shadow} counted the rule that would have fired")
                .isGreaterThanOrEqualTo(1.0);
    }

    // ------------------------------------------------------------------ the injected line

    @Test
    @DisplayName("a plain rule puts a visible, removable line in the cart, in the same edit, carrying its text")
    void aPlainRuleInjectsAVisibleLine() throws Exception {
        UUID rule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");

        JsonNode view = putLine(cart, 1, "burger", burger, "1");

        assertThat(lineKeys(view)).containsExactly("aa:" + rule, "burger");
        JsonNode injected = lineOf(view, "aa:" + rule);
        assertThat(injected.get("origin").asText()).isEqualTo("AUTO_ADD");
        assertThat(injected.get("variantId").asText()).isEqualTo(cutlery.toString());
        assertThat(injected.get("quantity").decimalValue()).isEqualByComparingTo("1");
        JsonNode autoAdd = injected.get("autoAdd");
        assertThat(autoAdd.get("ruleCode").asText()).isEqualTo("cutlery");
        assertThat(autoAdd.get("customerText").get("ru").asText()).isEqualTo("Приборы");
        assertThat(autoAdd.get("removable").asBoolean()).isTrue();
        assertThat(autoAdd.get("mandatory").asBoolean()).isFalse();
        assertThat(autoAdd.get("minimumQuantity").decimalValue()).isEqualByComparingTo("0");
        assertThat(autoAdd.get("maximumQuantity").decimalValue()).isEqualByComparingTo("20");
        assertThat(lineOf(view, "burger").get("origin").asText()).isEqualTo("CUSTOMER");
        assertThat(lineOf(view, "burger").get("autoAdd").isNull()).isTrue();
        assertThat(view.get("version").asInt())
                .as("the injection rides the customer's own edit: one version bump, not two")
                .isEqualTo(2);

        Map<String, Object> stored = jdbc.sql("SELECT origin, auto_add_rule_id, auto_add_rule_version, "
                        + "customer_adjusted FROM ordering.cart_lines WHERE cart_id = :c AND line_key = :k")
                .param("c", cart)
                .param("k", "aa:" + rule)
                .query()
                .singleRow();
        assertThat(stored.get("origin")).isEqualTo("AUTO_ADD");
        assertThat(stored.get("auto_add_rule_id")).isEqualTo(rule);
        assertThat(stored.get("auto_add_rule_version")).isEqualTo(2);
        assertThat(stored.get("customer_adjusted")).isEqualTo(false);

        JsonNode priced = price(cart, 2);
        assertThat(priced.get("totalMinor").asLong())
                .as("it is priced like any line: burger 40,000 + cutlery 2,000")
                .isEqualTo(42_000L);
        assertThat(priced.get("changedByPricing").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a rule only applies to the fulfilment modes it names")
    void aRuleIsLimitedToItsModes() throws Exception {
        UUID rule = activeRule(plainRule("cutlery", cutlery, "[\"DELIVERY\"]"));
        publish("STOREFRONT");
        gate(true, false);

        UUID pickup = openCart("STOREFRONT", "PICKUP");
        assertThat(lineKeys(putLine(pickup, 1, "burger", burger, "1")))
                .as("the rule is for delivery orders")
                .containsExactly("burger");
        assertThat(rule).isNotNull();
    }

    @Test
    @DisplayName("a customer who removes an injected line never sees it again, however the basket changes")
    void aRemovalSticks() throws Exception {
        UUID rule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        JsonNode view = putLine(cart, 1, "burger", burger, "1");
        String key = "aa:" + rule;

        view = removeLine(cart, view.get("version").asInt(), key);
        assertThat(lineKeys(view)).containsExactly("burger");
        assertThat(count("ordering.cart_auto_add_declines")).isEqualTo(1);

        // Three ways of changing the basket: a bigger burger, another dish, and taking it away again.
        view = putLine(cart, view.get("version").asInt(), "burger", burger, "3");
        assertThat(lineKeys(view)).doesNotContain(key);
        view = putLine(cart, view.get("version").asInt(), "plov", plov, "1");
        assertThat(lineKeys(view)).doesNotContain(key);
        view = removeLine(cart, view.get("version").asInt(), "plov");
        assertThat(lineKeys(view)).doesNotContain(key);

        assertThat(price(cart, view.get("version").asInt()).get("totalMinor").asLong())
                .as("priced without it")
                .isEqualTo(120_000L);
        assertThat(count("ordering.cart_lines WHERE origin = 'AUTO_ADD'")).isZero();
    }

    @Test
    @DisplayName("the customer's own quantity survives a re-evaluation, within the rule's maximum")
    void theCustomersQuantityIsKept() throws Exception {
        UUID rule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        JsonNode view = putLine(cart, 1, "burger", burger, "1");
        String key = "aa:" + rule;

        view = putLine(cart, view.get("version").asInt(), key, cutlery, "3");
        assertThat(lineOf(view, key).get("quantity").decimalValue()).isEqualByComparingTo("3");
        assertThat(count("ordering.cart_lines WHERE customer_adjusted")).isEqualTo(1);

        view = putLine(cart, view.get("version").asInt(), "burger", burger, "4");
        assertThat(lineOf(view, key).get("quantity").decimalValue())
                .as("the basket grew; the rule's computed quantity is still one, and the customer's three stands")
                .isEqualByComparingTo("3");

        MvcResult tooMany = rawPut(cart, view.get("version").asInt(), key, cutlery, "21");
        assertThat(tooMany.getResponse().getStatus()).isEqualTo(409);
        assertThat(tooMany.getResponse().getContentAsString()).contains("AUTO_ADD_QUANTITY_OUT_OF_BOUNDS");
    }

    @Test
    @DisplayName("a client cannot create a line under the reserved aa: prefix, or change the dish of an injected one")
    void theReservedPrefixIsNotForClients() throws Exception {
        UUID rule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");

        MvcResult forged = rawPut(cart, 1, "aa:" + UUID.randomUUID(), bag, "1");
        assertThat(forged.getResponse().getStatus()).isEqualTo(400);
        assertThat(forged.getResponse().getContentAsString()).contains("LINE_KEY_INVALID");

        JsonNode view = putLine(cart, 1, "burger", burger, "1");
        MvcResult swapped = rawPut(cart, view.get("version").asInt(), "aa:" + rule, bag, "1");
        assertThat(swapped.getResponse().getStatus()).isEqualTo(400);
        assertThat(count("ordering.cart_lines WHERE variant_id = '" + bag + "'"))
                .isZero();
    }

    // ------------------------------------------------------------------- the other two kinds

    @Test
    @DisplayName("a product-triggered rule adds one bag per two burgers, and takes it away when the basket shrinks")
    void aProductTriggeredRuleSteps() throws Exception {
        UUID rule = activeRule(triggeredRule("bag-per-two", bag, "[\"PICKUP\"]", burger));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        String key = "aa:" + rule;

        JsonNode view = putLine(cart, 1, "burger", burger, "1");
        assertThat(lineKeys(view)).as("one burger is below the step").containsExactly("burger");

        view = putLine(cart, view.get("version").asInt(), "burger", burger, "2");
        assertThat(lineOf(view, key).get("quantity").decimalValue()).isEqualByComparingTo("1");

        view = putLine(cart, view.get("version").asInt(), "burger", burger, "5");
        assertThat(lineOf(view, key).get("quantity").decimalValue())
                .as("floor(5 / 2) = 2 steps")
                .isEqualByComparingTo("2");
        assertThat(lineOf(view, key).get("autoAdd").get("removable").asBoolean())
                .isTrue();

        view = putLine(cart, view.get("version").asInt(), "burger", burger, "1");
        assertThat(lineKeys(view))
                .as("the rule no longer fires, so its line goes")
                .containsExactly("burger");
    }

    @Test
    @DisplayName("a category trigger counts the units of every dish in the category")
    void aCategoryTriggerCountsTheCategory() throws Exception {
        UUID rule = activeRule(triggeredRule("bag-per-mains", bag, "[\"PICKUP\"]", null, mainsCategory, 3));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");

        JsonNode view = putLine(cart, 1, "burger", burger, "2");
        assertThat(lineKeys(view)).containsExactly("burger");
        view = putLine(cart, view.get("version").asInt(), "plov", plov, "1");
        assertThat(lineKeys(view)).as("2 burgers + 1 plov = 3 mains").contains("aa:" + rule);
        view = putLine(cart, view.get("version").asInt(), "cola", cola, "5");
        assertThat(lineOf(view, "aa:" + rule).get("quantity").decimalValue())
                .as("the cola is not in the category and counts for nothing")
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("a portion-band rule totals the portions of the whole basket and takes the band it falls in")
    void aPortionBandRuleCountsPortions() throws Exception {
        UUID rule = activeRule(portionBandRule("cutlery-sets", "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        String key = "aa:" + rule;

        // The burger is worth two portions, the plov one; the cola states none.
        JsonNode view = putLine(cart, 1, "cola", cola, "6");
        assertThat(lineKeys(view))
                .as("a basket that counts no portions fires no band")
                .containsExactly("cola");

        view = putLine(cart, view.get("version").asInt(), "burger", burger, "1");
        assertThat(lineOf(view, key).get("quantity").decimalValue())
                .as("2 portions: the [1, 4) band, one set")
                .isEqualByComparingTo("1");

        view = putLine(cart, view.get("version").asInt(), "plov", plov, "2");
        assertThat(lineOf(view, key).get("quantity").decimalValue())
                .as("4 portions: the open-ended band, two sets")
                .isEqualByComparingTo("2");
    }

    // ------------------------------------------------------------------------------ mandatory

    @Test
    @DisplayName("a mandatory rule is blocked at publication until a platform administrator turns the switch on")
    void mandatoryIsGatedByThePlatformSwitch() throws Exception {
        activeRule(mandatory(plainRule("packaging", bag, "[\"PICKUP\"]")));

        var blocked = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        assertThat(blocked.status()).isEqualTo(PublicationStatus.REJECTED);
        assertThat(blocked.report().blockers())
                .extracting(finding -> finding.code())
                .contains("AUTO_ADD_MANDATORY_NOT_ALLOWED");

        mandatoryAllowed(true);
        var allowed = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        assertThat(allowed.status())
                .as(String.valueOf(allowed.report().blockers()))
                .isEqualTo(PublicationStatus.PUBLISHED);
    }

    @Test
    @DisplayName("a mandatory line cannot be removed or lowered below the computed quantity, and can be raised")
    void aMandatoryLineStays() throws Exception {
        mandatoryAllowed(true);
        UUID rule = activeRule(mandatory(triggeredRule("bag-per-burger", bag, "[\"PICKUP\"]", burger, null, 1)));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        String key = "aa:" + rule;

        JsonNode view = putLine(cart, 1, "burger", burger, "2");
        JsonNode autoAdd = lineOf(view, key).get("autoAdd");
        assertThat(autoAdd.get("mandatory").asBoolean()).isTrue();
        assertThat(autoAdd.get("removable").asBoolean()).isFalse();
        assertThat(lineOf(view, key).get("quantity").decimalValue()).isEqualByComparingTo("2");

        MvcResult removal = rawDelete(cart, view.get("version").asInt(), key);
        assertThat(removal.getResponse().getStatus()).isEqualTo(409);
        assertThat(removal.getResponse().getContentAsString()).contains("AUTO_ADD_LINE_MANDATORY");
        assertThat(count("ordering.cart_auto_add_declines")).isZero();

        MvcResult lowered = rawPut(cart, view.get("version").asInt(), key, bag, "1");
        assertThat(lowered.getResponse().getStatus()).isEqualTo(409);
        assertThat(lowered.getResponse().getContentAsString()).contains("AUTO_ADD_QUANTITY_OUT_OF_BOUNDS");

        view = putLine(cart, view.get("version").asInt(), key, bag, "5");
        assertThat(lineOf(view, key).get("quantity").decimalValue()).isEqualByComparingTo("5");
        view = putLine(cart, view.get("version").asInt(), "burger", burger, "6");
        assertThat(lineOf(view, key).get("quantity").decimalValue())
                .as(
                        "the basket grew past the customer's five, so the mandatory line is raised back to the computed six")
                .isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("ADR 0169 with ADR 0158: an amendment can neither remove nor lower a line a mandatory rule added, "
            + "while a line the customer could decline can go")
    void anAmendmentCannotTakeAMandatoryLineOff() throws Exception {
        mandatoryAllowed(true);
        UUID packaging = activeRule(mandatory(triggeredRule("bag-per-burger", bag, "[\"PICKUP\"]", burger, null, 1)));
        UUID spoons = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);
        set(OrderingConfigurationKeys.AMENDMENT_LINE_REMOVAL_ENABLED, ResourceScope.brand(TENANT, BRAND), true);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        JsonNode view = putLine(cart, 1, "burger", burger, "3");
        UUID orderId = checkout(cart, price(cart, view.get("version").asInt()));
        UUID bagLine = orderLineOfRule(orderId, packaging);
        UUID cutleryLine = orderLineOfRule(orderId, spoons);
        assertThat(jdbc.sql("SELECT quantity FROM ordering.order_lines WHERE id = :id")
                        .param("id", bagLine)
                        .query(BigDecimal.class)
                        .single())
                .as("one bag per burger, three burgers")
                .isEqualByComparingTo("3");
        String reason = removalReason();

        assertThat(amendRefusal(
                        orderId,
                        "am-mandatory-1",
                        "{\"type\":\"REMOVE_LINES\",\"orderLineIds\":[\"" + bagLine + "\"],\"removalReasonId\":\""
                                + reason + "\"}"))
                .as("the customer could not have declined this line, so neither can the operator")
                .isEqualTo("AUTO_ADD_LINE_MANDATORY");
        assertThat(amendRefusal(
                        orderId,
                        "am-mandatory-2",
                        "{\"type\":\"CHANGE_LINE_QUANTITY\",\"orderLineId\":\"" + bagLine
                                + "\",\"quantity\":2,\"removalReasonId\":\"" + reason + "\"}"))
                .as("lowering it is the same removal in part")
                .isEqualTo("AUTO_ADD_LINE_MANDATORY");
        assertThat(count("ordering.order_amendments")).isZero();

        MvcResult removable = amendRaw(
                orderId,
                "am-mandatory-3",
                "{\"type\":\"REMOVE_LINES\",\"orderLineIds\":[\"" + cutleryLine + "\"],\"removalReasonId\":\"" + reason
                        + "\"}");
        assertThat(removable.getResponse().getStatus())
                .as(removable.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(count("ordering.order_amendments")).isEqualTo(1);
    }

    // ---------------------------------------------------------------------- skips and races

    @Test
    @DisplayName("a rule whose variant is sold out or unpriced is skipped, and the cart is never refused for it")
    void aRuleThatCannotBeHonouredIsSkipped() throws Exception {
        UUID cutleryRule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        UUID bagRule = activeRule(plainRule("bag", bag, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);

        // Cutlery is sold out; the bag loses its price after the menu was published.
        inventory.setAvailability(TENANT, LOCATION, cutlery, false, "SOLD_OUT", null);
        jdbc.sql("DELETE FROM pricing.prices WHERE priceable_id = :v")
                .param("v", bag)
                .update();
        double skippedBefore = metricCount("horecaos.autoadd.rules", "outcome", "skipped");

        UUID cart = openCart("STOREFRONT", "PICKUP");
        JsonNode view = putLine(cart, 1, "burger", burger, "1");

        assertThat(lineKeys(view)).as("both rules skipped, the basket proceeds").containsExactly("burger");
        assertThat(price(cart, view.get("version").asInt()).get("totalMinor").asLong())
                .isEqualTo(40_000L);
        assertThat(metricCount("horecaos.autoadd.rules", "outcome", "skipped") - skippedBefore)
                .isGreaterThanOrEqualTo(2.0);
        assertThat(cutleryRule).isNotEqualTo(bagRule);
    }

    @Test
    @DisplayName("an injected line whose stock ran out before pricing is dropped, the cart is priced again and "
            + "the answer says the basket changed")
    void aLineThatRanOutIsDroppedAtPricing() throws Exception {
        UUID rule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        JsonNode view = putLine(cart, 1, "burger", burger, "1");
        assertThat(lineKeys(view)).contains("aa:" + rule);
        int version = view.get("version").asInt();

        // After the last edit and before the price: the dish is sold out.
        inventory.setAvailability(TENANT, LOCATION, cutlery, false, "SOLD_OUT", null);

        JsonNode priced = price(cart, version);

        assertThat(priced.get("changedByPricing").asBoolean()).isTrue();
        assertThat(priced.get("cartVersion").asInt()).isGreaterThan(version);
        assertThat(priced.get("totalMinor").asLong())
                .as("priced without the packaging; nothing in the answer names a line the customer did not choose")
                .isEqualTo(40_000L);
        assertThat(count("ordering.cart_lines WHERE origin = 'AUTO_ADD'")).isZero();
    }

    // -------------------------------------------------------------------------- the order

    @Test
    @DisplayName("the order records which lines a rule added and the rule that did, the console says so, and a "
            + "reorder repeats only what the customer chose")
    void theOrderRemembersTheRule() throws Exception {
        UUID rule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        JsonNode view = putLine(cart, 1, "burger", burger, "1");
        JsonNode priced = price(cart, view.get("version").asInt());
        UUID orderId = checkout(cart, priced);

        List<Map<String, Object>> lines = jdbc.sql("SELECT origin, auto_add_rule_id, auto_add_rule_version, "
                        + "auto_add_mandatory, source_variant_id FROM ordering.order_lines WHERE order_id = :o "
                        + "ORDER BY line_number")
                .param("o", orderId)
                .query()
                .listOfRows();
        assertThat(lines).hasSize(2);
        Map<String, Object> added = lines.stream()
                .filter(line -> "AUTO_ADD".equals(line.get("origin")))
                .findFirst()
                .orElseThrow();
        assertThat(added.get("auto_add_rule_id")).isEqualTo(rule);
        assertThat(added.get("auto_add_rule_version")).isEqualTo(2);
        assertThat(added.get("auto_add_mandatory")).isEqualTo(false);
        assertThat(added.get("source_variant_id")).isEqualTo(cutlery);
        assertThat(lines.stream().filter(line -> "CUSTOMER".equals(line.get("origin"))))
                .singleElement()
                .satisfies(line -> assertThat(line.get("source_variant_id")).isEqualTo(burger));

        MvcResult detail = mvc.perform(get("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION
                                + "/orders/" + orderId)
                        .with(operator()))
                .andReturn();
        JsonNode body = JSON.readTree(detail.getResponse().getContentAsString());
        assertThat(detail.getResponse().getStatus())
                .as(detail.getResponse().getContentAsString())
                .isEqualTo(200);
        List<String> origins = new ArrayList<>();
        body.get("lines").forEach(line -> origins.add(line.get("origin").asText()));
        assertThat(origins).containsExactlyInAnyOrder("CUSTOMER", "AUTO_ADD");

        MvcResult reorder = mvc.perform(get("/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/orders/"
                                + orderId + "/reorder")
                        .with(customer()))
                .andReturn();
        assertThat(reorder.getResponse().getStatus())
                .as(reorder.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode plan = JSON.readTree(reorder.getResponse().getContentAsString());
        assertThat(plan.get("lines"))
                .as("the packaging is not the customer's selection")
                .hasSize(1);
        assertThat(plan.get("lines").get(0).get("variantId").asText()).isEqualTo(burger.toString());
    }

    @Test
    @DisplayName("web, the operator's quote and the Telegram bot's repeat build the same basket")
    void everyEntryPathGetsTheSameInjectedLines() throws Exception {
        UUID rule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]", "[\"WEB\",\"CALL_CENTRE\",\"TELEGRAM\"]"));
        publish("STOREFRONT");
        publish("CALLCENTRE");
        publish("BOT");
        gate(true, false);

        // The storefront.
        UUID webCart = openCart("STOREFRONT", "PICKUP");
        JsonNode web = putLine(webCart, 1, "burger", burger, "1");
        assertThat(lineKeys(web)).containsExactly("aa:" + rule, "burger");

        // The operator composer prices the very same basket on its own channel.
        MvcResult quote = mvc.perform(post("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION
                                + "/orders/quote")
                        .with(operator())
                        .header(IDEMPOTENCY_HEADER, "quote-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerAccountId":"%s","channelCode":"CALLCENTRE","fulfillmentMode":"PICKUP",
                                 "paymentMethodCode":"CASH","lines":[{"variantId":"%s","quantity":1}]}""".formatted(customerAccount, burger)))
                .andReturn();
        assertThat(quote.getResponse().getStatus())
                .as(quote.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode priced = JSON.readTree(quote.getResponse().getContentAsString());
        assertThat(priced.get("totalMinor").asLong()).as("burger + cutlery").isEqualTo(42_000L);
        assertThat(priced.get("autoAdded")).hasSize(1);
        assertThat(priced.get("autoAdded").get(0).get("ruleCode").asText()).isEqualTo("cutlery");
        assertThat(priced.get("autoAdded").get(0).get("variantId").asText()).isEqualTo(cutlery.toString());

        // The bot repeats an order the customer placed on the web, through the same cart calls.
        UUID orderId = checkout(webCart, price(webCart, web.get("version").asInt()));
        CustomerBotOrderingPort.Repeat repeat = bot.repeat(TENANT, BRAND, customerAccount, orderId);
        assertThat(repeat.result()).isEqualTo(CustomerBotOrderingPort.Repeat.Result.BUILT);
        List<String> botKeys = jdbc.sql("SELECT line_key FROM ordering.cart_lines WHERE cart_id = :c ORDER BY line_key")
                .param("c", repeat.cartId())
                .query(String.class)
                .list();
        assertThat(botKeys)
                .as("the bot's cart holds the same one injected line and the one dish, and the packaging "
                        + "of the old order was not repeated as a customer line")
                .hasSize(2)
                .contains("aa:" + rule);
    }

    @Test
    @DisplayName("a reservation that fails at checkout for an injected line drops the line and asks for a new "
            + "quote, never a refusal that names the packaging")
    void aReservationFailureForAnInjectedLineIsAReQuote() throws Exception {
        UUID rule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        JsonNode view = putLine(cart, 1, "burger", burger, "1");
        JsonNode priced = price(cart, view.get("version").asInt());
        assertThat(lineKeys(view)).contains("aa:" + rule);

        // Between the price and the checkout the cutlery runs out.
        inventory.setAvailability(TENANT, LOCATION, cutlery, false, "SOLD_OUT", null);
        MvcResult refused = rawCheckout(cart, priced, "checkout-sold-out");

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        String body = refused.getResponse().getContentAsString();
        assertThat(body).contains("PRICE_CHANGED").doesNotContain(cutlery.toString());
        assertThat(count("ordering.cart_lines WHERE origin = 'AUTO_ADD'"))
                .as("the line the customer never chose is gone from their basket")
                .isZero();
        assertThat(count("ordering.orders")).isZero();

        // The customer's next attempt prices the basket as it is and goes through.
        JsonNode refreshed =
                JSON.readTree(mvc.perform(get(storefront() + "/carts/" + cart).with(customer()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        JsonNode again = price(cart, refreshed.get("version").asInt());
        assertThat(checkout(cart, again)).isNotNull();
    }

    @Test
    @DisplayName("the reporting fact says which lines a rule added, and the rule")
    void theReportingFactCarriesTheOrigin() throws Exception {
        UUID rule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        JsonNode view = putLine(cart, 1, "burger", burger, "1");
        UUID orderId = checkout(cart, price(cart, view.get("version").asInt()));
        advance(orderId, "PREPARING");
        advance(orderId, "READY");
        advance(orderId, "COMPLETED");

        dayClose.close(TENANT, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tashkent")));

        List<Map<String, Object>> facts = jdbc.sql("SELECT variant_id, line_origin, auto_add_rule_id "
                        + "FROM reporting.fact_order_line WHERE order_id = :id ORDER BY line_origin")
                .param("id", orderId)
                .query()
                .listOfRows();
        assertThat(facts).hasSize(2);
        assertThat(facts.get(0).get("line_origin")).isEqualTo("AUTO_ADD");
        assertThat(facts.get(0).get("auto_add_rule_id")).isEqualTo(rule);
        assertThat(facts.get(0).get("variant_id")).isEqualTo(cutlery);
        assertThat(facts.get(1).get("line_origin")).isEqualTo("CUSTOMER");
        assertThat(facts.get(1).get("auto_add_rule_id")).isNull();
    }

    @Test
    @DisplayName("a line a rule injected never triggers another rule, so rules cannot chain")
    void rulesCannotChain() throws Exception {
        UUID addsTheBag = activeRule(plainRule("bag", bag, "[\"PICKUP\"]"));
        // A second rule waits for a bag: the one the first rule injects must not count.
        activeRule(triggeredRule("cutlery-per-bag", cutlery, "[\"PICKUP\"]", bag, null, 1));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");

        JsonNode view = putLine(cart, 1, "burger", burger, "1");

        assertThat(lineKeys(view))
                .as("only the plain rule fired; the one triggered by a bag saw no bag in the customer's basket")
                .containsExactly("aa:" + addsTheBag, "burger");
        // The customer putting a bag of their own in does trigger it.
        view = putLine(cart, view.get("version").asInt(), "own-bag", bag, "1");
        assertThat(count("ordering.cart_lines WHERE variant_id = '" + cutlery + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("taking the last dish out of the cart takes the packaging with it: the cart is empty, nothing is "
            + "priced and the customer can always empty it")
    void thePackagingNeverOutlivesTheLastDish() throws Exception {
        UUID rule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        String key = "aa:" + rule;
        JsonNode view = putLine(cart, 1, "burger", burger, "1");
        assertThat(lineKeys(view)).containsExactly(key, "burger");

        view = removeLine(cart, view.get("version").asInt(), "burger");

        assertThat(lineKeys(view)).as("a rule's line is never the whole cart").isEmpty();
        assertThat(count("ordering.cart_lines")).isZero();
        assertThat(count("ordering.cart_auto_add_declines"))
                .as("the customer removed a dish, not the packaging: nothing is remembered as declined")
                .isZero();
        MvcResult priced = rawPrice(cart, view.get("version").asInt());
        assertThat(priced.getResponse().getStatus()).isNotEqualTo(200);
        assertThat(priced.getResponse().getContentAsString()).contains("CART_EMPTY");

        view = putLine(cart, view.get("version").asInt(), "plov", plov, "1");
        assertThat(lineKeys(view))
                .as("the rule applies again to the next thing the customer chooses")
                .containsExactly(key, "plov");
    }

    @Test
    @DisplayName("a mandatory line is dropped with the last dish, so a cart can be emptied one line at a time")
    void aMandatoryLineGoesWithTheLastDish() throws Exception {
        mandatoryAllowed(true);
        UUID rule = activeRule(mandatory(plainRule("packaging", bag, "[\"PICKUP\"]")));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        String key = "aa:" + rule;
        JsonNode view = putLine(cart, 1, "burger", burger, "1");
        view = putLine(cart, view.get("version").asInt(), "plov", plov, "1");
        assertThat(lineOf(view, key).get("autoAdd").get("mandatory").asBoolean())
                .isTrue();

        view = removeLine(cart, view.get("version").asInt(), "burger");
        assertThat(lineKeys(view)).contains(key, "plov");
        view = removeLine(cart, view.get("version").asInt(), "plov");

        assertThat(lineKeys(view))
                .as("the mandatory line cannot be removed by the customer, and is not left behind either")
                .isEmpty();
        assertThat(count("ordering.cart_lines")).isZero();
    }

    @Test
    @DisplayName("a cart that holds nothing but a rule's line is neither priced nor checked out, "
            + "whatever state the rules were left in")
    void aCartOfOnlyPackagingIsRefused() throws Exception {
        UUID rule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        JsonNode view = putLine(cart, 1, "burger", burger, "1");
        JsonNode priced = price(cart, view.get("version").asInt());
        assertThat(lineKeys(view)).contains("aa:" + rule);

        // The gate is closed and the customer's dish disappears underneath the cart (a stale row left by the
        // closed gate, which neither adds nor removes): only the rule's line is left, at the quoted version.
        gate(false, false);
        jdbc.sql("DELETE FROM ordering.cart_lines WHERE cart_id = :c AND line_key = 'burger'")
                .param("c", cart)
                .update();
        assertThat(count("ordering.cart_lines")).isEqualTo(1);

        MvcResult checkout = rawCheckout(cart, priced, "checkout-" + requests++);
        assertThat(checkout.getResponse().getStatus()).isNotIn(200, 201);
        assertThat(checkout.getResponse().getContentAsString()).contains("CART_EMPTY");
        assertThat(count("ordering.orders"))
                .as("no packaging-only order was placed")
                .isZero();

        MvcResult repriced = rawPrice(cart, priced.get("cartVersion").asInt());
        assertThat(repriced.getResponse().getStatus()).isNotEqualTo(200);
        assertThat(repriced.getResponse().getContentAsString()).contains("CART_EMPTY");
    }

    @Test
    @DisplayName("the operator's composer can take a rule's line off by sending its rule back as declined")
    void theOperatorCanTakeAnAutoAddedLineOff() throws Exception {
        UUID rule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]", "[\"WEB\",\"CALL_CENTRE\"]"));
        publish("CALLCENTRE");
        gate(true, false);
        String basket = """
                "customerAccountId":"%s","channelCode":"CALLCENTRE","fulfillmentMode":"PICKUP",
                 "paymentMethodCode":"CASH","lines":[{"variantId":"%s","quantity":1}]""".formatted(customerAccount, burger);

        JsonNode with = operatorQuote("{" + basket + "}");
        assertThat(with.get("autoAdded")).hasSize(1);
        assertThat(with.get("autoAdded").get(0).get("ruleId").asText()).isEqualTo(rule.toString());
        assertThat(with.get("totalMinor").asLong()).isEqualTo(42_000L);

        JsonNode without = operatorQuote("{" + basket + ",\"declinedAutoAddRuleIds\":[\"" + rule + "\"]}");
        assertThat(without.get("autoAdded")).isEmpty();
        assertThat(without.get("totalMinor").asLong()).as("burger only").isEqualTo(40_000L);
    }

    // ------------------------------------------------------------------------------- a republish

    @Test
    @DisplayName("price() after a republish re-evaluates the cart: an edited rule changes the injected quantity, an "
            + "archived one takes the line away, and each advances the cart's version")
    void aRepublishChangesTheInjectedSetAtPricing() throws Exception {
        UUID rule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        JsonNode view = putLine(cart, 1, "burger", burger, "1");
        int version = view.get("version").asInt();
        assertThat(lineOf(view, "aa:" + rule).get("quantity").decimalValue()).isEqualByComparingTo("1");

        // The author doubles the quantity and republishes; the customer has not touched the cart since.
        replaceRule(rule, 2, plainRule("cutlery", cutlery, "[\"PICKUP\"]").replace("\"quantity\":1", "\"quantity\":2"));
        publish("STOREFRONT");
        JsonNode doubled = price(cart, version);

        assertThat(doubled.get("changedByPricing").asBoolean())
                .as("the cart the customer saw is not the cart that was priced")
                .isTrue();
        assertThat(doubled.get("cartVersion").asInt()).isGreaterThan(version);
        assertThat(doubled.get("totalMinor").asLong())
                .as("burger 40,000 + two sets of cutlery at 2,000")
                .isEqualTo(44_000L);
        Map<String, Object> stored = jdbc.sql("SELECT quantity, auto_add_rule_version FROM ordering.cart_lines "
                        + "WHERE cart_id = :c AND line_key = :k")
                .param("c", cart)
                .param("k", "aa:" + rule)
                .query()
                .singleRow();
        assertThat((BigDecimal) stored.get("quantity")).isEqualByComparingTo("2");
        assertThat(stored.get("auto_add_rule_version"))
                .as("the line names the version of the rule that now puts it there")
                .isEqualTo(3);

        // The author then archives the rule and republishes: the line goes.
        archiveRule(rule, 3);
        publish("STOREFRONT");
        JsonNode gone = price(cart, doubled.get("cartVersion").asInt());

        assertThat(gone.get("changedByPricing").asBoolean()).isTrue();
        assertThat(gone.get("cartVersion").asInt())
                .isGreaterThan(doubled.get("cartVersion").asInt());
        assertThat(gone.get("totalMinor").asLong()).isEqualTo(40_000L);
        assertThat(count("ordering.cart_lines WHERE origin = 'AUTO_ADD'")).isZero();
    }

    // ---------------------------------------------------------------------- a moved cart

    @Test
    @DisplayName("moving the cart to another branch re-evaluates the rules there and keeps what was removed removed")
    void aMovedCartKeepsItsDeclines() throws Exception {
        UUID cutleryRule = activeRule(plainRule("cutlery", cutlery, "[\"PICKUP\"]"));
        UUID bagRule = activeRule(plainRule("bag", bag, "[\"PICKUP\"]"));
        publish("STOREFRONT");
        gate(true, false);
        UUID cart = openCart("STOREFRONT", "PICKUP");
        JsonNode view = putLine(cart, 1, "burger", burger, "1");
        view = removeLine(cart, view.get("version").asInt(), "aa:" + bagRule);

        MvcResult moved = mvc.perform(post(storefront() + "/carts/" + cart + "/location")
                        .with(customer())
                        .header(IDEMPOTENCY_HEADER, "move-" + requests++)
                        .header("If-Match", "\"" + view.get("version").asInt() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + SECOND_LOCATION + "\"}"))
                .andReturn();

        assertThat(moved.getResponse().getStatus())
                .as(moved.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode rebuilt = JSON.readTree(moved.getResponse().getContentAsString());
        assertThat(lineKeys(rebuilt))
                .as("the cutlery is injected anew at the new branch; the bag the customer removed is not")
                .containsExactly("aa:" + cutleryRule, "burger");
    }

    // ------------------------------------------------------------------------------- helpers

    private static String storefront() {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND;
    }

    private UUID openCart(String channel, String mode) throws Exception {
        MvcResult created = mvc.perform(post(storefront() + "/carts")
                        .with(customer())
                        .header(IDEMPOTENCY_HEADER, "cart-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + LOCATION + "\",\"channel\":\"" + channel
                                + "\",\"fulfillmentMode\":\"" + mode + "\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(200);
        return UUID.fromString(JSON.readTree(created.getResponse().getContentAsString())
                .get("cartId")
                .asText());
    }

    private MvcResult rawPut(UUID cart, int version, String lineKey, UUID variant, String quantity) throws Exception {
        return mvc.perform(put(storefront() + "/carts/" + cart + "/lines/" + lineKey)
                        .with(customer())
                        .header(IDEMPOTENCY_HEADER, "line-" + requests++)
                        .header("If-Match", "\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variant + "\",\"quantity\":" + quantity + "}"))
                .andReturn();
    }

    private JsonNode putLine(UUID cart, int version, String lineKey, UUID variant, String quantity) throws Exception {
        MvcResult result = rawPut(cart, version, lineKey, variant, quantity);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult rawDelete(UUID cart, int version, String lineKey) throws Exception {
        return mvc.perform(delete(storefront() + "/carts/" + cart + "/lines/" + lineKey)
                        .with(customer())
                        .header(IDEMPOTENCY_HEADER, "remove-" + requests++)
                        .header("If-Match", "\"" + version + "\""))
                .andReturn();
    }

    private JsonNode removeLine(UUID cart, int version, String lineKey) throws Exception {
        MvcResult result = rawDelete(cart, version, lineKey);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode price(UUID cart, int version) throws Exception {
        MvcResult result = mvc.perform(post(storefront() + "/carts/" + cart + "/pricing")
                        .with(customer())
                        .header(IDEMPOTENCY_HEADER, "price-" + requests++)
                        .header("If-Match", "\"" + version + "\""))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult rawPrice(UUID cart, int version) throws Exception {
        return mvc.perform(post(storefront() + "/carts/" + cart + "/pricing")
                        .with(customer())
                        .header(IDEMPOTENCY_HEADER, "price-" + requests++)
                        .header("If-Match", "\"" + version + "\""))
                .andReturn();
    }

    private UUID checkout(UUID cart, JsonNode priced) throws Exception {
        MvcResult result = mvc.perform(post(storefront() + "/checkouts")
                        .with(customer())
                        .header(IDEMPOTENCY_HEADER, "checkout-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartId\":\"" + cart + "\",\"cartVersion\":"
                                + priced.get("cartVersion").asInt()
                                + ",\"quoteId\":\"" + priced.get("quoteId").asText() + "\",\"contextHash\":\""
                                + priced.get("contextHash").asText() + "\",\"paymentMethodCode\":\"CASH\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isIn(200, 201);
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
                .get("orderId")
                .asText());
    }

    private JsonNode operatorQuote(String body) throws Exception {
        MvcResult quote = mvc.perform(post("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION
                                + "/orders/quote")
                        .with(operator())
                        .header(IDEMPOTENCY_HEADER, "quote-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(quote.getResponse().getStatus())
                .as(quote.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(quote.getResponse().getContentAsString());
    }

    private MvcResult rawCheckout(UUID cart, JsonNode priced, String idempotencyKey) throws Exception {
        return mvc.perform(post(storefront() + "/checkouts")
                        .with(customer())
                        .header(IDEMPOTENCY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartId\":\"" + cart + "\",\"cartVersion\":"
                                + priced.get("cartVersion").asInt()
                                + ",\"quoteId\":\"" + priced.get("quoteId").asText() + "\",\"contextHash\":\""
                                + priced.get("contextHash").asText() + "\",\"paymentMethodCode\":\"CASH\"}"))
                .andReturn();
    }

    private void advance(UUID orderId, String target) throws Exception {
        String path =
                "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/" + orderId;
        int version = JSON.readTree(mvc.perform(get(path).with(operator()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("summary")
                .get("version")
                .asInt();
        MvcResult moved = mvc.perform(post(path + "/state-actions")
                        .with(operator())
                        .header(IDEMPOTENCY_HEADER, "state-" + UUID.randomUUID())
                        .header("If-Match", "\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"" + target + "\",\"reasonCode\":\"KITCHEN\"}"))
                .andReturn();
        assertThat(moved.getResponse().getStatus())
                .as("-> " + target + ": " + moved.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private UUID orderLineOfRule(UUID orderId, UUID ruleId) {
        return jdbc.sql("SELECT id FROM ordering.order_lines WHERE order_id = :o AND auto_add_rule_id = :r "
                        + "AND revision_to IS NULL")
                .param("o", orderId)
                .param("r", ruleId)
                .query(UUID.class)
                .single();
    }

    /** A removal reason authored over the endpoint the settings screen uses. */
    private String removalReason() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/control-plane/tenants/" + TENANT + "/order-outcome-reasons")
                        .with(operator())
                        .header(IDEMPOTENCY_HEADER, "reason-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"LINE_REMOVAL","systemCategory":"CUSTOMER_CHANGED_MIND",\
                                "internalName":"Customer changed their mind","stockDisposition":"RETURN_TO_STOCK",\
                                "liabilityParty":"CUSTOMER",\
                                "customerTexts":{"ru":"Блюдо убрано","uz-Latn":"Taom olib tashlandi","en":"Removed"}}"""))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(created.getResponse().getContentAsString())
                .get("id")
                .asText();
    }

    private MvcResult amendRaw(UUID orderId, String key, String command) throws Exception {
        String path =
                "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/" + orderId;
        int version = JSON.readTree(mvc.perform(get(path).with(operator()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("summary")
                .get("version")
                .asInt();
        return mvc.perform(post(path + "/amendments")
                        .with(operator())
                        .header(IDEMPOTENCY_HEADER, key)
                        .header("If-Match", "\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commands\":[" + command + "],\"applyImmediately\":false,"
                                + "\"reasonCode\":\"OPERATOR_EDIT\"}"))
                .andReturn();
    }

    private String amendRefusal(UUID orderId, String key, String command) throws Exception {
        MvcResult refused = amendRaw(orderId, key, command);
        assertThat(refused.getResponse().getStatus())
                .as(refused.getResponse().getContentAsString())
                .isEqualTo(409);
        return JSON.readTree(refused.getResponse().getContentAsString())
                .path("reasonCode")
                .asText();
    }

    private static List<String> lineKeys(JsonNode cart) {
        List<String> keys = new ArrayList<>();
        cart.get("lines").forEach(line -> keys.add(line.get("lineKey").asText()));
        return keys.stream().sorted().toList();
    }

    private static JsonNode lineOf(JsonNode cart, String key) {
        for (JsonNode line : cart.get("lines")) {
            if (key.equals(line.get("lineKey").asText())) {
                return line;
            }
        }
        throw new AssertionError("no line " + key + " in " + cart);
    }

    private long count(String tableAndWhere) {
        return jdbc.sql("SELECT count(*) FROM " + tableAndWhere)
                .query(Long.class)
                .single();
    }

    private double shadowCount(String kind) {
        return metricCount("horecaos.autoadd.rules", "outcome", "shadow", "kind", kind);
    }

    private double metricCount(String name, String... tags) {
        var counter = metrics.find(name).tags(tags).counters().stream().findFirst();
        return counter.map(found -> found.count()).orElse(0.0);
    }

    // ---------------------------------------------------------------------- rule authoring

    private static String rulesPath() {
        return "/api/v1/control-plane/tenants/" + TENANT + "/brands/" + BRAND + "/catalog/auto-add-rules";
    }

    private static String plainRule(String code, UUID variant, String modes) {
        return plainRule(code, variant, modes, "[\"WEB\"]");
    }

    private static String plainRule(String code, UUID variant, String modes, String channels) {
        return """
                {"code":"%s","kind":"PLAIN","addVariantId":"%s","quantity":1,
                 "fulfillmentModes":%s,"channelSystemTypes":%s,
                 "customerText":{"ru":"Приборы","uz":"Qoshiq"}}""".formatted(code, variant, modes, channels);
    }

    private String triggeredRule(String code, UUID variant, String modes, UUID triggerVariant) {
        return triggeredRule(code, variant, modes, triggerVariant, null, 2);
    }

    private String triggeredRule(
            String code,
            UUID variant,
            String modes,
            @Nullable UUID triggerVariant,
            @Nullable UUID triggerCategory,
            int threshold) {
        String trigger = triggerVariant != null
                ? "{\"variantId\":\"" + triggerVariant + "\"}"
                : "{\"categoryId\":\"" + triggerCategory + "\"}";
        return """
                {"code":"%s","kind":"PRODUCT_TRIGGERED","addVariantId":"%s","quantity":1,
                 "triggerThreshold":%d,"repeatPerMultiple":true,
                 "fulfillmentModes":%s,"channelSystemTypes":["WEB"],"triggers":[%s],
                 "customerText":{"ru":"Пакет","uz":"Paket"}}""".formatted(code, variant, threshold, modes, trigger);
    }

    private String portionBandRule(String code, String modes) {
        return """
                {"code":"%s","kind":"PORTION_BAND","fulfillmentModes":%s,"channelSystemTypes":["WEB"],
                 "bands":[{"portionsFrom":1,"portionsTo":4,"addVariantId":"%s","quantity":1},
                          {"portionsFrom":4,"addVariantId":"%s","quantity":2}],
                 "customerText":{"ru":"Приборы","uz":"Qoshiq"}}""".formatted(code, modes, cutlery, cutlery);
    }

    private static String mandatory(String createBody) throws Exception {
        ObjectNode node = (ObjectNode) JSON.readTree(createBody);
        node.put("mandatory", true);
        return JSON.writeValueAsString(node);
    }

    /** Creates a draft over HTTP and activates it with a replace, the way the console does. */
    private UUID activeRule(String createBody) throws Exception {
        MvcResult created = mvc.perform(post(rulesPath())
                        .with(operator())
                        .header(IDEMPOTENCY_HEADER, "rule-" + requests++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(200);
        UUID ruleId = UUID.fromString(JSON.readTree(created.getResponse().getContentAsString())
                .get("ruleId")
                .asText());
        ObjectNode replace = (ObjectNode) JSON.readTree(createBody);
        replace.remove("code");
        replace.remove("kind");
        replace.put("status", "ACTIVE");
        MvcResult activated = mvc.perform(put(rulesPath() + "/" + ruleId)
                        .with(operator())
                        .header(IDEMPOTENCY_HEADER, "rule-" + requests++)
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(replace)))
                .andReturn();
        assertThat(activated.getResponse().getStatus())
                .as(activated.getResponse().getContentAsString())
                .isEqualTo(200);
        return ruleId;
    }

    private void replaceRule(UUID ruleId, int version, String createBody) throws Exception {
        ObjectNode replace = (ObjectNode) JSON.readTree(createBody);
        replace.remove("code");
        replace.remove("kind");
        replace.put("status", "ACTIVE");
        MvcResult replaced = mvc.perform(put(rulesPath() + "/" + ruleId)
                        .with(operator())
                        .header(IDEMPOTENCY_HEADER, "rule-" + requests++)
                        .header("If-Match", String.valueOf(version))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(replace)))
                .andReturn();
        assertThat(replaced.getResponse().getStatus())
                .as(replaced.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private void archiveRule(UUID ruleId, int version) throws Exception {
        MvcResult archived = mvc.perform(post(rulesPath() + "/" + ruleId + "/archive")
                        .with(operator())
                        .header(IDEMPOTENCY_HEADER, "rule-" + requests++)
                        .header("If-Match", String.valueOf(version)))
                .andReturn();
        assertThat(archived.getResponse().getStatus())
                .as(archived.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private void publish(String channel) {
        var result = publication.publish(TENANT, BRAND, catalogId, channel, null);
        assertThat(result.status())
                .as(String.valueOf(result.report().blockers()))
                .isEqualTo(PublicationStatus.PUBLISHED);
    }

    // ------------------------------------------------------------------------ configuration

    private void gate(boolean enabled, boolean shadow) {
        set(OrderingConfigurationKeys.AUTO_ADD_ENABLED, ResourceScope.brand(TENANT, BRAND), enabled);
        set(OrderingConfigurationKeys.AUTO_ADD_SHADOW_ENABLED, ResourceScope.brand(TENANT, BRAND), shadow);
    }

    private void mandatoryAllowed(boolean allowed) {
        set(CatalogConfigurationKeys.AUTO_ADD_MANDATORY_ALLOWED, ResourceScope.tenant(TENANT), allowed);
    }

    private <T> void set(ConfigurationKey<T> key, ResourceScope scope, T value) {
        Long version = configuration.currentVersion(key, scope).orElse(null);
        configuration.set(key, scope, value, false, version, ActorRef.user(PLATFORM_ADMIN, null), "auto-add test");
    }

    // ---------------------------------------------------------------------------- the seeds

    private static RequestPostProcessor customer() {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(CUSTOMER_SUBJECT));
    }

    private static RequestPostProcessor operator() {
        return jwt().jwt(builder ->
                builder.subject(OPERATOR).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :tenantId,
                        'ACTIVE', 'test-fixture', 'auto-add cart test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", role.scopeType().name())
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'auto-add-cart', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        UUID scheduleId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.service_schedules (id, tenant_id, brand_id, name, accepts_scheduled_orders)
                VALUES (:id, :tenantId, :brandId, 'Standard hours', true)
                """)
                .param("id", scheduleId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        for (int day = 1; day <= 7; day++) {
            jdbc.sql("""
                    INSERT INTO tenant.service_schedule_rules (schedule_id, sequence, day_of_week,
                        opens_at, closes_at)
                    VALUES (:scheduleId, :sequence, :day, '00:00', '23:59')
                    """)
                    .param("scheduleId", scheduleId)
                    .param("sequence", day)
                    .param("day", day)
                    .update();
        }
        for (UUID location : List.of(LOCATION, SECOND_LOCATION)) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("code", location.equals(LOCATION) ? "MAIN01" : "MAIN02")
                    .param("slug", location.equals(LOCATION) ? "main-01" : "main-02")
                    .update();
            jdbc.sql("""
                    INSERT INTO tenant.location_service_state (location_id, tenant_id, brand_id, mode)
                    VALUES (:locationId, :tenantId, :brandId, 'FOLLOW_SCHEDULE')
                    """)
                    .param("locationId", location)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .update();
            for (String mode : List.of("PICKUP", "DELIVERY")) {
                jdbc.sql("""
                        INSERT INTO tenant.location_service_bindings (tenant_id, brand_id, location_id,
                            fulfillment_mode, schedule_id)
                        VALUES (:tenantId, :brandId, :locationId, :mode, :scheduleId)
                        """)
                        .param("tenantId", TENANT)
                        .param("brandId", BRAND)
                        .param("locationId", location)
                        .param("mode", mode)
                        .param("scheduleId", scheduleId)
                        .update();
            }
        }
        customerAccount = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :tenantId, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", customerAccount).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO customer.principal_links (
                    id, tenant_id, customer_account_id, issuer, subject, status, linked_at)
                VALUES (:id, :tenantId, :accountId, :issuer, :subject, 'ACTIVE', :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("accountId", customerAccount)
                .param("issuer", ISSUER)
                .param("subject", CUSTOMER_SUBJECT)
                .param("now", now)
                .update();

        jdbc.sql("""
                INSERT INTO payments.payment_methods (id, tenant_id, code, display_name, responsibility, status)
                VALUES (:id, :tenantId, 'CASH', 'CASH', 'OPERATOR', 'ACTIVE')
                ON CONFLICT ON CONSTRAINT uq_payment_method_code DO NOTHING
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();
        channel("STOREFRONT", "WEB");
        channel("CALLCENTRE", "CALL_CENTRE");
        channel("BOT", "TELEGRAM");
    }

    private void channel(String code, String systemType) {
        UUID channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :tenantId, :code, :systemType, :code, 'ACTIVE', false)
                """)
                .param("id", channelId)
                .param("tenantId", TENANT)
                .param("code", code)
                .param("systemType", systemType)
                .update();
        for (UUID location : List.of(LOCATION, SECOND_LOCATION)) {
            jdbc.sql("""
                    INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                    VALUES (:tenantId, :channelId, :locationId, 'ACTIVE')
                    """)
                    .param("tenantId", TENANT)
                    .param("channelId", channelId)
                    .param("locationId", location)
                    .update();
        }
        for (String mode : List.of("PICKUP", "DELIVERY")) {
            jdbc.sql("""
                    INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id, fulfillment_mode, enabled)
                    VALUES (:tenantId, :channelId, :mode, true)
                    """)
                    .param("tenantId", TENANT)
                    .param("channelId", channelId)
                    .param("mode", mode)
                    .update();
        }
        jdbc.sql("""
                INSERT INTO tenant.channel_payment_methods (tenant_id, channel_id, payment_method_code, enabled)
                VALUES (:tenantId, :channelId, 'CASH', true)
                ON CONFLICT DO NOTHING
                """).param("tenantId", TENANT).param("channelId", channelId).update();
    }

    private void seedCatalogAndPricing() {
        catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        mainsCategory = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.categories (id, tenant_id, brand_id, catalog_id, code, sort_order, status)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'MAINS', 0, 'ACTIVE')
                """)
                .param("id", mainsCategory)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id, locale, name)
                VALUES (:tenantId, :brandId, 'CATEGORY', :id, 'uz', 'Mains')
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("id", mainsCategory)
                .update();
        burger = seedProduct("BURGER", "Burger", true);
        plov = seedProduct("PLOV", "Osh", true);
        cola = seedProduct("COLA", "Cola", false);
        cutlery = seedProduct("CUTLERY", "Cutlery", false);
        bag = seedProduct("BAG", "Bag", false);

        physical.replace(
                TENANT,
                BRAND,
                burger,
                new PhysicalAttributes(
                        null, null, false, null, null, false, null, null, null, null, null, new BigDecimal("2")),
                0,
                "auto-add-author");
        physical.replace(
                TENANT,
                BRAND,
                plov,
                new PhysicalAttributes(
                        null, null, false, null, null, false, null, null, null, null, null, BigDecimal.ONE),
                0,
                "auto-add-author");

        UUID priceBook = UUID.randomUUID();
        OffsetDateTime validFrom = OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO pricing.price_books (id, tenant_id, brand_id, name, currency, status,
                    valid_from, priority)
                VALUES (:id, :tenantId, :brandId, 'BRAND_MENU', 'UZS', 'ACTIVE', :from, 0)
                """)
                .param("id", priceBook)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("from", validFrom)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.price_book_assignments (id, tenant_id, brand_id, price_book_id,
                    scope_type, scope_id, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'BRAND', NULL, :from, 0)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("priceBookId", priceBook)
                .param("from", validFrom)
                .update();
        price(priceBook, burger, 40_000L, validFrom);
        price(priceBook, plov, 30_000L, validFrom);
        price(priceBook, cola, 5_000L, validFrom);
        price(priceBook, cutlery, 2_000L, validFrom);
        price(priceBook, bag, 1_000L, validFrom);
        jdbc.sql("""
                INSERT INTO pricing.tax_profiles (id, tenant_id, brand_id, jurisdiction_code, mode,
                    rate_basis_points, valid_from)
                VALUES (:id, :tenantId, :brandId, 'UZ', 'INCLUSIVE', 1200, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("from", validFrom)
                .update();

        for (UUID location : List.of(LOCATION, SECOND_LOCATION)) {
            for (UUID variant : List.of(burger, plov, cola, cutlery, bag)) {
                catalogAuthoring.setOffering(
                        TENANT, BRAND, location, variant, OfferingStatus.AVAILABLE, List.of("PICKUP", "DELIVERY"));
            }
        }
    }

    private UUID seedProduct(String code, String name, boolean inMains) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status, is_default)
                VALUES (:id, :tenantId, :brandId, :productId, :sku, 'ACTIVE', true)
                """)
                .param("id", variantId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .param("sku", "SKU-" + code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.catalog_products (tenant_id, brand_id, catalog_id, product_id)
                VALUES (:tenantId, :brandId, :catalogId, :productId)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .param("productId", productId)
                .update();
        if (inMains) {
            jdbc.sql("""
                    INSERT INTO catalog.category_products (tenant_id, brand_id, category_id, product_id)
                    VALUES (:tenantId, :brandId, :categoryId, :productId)
                    """)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("categoryId", mainsCategory)
                    .param("productId", productId)
                    .update();
        }
        jdbc.sql("""
                INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id, locale, name)
                VALUES (:tenantId, :brandId, 'PRODUCT', :productId, 'uz', :name)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .param("name", name)
                .update();
        return variantId;
    }

    private void price(UUID priceBook, UUID variant, long amount, OffsetDateTime validFrom) {
        jdbc.sql("""
                INSERT INTO pricing.prices (id, tenant_id, brand_id, price_book_id, priceable_type,
                    priceable_id, amount_minor, valid_from)
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'VARIANT', :variantId, :amount, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("priceBookId", priceBook)
                .param("variantId", variant)
                .param("amount", amount)
                .param("from", validFrom)
                .update();
    }
}
