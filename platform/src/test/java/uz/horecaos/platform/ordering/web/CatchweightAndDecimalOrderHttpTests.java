package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.AttachmentPolicy;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.NewComboGroup;
import uz.horecaos.platform.catalog.application.PhysicalAttributesAuthoringService;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CompositeProducts.AttachmentOwnerType;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboComponent;
import uz.horecaos.platform.catalog.domain.CompositeProducts.Visibility;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.PhysicalAttributes;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.kitchen.application.KitchenTicketService;
import uz.horecaos.platform.kitchen.domain.ReleaseMode;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.ordering.application.CheckoutService;
import uz.horecaos.platform.ordering.application.OrderStateService;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.pos.application.port.PosOrderSource;
import uz.horecaos.platform.pos.infrastructure.ordering.JdbcPosOrderSource;
import uz.horecaos.platform.pricing.PromotionDbFixture;
import uz.horecaos.platform.pricing.application.PromotionAuthoringService;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.reporting.application.DayCloseService;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcClassificationStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0137 end to end, against the real, fully wired application: a half portion of plov
 * and a cake sold by weight, from the product editor's attributes to the report.
 *
 * <p>Everything up to the order is the customer's own path (the published menu, the cart's
 * portion rule, pricing, checkout); the weighing, the handover blocker and the order read-back
 * are HTTP, the operator console's contract. The kitchen ticket, the POS read and the day's
 * report are the production services over the same rows, so a decimal that was rounded to a
 * whole number anywhere on the way shows up as the wrong figure here and nowhere else.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatchweightAndDecimalOrderHttpTests {

    private static final UUID TENANT = UUID.fromString("018fc100-c000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fc100-c000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018fc100-c000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018fc100-c000-7000-8000-0000000000b2");
    private static final UUID LOCATION = UUID.fromString("018fc100-c000-7000-8000-0000000000c1");
    private static final UUID CUSTOMER = UUID.fromString("018fc100-c000-7000-8000-0000000000d1");

    private static final String OPERATOR = "weighing-operator";
    private static final String OUTSIDER = "weighing-outsider";
    private static final String ACTOR_SUBJECT = "weighing-author";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

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
        // The kitchen ticket raises a realtime signal on the calling thread; against the dead
        // broker above every one of them waits out the producer's ten-second metadata block.
        registry.add("horecaos.realtime.signals.publish", () -> "false");
        // Closing the day pseudonymises the subject of each order, which needs the key.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private CartService carts;

    @Autowired
    private CheckoutService checkout;

    @Autowired
    private JdbcCartStore cartStore;

    @Autowired
    private CatalogAuthoringService catalogAuthoring;

    @Autowired
    private CatalogPublicationService publication;

    @Autowired
    private PhysicalAttributesAuthoringService physical;

    @Autowired
    private CompositeProductAuthoringService composites;

    @Autowired
    private PromotionAuthoringService promotionAuthoring;

    @Autowired
    private KitchenTicketService kitchenTickets;

    @Autowired
    private OrderStateService orderState;

    @Autowired
    private JdbcPosOrderSource posSource;

    @Autowired
    private DayCloseService dayClose;

    @Autowired
    private JdbcReportingStore reporting;

    @Autowired
    private JdbcClassificationStore classification;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID plovVariant;
    private UUID cakeVariant;
    private UUID sodaVariant;
    private UUID catalogId;
    private UUID priceBook;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("""
                TRUNCATE TABLE kitchen.tickets, kitchen.stations CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE reporting.fact_order_line, reporting.fact_order, reporting.fact_order_tender,
                    reporting.fact_refund CASCADE
                """).update();
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
                    ordering.cart_lines, ordering.carts CASCADE
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
                TRUNCATE TABLE catalog.variant_physical_attributes, catalog.publication_items,
                    catalog.publications, catalog.location_offerings, catalog.translations,
                    catalog.combo_components, catalog.combo_groups,
                    catalog.product_modifier_groups, catalog.variant_modifier_groups,
                    catalog.modifier_options, catalog.modifier_groups,
                    catalog.catalog_products, catalog.fiscal_classifications, catalog.fees,
                    catalog.variants, catalog.products, catalog.catalogs CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        roleRegistry.synchronize();

        seedTenancy();
        seedCatalogAndPricing();
        seedFallbackStation();
        grant(OPERATOR, PlatformRole.TENANT_OWNER, TENANT);
        grant(OUTSIDER, PlatformRole.TENANT_OWNER, OTHER_TENANT);
    }

    // ------------------------------------------------------------ the cart's rule

    @Test
    @DisplayName("the cart takes a fraction only of a splittable variant, in its portion step, and names the step")
    void theCartEnforcesThePublishedPortionStep() {
        UUID cart = openCart();

        // 0.5 of the splittable plov (step 0.5): taken. 1.5, 2: taken too.
        putLine(cart, "plov", plovVariant, "0.5");
        putLine(cart, "plov", plovVariant, "1.5");
        putLine(cart, "plov", plovVariant, "2");
        assertThat(lineQuantity(cart, "plov")).isEqualByComparingTo("2");

        // 0.3 is not a whole number of 0.5-portions.
        assertThat(refusal(cart, plovVariant, "0.3")).satisfies(refused -> {
            assertThat(refused.code()).isEqualTo("QUANTITY_NOT_A_PORTION");
            assertThat(refused.getMessage()).contains("0.5");
        });
        // A can of soda has no physical block: whole units only, exactly as before the column widened.
        assertThat(refusal(cart, sodaVariant, "0.5").code()).isEqualTo("FRACTIONAL_QUANTITY_NOT_ALLOWED");
        // A cake sold by weight is not splittable, so it cannot be ordered by the half either.
        assertThat(refusal(cart, cakeVariant, "0.5").code()).isEqualTo("FRACTIONAL_QUANTITY_NOT_ALLOWED");
        // Whole units of both stay fine.
        putLine(cart, "soda", sodaVariant, "3");
        putLine(cart, "cake", cakeVariant, "1");

        // A quantity the column cannot hold is refused by name rather than rounded by the database.
        assertThat(refusal(cart, plovVariant, "0.0005").code()).isEqualTo("QUANTITY_OUT_OF_RANGE");
        assertThat(refusal(cart, plovVariant, "1000").code()).isEqualTo("QUANTITY_OUT_OF_RANGE");
        assertThat(refusal(cart, plovVariant, "0").code()).isEqualTo("QUANTITY_OUT_OF_RANGE");
    }

    // ----------------------------------------------- a half portion, cart to report

    @Test
    @DisplayName("half a plov is half a plov in the quote, the order, the ticket, the till and the report")
    void aHalfPortionTravelsFromTheCartToTheReport() throws Exception {
        UUID cart = openCart();
        putLine(cart, "plov", plovVariant, "0.5");
        var priced = tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        assertThat(priced.quote().totalMinor()).as("half of 40,000").isEqualTo(20_000L);
        assertThat(priced.quote().lines()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualByComparingTo("0.5");
            assertThat(line.unitAmountMinor())
                    .as("the unit is one whole portion")
                    .isEqualTo(40_000L);
            assertThat(line.finalAmountMinor()).isEqualTo(20_000L);
        });
        assertThat(jdbc.sql("SELECT quantity FROM pricing.quote_lines WHERE quote_id = :id")
                        .param("id", priced.quote().quoteId())
                        .query(BigDecimal.class)
                        .single())
                .as("the stored quote line holds the fraction, not a rounded whole")
                .isEqualByComparingTo("0.5");

        UUID orderId = checkoutCart(cart, "half-plov-1");

        // The order.
        assertThat(jdbc.sql("SELECT quantity FROM ordering.order_lines WHERE order_id = :id")
                        .param("id", orderId)
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("0.5");
        JsonNode detail = orderDetail(orderId);
        assertThat(detail.get("summary").get("totalMinor").asLong()).isEqualTo(20_000L);
        assertThat(detail.get("lines").get(0).get("quantity").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(detail.get("lines").get(0).get("catchweight").isNull())
                .as("plov is not sold by weight")
                .isTrue();

        // The kitchen ticket: one item, half a portion, and the station is asked for a whole plate.
        confirmed(orderId);
        var ticket = tx(() -> kitchenTickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM));
        assertThat(kitchenTickets.items(TENANT, ticket.id()))
                .singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualByComparingTo("0.5"));

        // The till reads the same fraction off the order it is sent.
        PosOrderSource.ExportableOrder exportable =
                posSource.find(TENANT, orderId, "TEST").orElseThrow();
        assertThat(exportable.lines())
                .singleElement()
                .satisfies(line -> assertThat(line.quantity()).isEqualByComparingTo("0.5"));

        // The report: the order is completed, the day is closed, and the product sold half a portion.
        advance(orderId, "PREPARING");
        advance(orderId, "READY");
        advance(orderId, "COMPLETED");
        LocalDate today = LocalDate.now(TASHKENT);
        dayClose.close(TENANT, today);

        assertThat(jdbc.sql("SELECT quantity FROM reporting.fact_order_line WHERE order_id = :id")
                        .param("id", orderId)
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("0.5");
        assertThat(jdbc.sql("SELECT item_count FROM reporting.fact_order WHERE order_id = :id")
                        .param("id", orderId)
                        .query(Integer.class)
                        .single())
                .as("item_count is an integer column: half a plate is counted as the plate it occupies")
                .isEqualTo(1);
        var sales = reporting.readVariantSales(
                TENANT,
                today,
                today,
                List.of(),
                List.of(),
                JdbcReportingStore.VariantSalesSort.QUANTITY_DESC,
                10,
                null);
        assertThat(sales).singleElement().satisfies(row -> {
            assertThat(row.totalQuantity())
                    .as("the per-product count is 0.5, not the 1 a ::integer cast would have made it")
                    .isEqualByComparingTo("0.5");
            assertThat(row.totalNetSom()).isEqualTo(20_000L);
        });
        assertThat(classification.readVariantBuckets(TENANT, today, today, List.of(), List.of(), 1))
                .singleElement()
                .satisfies(bucket -> assertThat(bucket.quantity()).isEqualByComparingTo("0.5"));
    }

    // ------------------------------------------------ the wire form of a whole quantity

    @Test
    @DisplayName("a whole quantity is written on the wire as the integer it was before the column widened")
    void aWholeQuantityIsWrittenAsTheIntegerItWas() throws Exception {
        UUID cart = openCart();
        putLine(cart, "plov", plovVariant, "2");
        putLine(cart, "soda", sodaVariant, "20");
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkoutCart(cart, "whole-quantities-1");

        MvcResult result =
                mvc.perform(get(orderPath(orderId)).with(tokenFor(OPERATOR))).andReturn();
        String wire = result.getResponse().getContentAsString();

        // numeric(10,3) hands back 2.000 and 20.000; a client that read 2 and 20 yesterday reads them today.
        assertThat(wire)
                .contains("\"quantity\":2,")
                .contains("\"quantity\":20,")
                .doesNotContain("\"quantity\":2.0")
                .doesNotContain("\"quantity\":20.0");
    }

    // -------------------------------------------------------- the weighed cake

    @Test
    @DisplayName("a cake is quoted at its nominal weight, weighed at the pass, and the order, its lines and "
            + "its cash settlement follow the scale")
    void aWeighedCakeIsReconciledAtHandover() throws Exception {
        UUID orderId = placeOrder(Map.of("cake", cakeVariant), "cake-1");

        // Provisional: 12 quanta of 100 g at 15,000 = 180,000, and the order says so.
        JsonNode placed = orderDetail(orderId);
        assertThat(placed.get("summary").get("totalMinor").asLong()).isEqualTo(180_000L);
        JsonNode provisional = placed.get("lines").get(0).get("catchweight");
        assertThat(provisional.get("provisional").asBoolean()).isTrue();
        assertThat(provisional.get("quantumGrams").asInt()).isEqualTo(100);
        assertThat(provisional.get("nominalGramsPerUnit").asInt()).isEqualTo(1_200);
        assertThat(provisional.get("pricePerQuantumMinor").asLong()).isEqualTo(15_000L);
        UUID cakeLine = lineIdOf(orderId, cakeVariant);
        assertThat(cashDue(orderId))
                .as("the courier is told to collect the quoted amount")
                .isEqualTo(180_000L);
        Map<String, Object> quoted = jdbc.sql("SELECT ql.catchweight_quantum_grams, ql.catchweight_nominal_grams, "
                        + "ql.catchweight_price_per_quantum_minor, ql.actual_weight_grams "
                        + "FROM pricing.quote_lines ql JOIN ordering.orders o "
                        + "ON o.pricing_quote_id = ql.quote_id AND o.tenant_id = ql.tenant_id "
                        + "WHERE o.id = :id AND ql.catchweight_quantum_grams IS NOT NULL")
                .param("id", orderId)
                .query()
                .singleRow();
        assertThat(quoted.get("catchweight_quantum_grams"))
                .as("the quote remembers it priced this line by weight, from the PUBLISHED facts")
                .isEqualTo(100);
        assertThat(quoted.get("catchweight_nominal_grams")).isEqualTo(1_200);
        assertThat(quoted.get("catchweight_price_per_quantum_minor")).isEqualTo(15_000L);
        assertThat(quoted.get("actual_weight_grams")).as("not weighed yet").isNull();

        advanceToReady(orderId);

        // Weighed at 1,340 g: 15,000 x 1,340 / 100 = 201,000.
        MvcResult weighed = weigh(orderId, cakeLine, 1_340, version(orderId));
        assertThat(weighed.getResponse().getStatus())
                .as(weighed.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode result = JSON.readTree(weighed.getResponse().getContentAsString());
        assertThat(result.get("changed").asBoolean()).isTrue();
        assertThat(result.get("totalMinor").asLong()).isEqualTo(201_000L);
        assertThat(result.get("deltaTotalMinor").asLong()).isEqualTo(21_000L);
        assertThat(result.get("lineFinalAmountMinor").asLong()).isEqualTo(201_000L);
        assertThat(result.get("revision").asInt()).isEqualTo(2);
        assertThat(weighed.getResponse().getHeader("ETag"))
                .isEqualTo("W/\"" + result.get("orderVersion").asInt() + "\"");

        // The order, its line, its revision history and the money to collect all say 201,000.
        Map<String, Object> order = jdbc.sql(
                        "SELECT subtotal_minor, tax_minor, discount_minor, fee_minor, total_minor, current_revision "
                                + "FROM ordering.orders WHERE id = :id")
                .param("id", orderId)
                .query()
                .singleRow();
        assertThat(order.get("total_minor")).isEqualTo(201_000L);
        assertThat(money(order, "subtotal_minor")
                        + money(order, "tax_minor")
                        + money(order, "fee_minor")
                        - money(order, "discount_minor"))
                .as("ck_order_total_reconciles")
                .isEqualTo(201_000L);
        Map<String, Object> line = jdbc.sql("SELECT * FROM ordering.order_lines WHERE id = :id")
                .param("id", cakeLine)
                .query()
                .singleRow();
        assertThat(line.get("actual_weight_grams")).isEqualTo(1_340);
        assertThat(line.get("final_amount_minor")).isEqualTo(201_000L);
        assertThat(line.get("base_amount_minor")).isEqualTo(201_000L);
        assertThat(line.get("unit_amount_minor"))
                .as("the line still says what one cake was quoted at")
                .isEqualTo(180_000L);
        assertThat(line.get("catchweight_price_per_quantum_minor")).isEqualTo(15_000L);
        assertThat(line.get("tax_amount_minor"))
                .as("VAT 12% extracted from 201,000")
                .isEqualTo(21_536L);
        Map<String, Object> revision = jdbc.sql(
                        "SELECT source, amendment_id, total_minor, delta_total_minor, pricing_quote_id "
                                + "FROM ordering.order_revisions WHERE order_id = :id AND revision = 2")
                .param("id", orderId)
                .query()
                .singleRow();
        assertThat(revision.get("source")).isEqualTo("CATCHWEIGHT");
        assertThat(revision.get("amendment_id"))
                .as("a reconciliation is not an amendment")
                .isNull();
        assertThat(revision.get("total_minor")).isEqualTo(201_000L);
        assertThat(revision.get("delta_total_minor")).isEqualTo(21_000L);
        assertThat(jdbc.sql("SELECT total_minor FROM ordering.order_revisions WHERE order_id = :id AND revision = 1")
                        .param("id", orderId)
                        .query(Long.class)
                        .single())
                .as("revision 1 is the checkout snapshot, byte-identical for ever")
                .isEqualTo(180_000L);
        assertThat(cashDue(orderId))
                .as("the courier's cash figure is the weighed one")
                .isEqualTo(201_000L);
        assertThat(jdbc.sql("SELECT amount_minor FROM payments.tenders t JOIN payments.order_settlements s "
                                + "ON s.id = t.settlement_id WHERE s.order_id = :id")
                        .param("id", orderId)
                        .query(Long.class)
                        .single())
                .isEqualTo(201_000L);
        assertThat(jdbc.sql("SELECT status FROM pricing.quotes WHERE id = :id")
                        .param("id", revision.get("pricing_quote_id"))
                        .query(String.class)
                        .single())
                .as("the re-price behind the weight is consumed, so it cannot be reused")
                .isEqualTo("ACCEPTED");

        // The read-back.
        JsonNode reread = orderDetail(orderId);
        assertThat(reread.get("summary").get("totalMinor").asLong()).isEqualTo(201_000L);
        assertThat(reread.get("currentRevision").asInt()).isEqualTo(2);
        JsonNode weighedLine = reread.get("lines").get(0).get("catchweight");
        assertThat(weighedLine.get("provisional").asBoolean()).isFalse();
        assertThat(weighedLine.get("actualWeightGrams").asInt()).isEqualTo(1_340);

        // The audit fact names the before and after of the money and the weight.
        String audited = jdbc.sql("SELECT change_document::text FROM audit.audit_events "
                        + "WHERE action_code = 'ordering.order.catchweight-reconciled'")
                .query(String.class)
                .single();
        assertThat(audited).contains("180000").contains("201000").contains("1340");

        // The same weight again is a no-op: no second revision.
        MvcResult again = weigh(
                orderId, cakeLine, 1_340, reread.get("summary").get("version").asInt());
        assertThat(JSON.readTree(again.getResponse().getContentAsString())
                        .get("changed")
                        .asBoolean())
                .isFalse();
        assertThat(revisionCount(orderId)).isEqualTo(2);

        // A lighter reading (the operator re-weighs) corrects it again, downwards.
        MvcResult lighter = weigh(orderId, cakeLine, 1_100, version(orderId));
        JsonNode lighterResult = JSON.readTree(lighter.getResponse().getContentAsString());
        assertThat(lighterResult.get("totalMinor").asLong()).isEqualTo(165_000L);
        assertThat(lighterResult.get("deltaTotalMinor").asLong()).isEqualTo(-36_000L);
        assertThat(lighterResult.get("revision").asInt()).isEqualTo(3);
        assertThat(cashDue(orderId)).isEqualTo(165_000L);
    }

    @Test
    @DisplayName("an order cannot leave the pass while a weighed line is unweighed, and can once it is")
    void theHandoverIsBlockedUntilTheWeightIsCaptured() throws Exception {
        UUID orderId = placeOrder(Map.of("cake", cakeVariant, "soda", sodaVariant), "cake-2");
        advanceToReady(orderId);
        UUID cakeLine = lineIdOf(orderId, cakeVariant);

        MvcResult refused = stateAction(orderId, "COMPLETED", version(orderId));

        assertThat(refused.getResponse().getStatus())
                .as(refused.getResponse().getContentAsString())
                .isEqualTo(409);
        JsonNode problem = JSON.readTree(refused.getResponse().getContentAsString());
        assertThat(problem.get("reason").asText()).isEqualTo("CATCHWEIGHT_NOT_RECONCILED");
        assertThat(problem.get("orderLineIds")).extracting(JsonNode::asText).containsExactly(cakeLine.toString());
        assertThat(statusOf(orderId)).isEqualTo("READY");

        // The kitchen's own pickup handover proposal is refused the same way, and not rolled back.
        assertThat(tx(() -> orderState.proposeProgress(
                        TENANT,
                        orderId,
                        OrderStatus.COMPLETED,
                        "kitchen-handover-1",
                        "KITCHEN_HANDOVER",
                        "SERVICE",
                        "kitchen",
                        null)))
                .isEqualTo(OrderStateService.ProgressProposal.REFUSED);
        assertThat(statusOf(orderId)).isEqualTo("READY");

        assertThat(weigh(orderId, cakeLine, 1_250, version(orderId))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        MvcResult completed = stateAction(orderId, "COMPLETED", version(orderId));
        assertThat(completed.getResponse().getStatus())
                .as(completed.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(statusOf(orderId)).isEqualTo("COMPLETED");

        // Once the order has left the pass the weight can no longer be changed.
        MvcResult late = weigh(orderId, cakeLine, 1_300, version(orderId));
        assertThat(late.getResponse().getStatus()).isEqualTo(422);
        assertThat(JSON.readTree(late.getResponse().getContentAsString())
                        .get("reason")
                        .asText())
                .isEqualTo("ORDER_NOT_WEIGHABLE");
    }

    @Test
    @DisplayName(
            "an order with an unweighed line is not handed over by the kitchen proposal either, and is once weighed")
    void theKitchenProposalFollowsTheSameRule() throws Exception {
        UUID orderId = placeOrder(Map.of("cake", cakeVariant), "cake-3");
        advanceToReady(orderId);
        UUID cakeLine = lineIdOf(orderId, cakeVariant);
        weigh(orderId, cakeLine, 1_200, version(orderId));

        assertThat(tx(() -> orderState.proposeProgress(
                        TENANT,
                        orderId,
                        OrderStatus.COMPLETED,
                        "kitchen-handover-2",
                        "KITCHEN_HANDOVER",
                        "SERVICE",
                        "kitchen",
                        null)))
                .isEqualTo(OrderStateService.ProgressProposal.APPLIED);
        assertThat(statusOf(orderId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a weight that matches the nominal one changes no money, and is accepted even when payment is taken")
    void aWeightEqualToTheNominalOneMovesNoMoney() throws Exception {
        UUID orderId = placeOrder(Map.of("cake", cakeVariant), "cake-4");
        advanceToReady(orderId);
        jdbc.sql("UPDATE ordering.orders SET payment_status_projection = 'CAPTURED' WHERE id = :id")
                .param("id", orderId)
                .update();

        MvcResult exact = weigh(orderId, lineIdOf(orderId, cakeVariant), 1_200, version(orderId));

        assertThat(exact.getResponse().getStatus())
                .as(exact.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode result = JSON.readTree(exact.getResponse().getContentAsString());
        assertThat(result.get("deltaTotalMinor").asLong()).isZero();
        assertThat(result.get("totalMinor").asLong()).isEqualTo(180_000L);
        assertThat(jdbc.sql("SELECT actual_weight_grams FROM ordering.order_lines WHERE order_id = :id")
                        .param("id", orderId)
                        .query(Integer.class)
                        .single())
                .as("the line is reconciled, which is what lets the order leave the pass")
                .isEqualTo(1_200);
    }

    @Test
    @DisplayName("a weight that would move a provider-paid total is refused, and nothing is written")
    void aProviderPaidOrderRefusesAWeightThatMovesItsTotal() throws Exception {
        UUID orderId = placeOrder(Map.of("cake", cakeVariant), "cake-5");
        advanceToReady(orderId);
        jdbc.sql("UPDATE ordering.orders SET payment_status_projection = 'CAPTURED' WHERE id = :id")
                .param("id", orderId)
                .update();
        UUID cakeLine = lineIdOf(orderId, cakeVariant);

        MvcResult refused = weigh(orderId, cakeLine, 1_340, version(orderId));

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(JSON.readTree(refused.getResponse().getContentAsString())
                        .get("reason")
                        .asText())
                .isEqualTo("PAYMENT_ALREADY_TAKEN");
        assertThat(revisionCount(orderId)).as("no revision was appended").isEqualTo(1);
        assertThat(jdbc.sql("SELECT actual_weight_grams FROM ordering.order_lines WHERE id = :id")
                        .param("id", cakeLine)
                        .query(Integer.class)
                        .optional())
                .as("the weight was not stored")
                .isEmpty();
        assertThat(orderDetail(orderId).get("summary").get("totalMinor").asLong())
                .isEqualTo(180_000L);
    }

    @Test
    @DisplayName("a menu repriced after checkout cannot ride in on a weight capture")
    void aRepricedMenuIsNotAppliedByTheScale() throws Exception {
        UUID orderId = placeOrder(Map.of("cake", cakeVariant, "soda", sodaVariant), "cake-6");
        advanceToReady(orderId);
        UUID cakeLine = lineIdOf(orderId, cakeVariant);

        // The cake went up to 16,000 per 100 g while it was in the oven.
        jdbc.sql("UPDATE pricing.prices SET amount_minor = 16000 WHERE priceable_id = :id")
                .param("id", cakeVariant)
                .update();
        MvcResult cakeRefused = weigh(orderId, cakeLine, 1_340, version(orderId));
        assertThat(cakeRefused.getResponse().getStatus()).isEqualTo(422);
        assertThat(JSON.readTree(cakeRefused.getResponse().getContentAsString())
                        .get("reason")
                        .asText())
                .isEqualTo("CATCHWEIGHT_PRICE_CHANGED");

        // And the soda, which nobody is weighing, went up too: weighing the cake must not reprice it.
        jdbc.sql("UPDATE pricing.prices SET amount_minor = 15000 WHERE priceable_id = :id")
                .param("id", cakeVariant)
                .update();
        jdbc.sql("UPDATE pricing.prices SET amount_minor = 6000 WHERE priceable_id = :id")
                .param("id", sodaVariant)
                .update();
        MvcResult sodaRefused = weigh(orderId, cakeLine, 1_340, version(orderId));
        assertThat(sodaRefused.getResponse().getStatus()).isEqualTo(422);
        assertThat(JSON.readTree(sodaRefused.getResponse().getContentAsString())
                        .get("reason")
                        .asText())
                .isEqualTo("ORDER_REPRICE_DRIFT");

        assertThat(revisionCount(orderId)).isEqualTo(1);
        jdbc.sql("UPDATE pricing.prices SET amount_minor = 5000 WHERE priceable_id = :id")
                .param("id", sodaVariant)
                .update();
        assertThat(weigh(orderId, cakeLine, 1_340, version(orderId))
                        .getResponse()
                        .getStatus())
                .as("with the prices back, the same weight is applied")
                .isEqualTo(200);
    }

    @Test
    @DisplayName(
            "weighing one cake of two leaves the other priced at its nominal weight, and still blocks the handover")
    void oneOfTwoWeighedLines() throws Exception {
        UUID cart = openCart();
        putLine(cart, "cake-a", cakeVariant, "1");
        putLine(cart, "cake-b", cakeVariant, "1");
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkoutCart(cart, "two-cakes");
        advanceToReady(orderId);
        List<UUID> lines = jdbc.sql("SELECT id FROM ordering.order_lines WHERE order_id = :id ORDER BY line_number")
                .param("id", orderId)
                .query(UUID.class)
                .list();

        assertThat(weigh(orderId, lines.get(0), 1_000, version(orderId))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        // 150,000 for the weighed one, 180,000 still provisional for the other.
        assertThat(orderDetail(orderId).get("summary").get("totalMinor").asLong())
                .isEqualTo(330_000L);
        MvcResult blocked = stateAction(orderId, "COMPLETED", version(orderId));
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat(JSON.readTree(blocked.getResponse().getContentAsString()).get("orderLineIds"))
                .extracting(JsonNode::asText)
                .containsExactly(lines.get(1).toString());

        assertThat(weigh(orderId, lines.get(1), 1_500, version(orderId))
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(orderDetail(orderId).get("summary").get("totalMinor").asLong())
                .as("150,000 + 225,000")
                .isEqualTo(375_000L);
    }

    @Test
    @DisplayName(
            "a weight is refused for a line that is not sold by weight, for an unknown line, and for a stale order")
    void theWeighingEndpointRefusesWhatItCannotApply() throws Exception {
        UUID orderId = placeOrder(Map.of("cake", cakeVariant, "soda", sodaVariant), "cake-7");
        advanceToReady(orderId);

        MvcResult fixedPrice = weigh(orderId, lineIdOf(orderId, sodaVariant), 330, version(orderId));
        assertThat(fixedPrice.getResponse().getStatus()).isEqualTo(422);
        assertThat(JSON.readTree(fixedPrice.getResponse().getContentAsString())
                        .get("reason")
                        .asText())
                .isEqualTo("LINE_NOT_CATCHWEIGHT");

        assertThat(weigh(orderId, UUID.randomUUID(), 1_000, version(orderId))
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);

        UUID cakeLine = lineIdOf(orderId, cakeVariant);
        MvcResult stale = weigh(orderId, cakeLine, 1_000, version(orderId) - 1);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");

        MvcResult zero = mvc.perform(put(linePath(orderId, cakeLine))
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "weigh-zero")
                        .header("If-Match", "\"" + version(orderId) + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualWeightGrams\":0}"))
                .andReturn();
        assertThat(zero.getResponse().getStatus()).isEqualTo(400);

        MvcResult noKey = mvc.perform(put(linePath(orderId, cakeLine))
                        .with(tokenFor(OPERATOR))
                        .header("If-Match", "\"" + version(orderId) + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualWeightGrams\":1000}"))
                .andReturn();
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(noKey.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    @DisplayName("another tenant's operator cannot weigh this tenant's order, by path or by guessing its id")
    void anotherTenantCannotWeighTheOrder() throws Exception {
        UUID orderId = placeOrder(Map.of("cake", cakeVariant), "cake-8");
        advanceToReady(orderId);
        UUID cakeLine = lineIdOf(orderId, cakeVariant);

        // Their own tenant's path, my order's id: the order is not theirs.
        MvcResult guessed = mvc.perform(put("/api/v1/tenants/" + OTHER_TENANT + "/brands/" + OTHER_BRAND
                                + "/locations/" + LOCATION + "/orders/" + orderId + "/lines/" + cakeLine
                                + "/actual-weight")
                        .with(tokenFor(OUTSIDER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "weigh-outsider-1")
                        .header("If-Match", "\"" + version(orderId) + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualWeightGrams\":1000}"))
                .andReturn();
        assertThat(guessed.getResponse().getStatus()).isIn(403, 404);

        // My tenant's path with their credentials.
        MvcResult byPath = mvc.perform(put(linePath(orderId, cakeLine))
                        .with(tokenFor(OUTSIDER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "weigh-outsider-2")
                        .header("If-Match", "\"" + version(orderId) + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualWeightGrams\":1000}"))
                .andReturn();
        assertThat(byPath.getResponse().getStatus()).isEqualTo(403);

        assertThat(revisionCount(orderId)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT actual_weight_grams FROM ordering.order_lines WHERE id = :id")
                        .param("id", cakeLine)
                        .query(Integer.class)
                        .optional())
                .isEmpty();
    }

    // ------------------------------------- the context a weight is captured in

    @Test
    @DisplayName("a weight capture re-prices under the promotion inputs the order was placed with: "
            + "a first-order discount survives the customer's next order")
    void aWeightCaptureKeepsTheFirstOrderPromotion() throws Exception {
        activate(welcomeDiscount());
        UUID orderId = placeOrder(Map.of("cake", cakeVariant), "welcome-1");
        assertThat(totalOf(orderId)).as("10% off 180,000").isEqualTo(162_000L);
        // The customer orders again while the cake is in the oven. By the clock they are no longer a
        // first-time customer, and a re-price that asked the clock would take the welcome discount back.
        placeOrder(Map.of("soda", sodaVariant), "welcome-2");
        advanceToReady(orderId);

        MvcResult weighed = weigh(orderId, lineIdOf(orderId, cakeVariant), 1_340, version(orderId));

        assertThat(weighed.getResponse().getStatus())
                .as(weighed.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode result = JSON.readTree(weighed.getResponse().getContentAsString());
        assertThat(result.get("totalMinor").asLong())
                .as("10% off 201,000: the discount stays, and follows the weight")
                .isEqualTo(180_900L);
        assertThat(result.get("deltaTotalMinor").asLong()).isEqualTo(18_900L);
        assertThat(cashDue(orderId))
                .as("the courier collects the weighed, discounted amount")
                .isEqualTo(180_900L);
    }

    @Test
    @DisplayName("a hidden option the server applied is neither charged a second time nor refused when a line is "
            + "weighed, on the weighed line or on any other")
    void aHiddenOptionIsAppliedOnceWhenALineIsWeighed() throws Exception {
        attachHiddenPackaging(2_000L, cakeVariant, sodaVariant);
        UUID orderId = placeOrder(Map.of("cake", cakeVariant, "soda", sodaVariant), "boxed");
        assertThat(totalOf(orderId))
                .as("180,000 and 5,000, and a 2,000 box on each")
                .isEqualTo(189_000L);
        advanceToReady(orderId);

        MvcResult weighed = weigh(orderId, lineIdOf(orderId, cakeVariant), 1_340, version(orderId));

        assertThat(weighed.getResponse().getStatus())
                .as(weighed.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode result = JSON.readTree(weighed.getResponse().getContentAsString());
        assertThat(result.get("lineFinalAmountMinor").asLong())
                .as("201,000 and the one box")
                .isEqualTo(203_000L);
        assertThat(result.get("totalMinor").asLong())
                .as("203,000 + 5,000 + 2,000")
                .isEqualTo(210_000L);
        assertThat(jdbc.sql("""
                                SELECT count(*) FROM ordering.order_line_modifiers m
                                JOIN ordering.order_lines l ON l.id = m.order_line_id
                                WHERE l.order_id = :id AND l.revision_to IS NULL AND m.auto_selected
                                """).param("id", orderId).query(Integer.class).single())
                .as("still one hidden row per line, written by the server")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an order that also holds a combo can be weighed, and the combo keeps its combo price")
    void anOrderWithAComboCanBeWeighed() throws Exception {
        Lunch lunch = seedLunchCombo(25_000L);
        UUID cart = openCart();
        putLine(cart, "cake", cakeVariant, "1");
        tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                "lunch",
                lunch.variantId(),
                1,
                List.of(),
                null,
                List.of(new CartService.ComboPick(lunch.burger().id(), 1)),
                List.of(),
                null));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkoutCart(cart, "cake-and-lunch");
        assertThat(totalOf(orderId)).as("180,000 and the lunch box at 25,000").isEqualTo(205_000L);
        advanceToReady(orderId);

        MvcResult weighed = weigh(orderId, lineIdOf(orderId, cakeVariant), 1_340, version(orderId));

        assertThat(weighed.getResponse().getStatus())
                .as(weighed.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(totalOf(orderId)).as("201,000 and the same 25,000").isEqualTo(226_000L);
        assertThat(jdbc.sql("""
                                SELECT final_amount_minor FROM ordering.order_lines
                                WHERE order_id = :id AND revision_to IS NULL AND combo_selection_id IS NOT NULL
                                """).param("id", orderId).query(Long.class).single())
                .as("the combo component is untouched by the scale")
                .isEqualTo(25_000L);
    }

    // ---------------------------------------------------------------- helpers

    private UUID placeOrder(Map<String, UUID> lines, String idempotencyKey) {
        UUID cart = openCart();
        lines.forEach((key, variant) -> putLine(cart, key, variant, "1"));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        return checkoutCart(cart, idempotencyKey);
    }

    private UUID openCart() {
        return tx(() -> carts.create(TENANT, BRAND, LOCATION, "STOREFRONT", FulfillmentMode.PICKUP, CUSTOMER, null))
                .cartId();
    }

    private void putLine(UUID cartId, String lineKey, UUID variantId, String quantity) {
        tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                cartId,
                cartVersion(cartId),
                lineKey,
                variantId,
                new BigDecimal(quantity),
                List.of(),
                List.of(),
                null));
    }

    private CartService.CartRefusedException refusal(UUID cartId, UUID variantId, String quantity) {
        Throwable thrown = catchThrowable(() -> putLine(cartId, "probe", variantId, quantity));
        assertThat(thrown).isInstanceOf(CartService.CartRefusedException.class);
        return (CartService.CartRefusedException) thrown;
    }

    private BigDecimal lineQuantity(UUID cartId, String lineKey) {
        return jdbc.sql("SELECT quantity FROM ordering.cart_lines WHERE cart_id = :id AND line_key = :key")
                .param("id", cartId)
                .param("key", lineKey)
                .query(BigDecimal.class)
                .single();
    }

    private UUID checkoutCart(UUID cart, String idempotencyKey) {
        var row = cartStore.find(TENANT, BRAND, cart).orElseThrow();
        var result = tx(() -> checkout.checkout(new CheckoutService.CheckoutCommand(
                TENANT,
                BRAND,
                cart,
                row.version(),
                Objects.requireNonNull(row.pricingQuoteId(), "the cart was priced first"),
                Objects.requireNonNull(row.pricingContextHash(), "the cart was priced first"),
                idempotencyKey,
                "CASH",
                0L,
                "CUSTOMER",
                CUSTOMER.toString(),
                null,
                null,
                false)));
        return Objects.requireNonNull(result.orderId(), "a created checkout always has an order id");
    }

    /** CASH orders confirm themselves under the default policy; this makes the premise explicit. */
    private void confirmed(UUID orderId) {
        assertThat(statusOf(orderId)).isIn("CONFIRMED", "PREPARING");
    }

    private void advanceToReady(UUID orderId) throws Exception {
        confirmed(orderId);
        advance(orderId, "PREPARING");
        advance(orderId, "READY");
    }

    private void advance(UUID orderId, String target) throws Exception {
        MvcResult moved = stateAction(orderId, target, version(orderId));
        assertThat(moved.getResponse().getStatus())
                .as("-> " + target + ": " + moved.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private MvcResult stateAction(UUID orderId, String target, int expectedVersion) throws Exception {
        return mvc.perform(post(orderPath(orderId) + "/state-actions")
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "state-" + UUID.randomUUID())
                        .header("If-Match", "\"" + expectedVersion + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"" + target + "\",\"reasonCode\":\"KITCHEN\"}"))
                .andReturn();
    }

    private MvcResult weigh(UUID orderId, UUID lineId, int grams, int expectedVersion) throws Exception {
        return mvc.perform(put(linePath(orderId, lineId))
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "weigh-" + UUID.randomUUID())
                        .header("If-Match", "\"" + expectedVersion + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualWeightGrams\":" + grams + "}"))
                .andReturn();
    }

    private static long money(Map<String, Object> row, String column) {
        return ((Number) Objects.requireNonNull(row.get(column))).longValue();
    }

    private int version(UUID orderId) throws Exception {
        return orderDetail(orderId).get("summary").get("version").asInt();
    }

    private String statusOf(UUID orderId) {
        return jdbc.sql("SELECT status FROM ordering.orders WHERE id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }

    private int revisionCount(UUID orderId) {
        return jdbc.sql("SELECT count(*) FROM ordering.order_revisions WHERE order_id = :id")
                .param("id", orderId)
                .query(Integer.class)
                .single();
    }

    private long cashDue(UUID orderId) {
        return jdbc.sql("SELECT total_due_minor FROM payments.order_settlements WHERE order_id = :id")
                .param("id", orderId)
                .query(Long.class)
                .single();
    }

    private UUID lineIdOf(UUID orderId, UUID variantId) {
        return jdbc.sql("SELECT id FROM ordering.order_lines WHERE order_id = :id AND source_variant_id = :variant "
                        + "AND revision_to IS NULL ORDER BY line_number LIMIT 1")
                .param("id", orderId)
                .param("variant", variantId)
                .query(UUID.class)
                .single();
    }

    private int cartVersion(UUID cartId) {
        return cartStore.find(TENANT, BRAND, cartId).orElseThrow().version();
    }

    private String orderPath(UUID orderId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/" + orderId;
    }

    private String linePath(UUID orderId, UUID lineId) {
        return orderPath(orderId) + "/lines/" + lineId + "/actual-weight";
    }

    private JsonNode orderDetail(UUID orderId) throws Exception {
        MvcResult result =
                mvc.perform(get(orderPath(orderId)).with(tokenFor(OPERATOR))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private long totalOf(UUID orderId) throws Exception {
        return orderDetail(orderId).get("summary").get("totalMinor").asLong();
    }

    /** 10% off the goods, for a customer's first order at the brand. */
    private static PromotionDefinition welcomeDiscount() {
        return PromotionDbFixture.definition(
                "WELCOME",
                Promotion.Scope.ORDER,
                "welcome",
                List.of(PromotionDbFixture.condition(1, Promotion.Condition.Type.FIRST_ORDER)),
                List.of(PromotionDbFixture.action(
                        1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L)));
    }

    /** Authors, validates and activates a promotion through the production service, as a marketer would. */
    private void activate(PromotionDefinition definition) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(
                        Jwt.withTokenValue("promotion-author")
                                .header("alg", "none")
                                .subject(ACTOR_SUBJECT)
                                .build(),
                        List.of()));
        try {
            var drafted = promotionAuthoring.create(TENANT, BRAND, definition);
            var validated = promotionAuthoring.validate(TENANT, BRAND, drafted.id(), drafted.version());
            assertThat(validated.report().isValid())
                    .as(String.valueOf(validated.report().refusals()))
                    .isTrue();
            var activated = promotionAuthoring.activate(
                    TENANT, BRAND, drafted.id(), validated.promotion().version(), "catchweight test");
            assertThat(activated.isPending())
                    .as("a promotion this small needs no second person")
                    .isFalse();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    /**
     * A required packaging group the server adds by itself to each of these variants' products on a
     * pickup order -- hidden, so the customer never chooses it -- priced at {@code boxMinor}, and the
     * menu published again with it.
     */
    private void attachHiddenPackaging(long boxMinor, UUID... variants) {
        UUID group = catalogAuthoring.createModifierGroup(TENANT, BRAND, "PACK", "Packaging", "uz", true, 1, 1, false);
        UUID box = catalogAuthoring.addModifierOption(
                TENANT, BRAND, group, "BOX", "Gift box", "uz", null, 1, 0, FiscalClassification.unclassified(), null);
        for (UUID variant : variants) {
            UUID product = jdbc.sql("SELECT product_id FROM catalog.variants WHERE id = :id")
                    .param("id", variant)
                    .query(UUID.class)
                    .single();
            catalogAuthoring.attachModifierGroup(TENANT, BRAND, product, group, 0);
            composites.setAttachmentPolicy(
                    TENANT,
                    BRAND,
                    AttachmentOwnerType.PRODUCT,
                    product,
                    group,
                    1,
                    new AttachmentPolicy(
                            Visibility.HIDDEN_AUTO_SELECT, Set.of(FulfillmentMode.PICKUP), null, null, null),
                    ACTOR_SUBJECT);
        }
        priceOf("MODIFIER_OPTION", box, boxMinor);
        publishAgain();
    }

    private record Lunch(UUID variantId, ComboComponent burger) {}

    /** A lunch box that is a burger, sold at {@code burgerInLunchMinor} inside it, on the published menu. */
    private Lunch seedLunchCombo(long burgerInLunchMinor) {
        var unclassified = FiscalClassification.unclassified();
        var lunch = catalogAuthoring.createProduct(
                TENANT, BRAND, catalogId, "LUNCH", "Lunch box", null, "uz", "SKU-LUNCH", "PIECE", unclassified, null);
        var burger = catalogAuthoring.createProduct(
                TENANT, BRAND, catalogId, "BURGER", "Burger", null, "uz", "SKU-BURGER", "PIECE", unclassified, null);
        UUID lunchVariant = lunch.defaultVariantId();
        var main = composites.createComboGroup(
                new NewComboGroup(TENANT, BRAND, lunchVariant, "MAIN", "Choose a main", "uz", 1, 1, false, 0),
                ACTOR_SUBJECT);
        ComboComponent burgerInLunch =
                composites.addComponent(TENANT, BRAND, main.id(), burger.defaultVariantId(), 1, 0, ACTOR_SUBJECT);
        priceOf("VARIANT", burger.defaultVariantId(), 30_000L);
        priceOf("COMBO_COMPONENT", burgerInLunch.id(), burgerInLunchMinor);
        for (UUID variant : List.of(lunchVariant, burger.defaultVariantId())) {
            catalogAuthoring.setOffering(
                    TENANT, BRAND, LOCATION, variant, OfferingStatus.AVAILABLE, List.of("PICKUP", "DELIVERY"));
        }
        publishAgain();
        return new Lunch(lunchVariant, burgerInLunch);
    }

    private void publishAgain() {
        var published = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        assertThat(published.status())
                .as(String.valueOf(published.report().blockers()))
                .isEqualTo(PublicationStatus.PUBLISHED);
    }

    private void priceOf(String type, UUID priceableId, long amountMinor) {
        jdbc.sql("""
                INSERT INTO pricing.prices (id, tenant_id, brand_id, price_book_id, priceable_type,
                    priceable_id, amount_minor, valid_from)
                VALUES (:id, :tenantId, :brandId, :priceBookId, :type, :priceableId, :amount, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("priceBookId", priceBook)
                .param("type", type)
                .param("priceableId", priceableId)
                .param("amount", amountMinor)
                .param(
                        "from",
                        java.time.OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
    }

    private <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private void tx(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'catchweight endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", role.scopeType().name())
                .param("scopeId", tenantId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    // ------------------------------------------------------------------ seeds

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'catchweight-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'catchweight-http-other', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """).param("id", OTHER_TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'other-main', 'Brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", OTHER_TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Branch', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :tenantId, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", CUSTOMER).param("tenantId", TENANT).update();

        UUID storefrontChannel = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE', false)
                """).param("id", storefrontChannel).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                VALUES (:tenantId, :channelId, :locationId, 'ACTIVE')
                """)
                .param("tenantId", TENANT)
                .param("channelId", storefrontChannel)
                .param("locationId", LOCATION)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.location_service_state (location_id, tenant_id, brand_id, mode)
                VALUES (:locationId, :tenantId, :brandId, 'FOLLOW_SCHEDULE')
                """)
                .param("locationId", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        for (FulfillmentMode mode : List.of(FulfillmentMode.PICKUP, FulfillmentMode.DELIVERY)) {
            jdbc.sql("""
                    INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id,
                        fulfillment_mode, enabled)
                    VALUES (:tenantId, :channelId, :mode, true)
                    """)
                    .param("tenantId", TENANT)
                    .param("channelId", storefrontChannel)
                    .param("mode", mode.name())
                    .update();
        }
        jdbc.sql("""
                INSERT INTO payments.payment_methods (id, tenant_id, code, display_name, responsibility, status)
                VALUES (:id, :tenantId, 'CASH', 'CASH', 'OPERATOR', 'ACTIVE')
                ON CONFLICT ON CONSTRAINT uq_payment_method_code DO NOTHING
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.channel_payment_methods (tenant_id, channel_id, payment_method_code, enabled)
                VALUES (:tenantId, :channelId, 'CASH', true)
                ON CONFLICT DO NOTHING
                """)
                .param("tenantId", TENANT)
                .param("channelId", storefrontChannel)
                .update();

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
                    VALUES (:scheduleId, :sequence, :day, :opens, :closes)
                    """)
                    .param("scheduleId", scheduleId)
                    .param("sequence", day)
                    .param("day", day)
                    .param("opens", LocalTime.of(0, 0))
                    .param("closes", LocalTime.of(23, 59))
                    .update();
        }
        for (FulfillmentMode mode : List.of(FulfillmentMode.PICKUP, FulfillmentMode.DELIVERY)) {
            jdbc.sql("""
                    INSERT INTO tenant.location_service_bindings (tenant_id, brand_id, location_id,
                        fulfillment_mode, schedule_id)
                    VALUES (:tenantId, :brandId, :locationId, :mode, :scheduleId)
                    """)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("locationId", LOCATION)
                    .param("mode", mode.name())
                    .param("scheduleId", scheduleId)
                    .update();
        }
    }

    /**
     * A plov ordered by the half portion, a cake priced per 100 g, and a can of soda -- authored the
     * way the product editor does it, then published and priced.
     */
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
        plovVariant = seedProduct(catalogId, "PLOV", "Osh");
        cakeVariant = seedProduct(catalogId, "CAKE", "Tort");
        sodaVariant = seedProduct(catalogId, "SODA", "Cola");

        physical.replace(
                TENANT,
                BRAND,
                plovVariant,
                new PhysicalAttributes(
                        350, null, false, null, null, true, new BigDecimal("0.5"), null, null, null, null),
                0,
                ACTOR_SUBJECT);
        physical.replace(
                TENANT,
                BRAND,
                cakeVariant,
                new PhysicalAttributes(1_500, null, true, 100, 1_200, false, null, null, null, null, null),
                0,
                ACTOR_SUBJECT);

        priceBook = UUID.randomUUID();
        var validFrom = java.time.OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC);
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
        price(priceBook, plovVariant, 40_000L, validFrom);
        price(priceBook, cakeVariant, 15_000L, validFrom);
        price(priceBook, sodaVariant, 5_000L, validFrom);
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

        for (UUID variant : List.of(plovVariant, cakeVariant, sodaVariant)) {
            catalogAuthoring.setOffering(
                    TENANT, BRAND, LOCATION, variant, OfferingStatus.AVAILABLE, List.of("PICKUP", "DELIVERY"));
        }

        var published = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        assertThat(published.status())
                .as(String.valueOf(published.report().blockers()))
                .isEqualTo(PublicationStatus.PUBLISHED);
    }

    private UUID seedProduct(UUID catalogId, String code, String name) {
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

    private void price(UUID priceBook, UUID variant, long amount, java.time.OffsetDateTime validFrom) {
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

    private void seedFallbackStation() {
        jdbc.sql("""
                INSERT INTO kitchen.stations (id, tenant_id, brand_id, location_id, code, role,
                    display_name_ru, display_name_uz, display_name_en, is_fallback, status)
                VALUES (:id, :tenantId, :brandId, :locationId, 'MAIN', 'HOT', 'Main', 'Main', 'Main',
                    true, 'ACTIVE')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .update();
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
