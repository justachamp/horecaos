package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.dinein.application.FloorPlanService;
import uz.horecaos.platform.dinein.application.TableSessionService;
import uz.horecaos.platform.dinein.domain.SessionStatus;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionRow;
import uz.horecaos.platform.fulfillment.application.DeliveryTariffService;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService;
import uz.horecaos.platform.fulfillment.domain.VersionStatus;
import uz.horecaos.platform.fulfillment.domain.tariff.DeliveryTariff;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceMode;
import uz.horecaos.platform.fulfillment.domain.tariff.FeeSource;
import uz.horecaos.platform.fulfillment.domain.tariff.TariffBand;
import uz.horecaos.platform.fulfillment.domain.zone.ZoneRole;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The New order screen's three server-side promises, driven over HTTP through the real wiring
 * (gap map rows {@code 1.3e} and {@code 1.3}): the price the operator sees before «Создать»
 * is the price the order is booked at, the cash the customer says they will hand over is on
 * the order from its first read, and an order keyed in for a seated party is on that party's
 * bill and shows its table on the board at once.
 *
 * <p>Everything is the production class reading the production tables. The menu is authored
 * through the authoring service and published through the publication service, the party is
 * seated through {@link TableSessionService}, and the order is placed by {@code POST
 * .../orders} -- so the attach to the bill is the real {@code TableBindingPortAdapter}, not a
 * recording port, and it is proved together with the placement and not beside it.
 *
 * <p>What would still pass if the quote were a second pricing path that happened to agree on
 * a bare burger: the first case. What would not: the promo code and the delivery fee, which
 * are both in the figure the order is then booked at, and the footprint check, which fails if
 * the quote leaves a cart, a stored quote or an audit fact behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class OperatorOrderEntryHttpTests {

    private static final UUID TENANT = UUID.fromString("018fe100-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fe100-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fe100-4000-7000-8000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fe100-4000-7000-8000-0000000000c2");
    private static final UUID CUSTOMER = UUID.fromString("018fe100-4000-7000-8000-0000000000d1");
    private static final UUID ACTOR = UUID.fromString("018fe100-4000-7000-8000-0000000000e1");

    private static final String LOCALE = "uz";
    private static final String MANAGER = "order-entry-http-manager";
    private static final String OTHER_BRANCH_MANAGER = "order-entry-http-other-branch";
    private static final String UNGRANTED = "order-entry-http-ungranted";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** One burger, tax inclusive at 12%. */
    private static final long BURGER = 50_000L;

    /** The flat charge the delivery zone's only band levies. */
    private static final long DELIVERY_FEE = 8_000L;

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the order entry HTTP test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // Realtime signals go to a broker that is not there; the default waits ten seconds per
        // signal for it, and a checkout emits several.
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "300");
        // A delivery order's address is envelope-encrypted (ADR 0029).
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
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
    private CatalogAuthoringService authoring;

    @Autowired
    @SuppressWarnings("NullAway")
    private CatalogPublicationService publication;

    @Autowired
    @SuppressWarnings("NullAway")
    private InventoryService inventory;

    @Autowired
    @SuppressWarnings("NullAway")
    private FloorPlanService floorPlan;

    @Autowired
    @SuppressWarnings("NullAway")
    private TableSessionService sessions;

    @Autowired
    @SuppressWarnings("NullAway")
    private PromoCodeAuthoringService promoCodes;

    @Autowired
    @SuppressWarnings("NullAway")
    private FieldProtection protection;

    @Autowired
    @SuppressWarnings("NullAway")
    private tools.jackson.databind.ObjectMapper objectMapper;

    private UUID catalogId;
    private UUID burgerVariant;
    private UUID priceBook;
    private UUID addressId;
    private UUID hallSection;
    private UUID otherHallSection;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("""
                TRUNCATE TABLE dinein.session_orders, dinein.session_tables, dinein.table_sessions,
                    dinein.reservation_tables, dinein.reservations, dinein.qr_guest_sessions,
                    dinein.tables, dinein.sections, dinein.location_settings CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE ordering.order_amendment_commands, ordering.order_amendments,
                    ordering.order_outcomes, ordering.order_outcome_reason_texts,
                    ordering.order_outcome_reasons, ordering.order_revisions,
                    ordering.order_process_states, ordering.order_timers,
                    ordering.approval_decisions, ordering.order_state_history,
                    ordering.order_customer_snapshots, ordering.order_adjustments,
                    ordering.order_line_comment_presets, ordering.order_line_modifiers,
                    ordering.order_lines, ordering.bulk_operation_items, ordering.bulk_operations,
                    ordering.orders, ordering.order_number_counters, ordering.checkout_attempts,
                    ordering.cart_lines, ordering.carts CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE kitchen.tickets CASCADE").update();
        jdbc.sql("""
                TRUNCATE TABLE pricing.quote_adjustments, pricing.quote_lines, pricing.quotes,
                    pricing.prices, pricing.price_book_assignments, pricing.price_books,
                    pricing.tax_profiles, pricing.coupon_codes, pricing.promotion_actions,
                    pricing.promotion_conditions, pricing.promotions CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE inventory.reservation_lines, inventory.reservations,
                    inventory.movements, inventory.positions, inventory.stock_items CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE catalog.publication_items, catalog.publications,
                    catalog.location_offerings, catalog.item_sale_windows, catalog.media_relations,
                    catalog.translations, catalog.product_modifier_groups, catalog.variant_modifier_groups,
                    catalog.category_products, catalog.catalog_products, catalog.modifier_options,
                    catalog.modifier_groups, catalog.categories, catalog.fiscal_classifications,
                    catalog.fees, catalog.variants, catalog.products, catalog.catalogs CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE fulfillment.delivery_sourcing_jobs, fulfillment.delivery_plans CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        roleRegistry.synchronize();

        seedTenancy();
        seedDeliveryZone();
        seedMenu();
        seedPricing();
        publishTheMenu();
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, burgerVariant, TrackingMode.BINARY);
        grant(MANAGER, LOCATION);
        grant(OTHER_BRANCH_MANAGER, OTHER_LOCATION);
    }

    // ============================================================ row 1.3e: the server's price

    @Test
    @DisplayName("the price shown before Создать is the price the order is booked at, promo code and all")
    void theQuotedPriceIsTheBookedPrice() throws Exception {
        activatePromoCode("OPERATOR10", 1_000);
        List<Long> before = footprint();

        JsonNode quote = quote(pickupBody("OPERATOR10", null), MANAGER);

        assertThat(footprint())
                .as("a quote leaves no cart, no stored quote, no audit fact and no event behind")
                .isEqualTo(before);
        assertThat(quote.path("currency").asText()).isEqualTo("UZS");
        assertThat(quote.path("discountMinor").asLong())
                .as("10% of the 50,000 line")
                .isEqualTo(5_000L);
        assertThat(quote.path("totalMinor").asLong()).isEqualTo(BURGER - 5_000L);
        assertThat(quote.path("feeMinor").asLong()).isZero();
        assertThat(reconciles(quote))
                .as("total = subtotal + tax + fee - discount, the way the order's own constraint reads it")
                .isTrue();
        assertThat(quote.path("deliveryOutcome").isNull())
                .as("a pickup is not priced as a delivery")
                .isTrue();
        assertThat(quote.path("lines")).hasSize(1);
        assertThat(quote.path("lines").get(0).path("index").asInt()).isZero();
        assertThat(quote.path("lines").get(0).path("baseAmountMinor").asLong()).isEqualTo(BURGER);
        assertThat(quote.path("discounts")).hasSize(1);
        assertThat(quote.path("discounts").get(0).path("amountMinor").asLong()).isEqualTo(5_000L);

        UUID orderId = place(pickupBody("OPERATOR10", null), MANAGER, "booked-1");
        JsonNode booked = detail(orderId);

        assertThat(booked.path("summary").path("totalMinor").asLong())
                .isEqualTo(quote.path("totalMinor").asLong());
        assertThat(booked.path("subtotalMinor").asLong())
                .isEqualTo(quote.path("subtotalMinor").asLong());
        assertThat(booked.path("taxMinor").asLong())
                .isEqualTo(quote.path("taxMinor").asLong());
        assertThat(booked.path("summary").path("discountMinor").asLong())
                .isEqualTo(quote.path("discountMinor").asLong());
    }

    @Test
    @DisplayName("a delivery quote carries the delivery fee the order is then booked with")
    void aDeliveryQuoteCarriesTheFee() throws Exception {
        JsonNode quote = quote(deliveryBody(), MANAGER);

        assertThat(quote.path("feeMinor").asLong()).isEqualTo(DELIVERY_FEE);
        assertThat(quote.path("totalMinor").asLong())
                .as("the goods and the fee, which the menu arithmetic on the screen knows nothing about")
                .isEqualTo(BURGER + DELIVERY_FEE);
        assertThat(quote.path("deliveryOutcome").asText()).isIn("RESOLVED", "EXTERNALLY_PRICED");
        assertThat(reconciles(quote)).isTrue();

        UUID orderId = place(deliveryBody(), MANAGER, "booked-delivery");

        assertThat(detail(orderId).path("summary").path("totalMinor").asLong())
                .isEqualTo(quote.path("totalMinor").asLong());
    }

    @Test
    @DisplayName("a quote is refused for what the order would be refused for, and still keeps nothing")
    void aQuoteIsRefusedLikeTheOrder() throws Exception {
        List<Long> before = footprint();

        MvcResult unknownCode = quoteResult(pickupBody("NO-SUCH-CODE", null), MANAGER);
        MvcResult emptyBasket = quoteResult("""
                {"customerAccountId":"%s","channelCode":"STOREFRONT","fulfillmentMode":"PICKUP",
                 "paymentMethodCode":"CASH","lines":[]}""".formatted(CUSTOMER), MANAGER);
        MvcResult tenderOnCard = quoteResult(
                pickupBody(null, 60_000L).replace("\"paymentMethodCode\":\"CASH\"", "\"paymentMethodCode\":\"CLICK\""),
                MANAGER);

        assertThat(unknownCode.getResponse().getStatus())
                .as(unknownCode.getResponse().getContentAsString())
                .isBetween(400, 499);
        assertThat(unknownCode.getResponse().getContentAsString()).contains("CODE_NOT_FOUND");
        assertThat(emptyBasket.getResponse().getStatus()).isEqualTo(400);
        assertThat(tenderOnCard.getResponse().getStatus())
                .as("cash tendered means nothing to an order paid another way")
                .isEqualTo(400);
        assertThat(footprint()).isEqualTo(before);
    }

    @Test
    @DisplayName("a quote needs the right to place an order at the branch it is priced at")
    void aQuoteNeedsTheCapabilityAtTheBranch() throws Exception {
        assertThat(quoteResult(pickupBody(null, null), UNGRANTED).getResponse().getStatus())
                .as("authenticated, no grant at all")
                .isEqualTo(403);
        assertThat(quoteResult(pickupBody(null, null), OTHER_BRANCH_MANAGER)
                        .getResponse()
                        .getStatus())
                .as("a grant at another branch of the same brand does not reach this one")
                .isEqualTo(403);
        assertThat(quoteResult(pickupBody(null, null), MANAGER).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("the cash a customer will hand over is on the order from its first read, with the change due")
    void cashTenderedAtCreationIsOnTheOrder() throws Exception {
        UUID orderId = place(pickupBody(null, 60_000L), MANAGER, "tender-enough");

        JsonNode detail = detail(orderId);
        assertThat(detail.path("cashTenderedExpectedMinor").asLong()).isEqualTo(60_000L);
        assertThat(detail.path("changeDueMinor").asLong()).isEqualTo(60_000L - BURGER);
        assertThat(detail.path("currentRevision").asInt())
                .as("part of creating the order, not a revision of it")
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.order_amendments")
                        .query(Long.class)
                        .single())
                .as("no SET_CASH_TENDERED amendment was needed")
                .isZero();
    }

    @Test
    @DisplayName("a tender short of the total still creates the order, and the answer says so")
    void aShortTenderIsANotice() throws Exception {
        MvcResult placed = placeResult(pickupBody(null, 30_000L), MANAGER, "tender-short");

        assertThat(placed.getResponse().getStatus())
                .as(placed.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode answer = JSON.readTree(placed.getResponse().getContentAsString());
        assertThat(answer.path("warnings").toString()).contains("CASH_TENDERED_INSUFFICIENT");
        assertThat(detail(UUID.fromString(answer.path("orderId").asText()))
                        .path("cashTenderedExpectedMinor")
                        .asLong())
                .as("the customer can hand over more; the figure is kept as said")
                .isEqualTo(30_000L);
    }

    @Test
    @DisplayName("a body that names no tender sends none, and the order carries none")
    void noTenderIsNone() throws Exception {
        // One placement per test: a pickup is proposed to the branch with the lighter live load,
        // so a second order at this branch would be proposed the other one.
        UUID orderId = place(pickupBody(null, null), MANAGER, "tender-none");

        JsonNode detail = detail(orderId);
        assertThat(detail.path("cashTenderedExpectedMinor").isNull()).isTrue();
        assertThat(detail.path("changeDueMinor").isNull()).isTrue();
    }

    // ============================================================ row 1.3: the seated party

    @Test
    @DisplayName("an order keyed in for a seated party is on its bill and shows its table on the board at once")
    void anOrderForASeatedPartyShowsItsTableAtOnce() throws Exception {
        UUID t7 = createTable(LOCATION, hallSection, "T7");
        UUID t8 = createTable(LOCATION, hallSection, "T8");
        SessionRow party = seat(LOCATION, t7, t8);

        UUID orderId = place(dineInBody(party.id()), MANAGER, "dine-in-1");

        JsonNode row = boardRow(orderId);
        assertThat(row.path("table").path("sessionId").asText())
                .as("the first read after Создать already names the party")
                .isEqualTo(party.id().toString());
        assertThat(codesOf(row.path("table"))).containsExactly("T7", "T8");
        assertThat(detail(orderId)
                        .path("summary")
                        .path("table")
                        .path("sessionId")
                        .asText())
                .isEqualTo(party.id().toString());

        JsonNode bill = sessionBill(party.id());
        assertThat(bill.path("orderIds")).extracting(JsonNode::asText).containsExactly(orderId.toString());
        assertThat(bill.path("roundCount").asInt()).isEqualTo(1);
        assertThat(bill.path("totalMinor").asLong()).isEqualTo(BURGER);
    }

    @Test
    @DisplayName("a party that has left refuses the order with SESSION_NOT_LIVE, and nothing is created")
    void aPartyThatLeftRefusesTheOrder() throws Exception {
        UUID t7 = createTable(LOCATION, hallSection, "T7");
        SessionRow party = seat(LOCATION, t7);
        sessions.move(TENANT, party.id(), SessionStatus.CLOSED, party.version(), null, "waiter", "left");
        List<Long> before = footprint();

        MvcResult refused = placeResult(dineInBody(party.id()), MANAGER, "dine-in-gone");

        assertThat(refused.getResponse().getStatus())
                .as(refused.getResponse().getContentAsString())
                .isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("SESSION_NOT_LIVE");
        assertThat(footprint())
                .as("a cooked order on no bill is the failure this refusal exists to prevent")
                .isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.orders")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("a party of another branch answers as if it did not exist, and nothing is created")
    void aPartyOfAnotherBranchIsNotFound() throws Exception {
        UUID t1 = createTable(OTHER_LOCATION, otherHallSection, "X1");
        SessionRow elsewhere = seat(OTHER_LOCATION, t1);
        List<Long> before = footprint();

        MvcResult refused = placeResult(dineInBody(elsewhere.id()), MANAGER, "dine-in-elsewhere");

        assertThat(refused.getResponse().getStatus())
                .as(refused.getResponse().getContentAsString())
                .isEqualTo(404);
        assertThat(footprint()).isEqualTo(before);
        assertThat(sessionBill(elsewhere.id(), OTHER_BRANCH_MANAGER, OTHER_LOCATION)
                        .path("roundCount")
                        .asInt())
                .as("the other branch's bill was not touched")
                .isZero();
    }

    // ================================================================================ helpers

    private JsonNode quote(String body, String subject) throws Exception {
        MvcResult result = quoteResult(body, subject);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult quoteResult(String body, String subject) throws Exception {
        return mvc.perform(post(ordersPath(LOCATION) + "/quote")
                        .with(tokenFor(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "quote-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private UUID place(String body, String subject, String key) throws Exception {
        MvcResult result = placeResult(body, subject, key);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString())
                .path("orderId")
                .asText());
    }

    private MvcResult placeResult(String body, String subject, String key) throws Exception {
        return mvc.perform(post(ordersPath(LOCATION))
                        .with(tokenFor(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private JsonNode detail(UUID orderId) throws Exception {
        MvcResult result = mvc.perform(get(ordersPath(LOCATION) + "/" + orderId).with(tokenFor(MANAGER)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("detail status").isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode boardRow(UUID orderId) throws Exception {
        MvcResult result = mvc.perform(get(ordersPath(LOCATION) + "/board").with(tokenFor(MANAGER)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("board status").isEqualTo(200);
        for (JsonNode row :
                JSON.readTree(result.getResponse().getContentAsString()).path("items")) {
            if (orderId.toString().equals(row.path("orderId").asText())) {
                return row;
            }
        }
        throw new AssertionError("order " + orderId + " is not on the board: "
                + result.getResponse().getContentAsString());
    }

    private JsonNode sessionBill(UUID sessionId) throws Exception {
        return sessionBill(sessionId, MANAGER, LOCATION);
    }

    private JsonNode sessionBill(UUID sessionId, String subject, UUID location) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + location
                                + "/dine-in/sessions/" + sessionId)
                        .with(tokenFor(subject)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> codesOf(JsonNode table) {
        return java.util.stream.StreamSupport.stream(table.path("tables").spliterator(), false)
                .map(node -> node.path("code").asText())
                .toList();
    }

    /** {@code total = subtotal + tax + fee - discount}: the identity {@code ck_order_total_reconciles} holds an order to. */
    private static boolean reconciles(JsonNode quote) {
        return quote.path("totalMinor").asLong()
                == quote.path("subtotalMinor").asLong()
                        + quote.path("taxMinor").asLong()
                        + quote.path("feeMinor").asLong()
                        - quote.path("discountMinor").asLong();
    }

    private static String ordersPath(UUID location) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + location + "/orders";
    }

    /** One burger, a pickup, paid in cash, with the optional fields spelled out the way the console sends them. */
    private String pickupBody(@Nullable String promoCode, @Nullable Long cashTenderedMinor) {
        return body("PICKUP", promoCode, cashTenderedMinor, null, null);
    }

    private String deliveryBody() {
        return body("DELIVERY", null, null, null, """
                {"customerAddressId":"%s","recipientName":"Aziz","recipientPhone":"+998901112233"}""".formatted(addressId));
    }

    private String dineInBody(UUID sessionId) {
        return body("DINE_IN", null, null, sessionId, null);
    }

    private String body(
            String mode,
            @Nullable String promoCode,
            @Nullable Long cashTenderedMinor,
            @Nullable UUID dineInSessionId,
            @Nullable String destination) {
        StringBuilder json = new StringBuilder();
        json.append("{\"customerAccountId\":\"").append(CUSTOMER).append('"');
        json.append(",\"channelCode\":\"STOREFRONT\"");
        json.append(",\"fulfillmentMode\":\"").append(mode).append('"');
        json.append(",\"paymentMethodCode\":\"CASH\"");
        json.append(",\"lines\":[{\"variantId\":\"")
                .append(burgerVariant)
                .append("\",\"quantity\":1,\"modifierOptionIds\":[],\"commentPresetCodes\":[]}]");
        if (promoCode != null) {
            json.append(",\"promoCode\":\"").append(promoCode).append('"');
        }
        if (cashTenderedMinor != null) {
            json.append(",\"cashTenderedMinor\":").append(cashTenderedMinor);
        }
        if (dineInSessionId != null) {
            json.append(",\"dineInSessionId\":\"").append(dineInSessionId).append('"');
        }
        if (destination != null) {
            json.append(",\"destination\":").append(destination);
        }
        return json.append('}').toString();
    }

    /** Row counts of every table a priced cart, a booked order or its audit trail would write to. */
    private List<Long> footprint() {
        return java.util.stream.Stream.of(
                        "ordering.carts",
                        "ordering.cart_lines",
                        "ordering.orders",
                        "ordering.order_lines",
                        "pricing.quotes",
                        "pricing.quote_lines",
                        "pricing.quote_adjustments",
                        "audit.audit_events",
                        "inventory.reservations",
                        "dinein.session_orders")
                .map(table -> jdbc.sql("SELECT count(*) FROM " + table)
                        .query(Long.class)
                        .single())
                .toList();
    }

    private UUID createTable(UUID location, UUID section, String code) {
        return floorPlan
                .createTable(new FloorPlanService.NewTable(
                        TENANT, BRAND, location, section, code, "Table " + code, 4, false, null, null))
                .id();
    }

    private SessionRow seat(UUID location, UUID... tableIds) {
        return sessions.open(
                new TableSessionService.OpenSession(
                        TENANT, BRAND, location, null, List.of(tableIds), 2, "UZS", "waiter"),
                "Walk-in");
    }

    private void activatePromoCode(String code, int basisPoints) {
        var drafted = promoCodes.draft(
                TENANT,
                BRAND,
                new PromoCodeAuthoringService.PromoCodeDraft(
                        "Promo " + code,
                        code,
                        PromoCodeAuthoringService.DiscountShape.PERCENTAGE_OFF_ORDER,
                        basisPoints,
                        null,
                        "UZS",
                        0,
                        List.of(),
                        List.of(),
                        null,
                        100,
                        null,
                        null));
        promoCodes.activate(TENANT, BRAND, drafted.couponId());
    }

    // ================================================================================ fixture

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'order-entry', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        for (UUID location : List.of(LOCATION, OTHER_LOCATION)) {
            String code = location.equals(LOCATION) ? "MAIN01" : "MAIN02";
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version, latitude, longitude, coordinate_source)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, :code, 'Asia/Tashkent',
                        'ACTIVE', 0, 41.311081, 69.240562, 'MERCHANT_PIN')
                    """)
                    .param("id", location)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("code", code)
                    .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                    .update();
        }
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :tenantId, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", CUSTOMER).param("tenantId", TENANT).update();

        UUID channel = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE', false)
                """).param("id", channel).param("tenantId", TENANT).update();
        for (FulfillmentMode mode :
                List.of(FulfillmentMode.PICKUP, FulfillmentMode.DELIVERY, FulfillmentMode.DINE_IN)) {
            jdbc.sql("""
                    INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id, fulfillment_mode, enabled)
                    VALUES (:tenantId, :channelId, :mode, true)
                    """)
                    .param("tenantId", TENANT)
                    .param("channelId", channel)
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
                """).param("tenantId", TENANT).param("channelId", channel).update();

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
                    INSERT INTO tenant.service_schedule_rules (schedule_id, sequence, day_of_week, opens_at, closes_at)
                    VALUES (:scheduleId, :sequence, :day, :opens, :closes)
                    """)
                    .param("scheduleId", scheduleId)
                    .param("sequence", day)
                    .param("day", day)
                    .param("opens", LocalTime.of(0, 0))
                    .param("closes", LocalTime.of(23, 59))
                    .update();
        }
        for (UUID location : List.of(LOCATION, OTHER_LOCATION)) {
            jdbc.sql("""
                    INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                    VALUES (:tenantId, :channelId, :locationId, 'ACTIVE')
                    """)
                    .param("tenantId", TENANT)
                    .param("channelId", channel)
                    .param("locationId", location)
                    .update();
            jdbc.sql("""
                    INSERT INTO tenant.location_service_state (location_id, tenant_id, brand_id, mode)
                    VALUES (:locationId, :tenantId, :brandId, 'FOLLOW_SCHEDULE')
                    """)
                    .param("locationId", location)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .update();
            for (FulfillmentMode mode :
                    List.of(FulfillmentMode.PICKUP, FulfillmentMode.DELIVERY, FulfillmentMode.DINE_IN)) {
                jdbc.sql("""
                        INSERT INTO tenant.location_service_bindings (tenant_id, brand_id, location_id,
                            fulfillment_mode, schedule_id)
                        VALUES (:tenantId, :brandId, :locationId, :mode, :scheduleId)
                        """)
                        .param("tenantId", TENANT)
                        .param("brandId", BRAND)
                        .param("locationId", location)
                        .param("mode", mode.name())
                        .param("scheduleId", scheduleId)
                        .update();
            }
        }

        hallSection = floorPlan
                .createSection(new FloorPlanService.NewSection(TENANT, BRAND, LOCATION, "HALL", "Hall", 0))
                .id();
        otherHallSection = floorPlan
                .createSection(new FloorPlanService.NewSection(TENANT, BRAND, OTHER_LOCATION, "HALL", "Hall", 0))
                .id();
        addressId = insertAddress();
    }

    /** A flat 8,000 delivery fee in a ten kilometre circle around the branch. */
    private void seedDeliveryZone() {
        CurrentActor currentActor = () -> new AuthenticatedActor("order-entry-zone-author", Set.of(), Map.of());
        UUID actor = UUID.randomUUID();
        var zones = new ServiceZoneService(
                new JdbcServiceZoneStore(jdbc),
                JsonMapper.builder().build(),
                Clock.systemUTC(),
                fact -> {},
                currentActor);
        var tariffs = new DeliveryTariffService(
                new JdbcDeliveryTariffStore(jdbc), Clock.systemUTC(), fact -> {}, currentActor);

        UUID tariffId = tariffs.createTariff(TENANT, BRAND, "FLAT", "FLAT", false);
        var drafted = tariffs.draftVersion(
                TENANT,
                BRAND,
                new DeliveryTariff(
                        tariffId,
                        0,
                        VersionStatus.DRAFT,
                        "UZS",
                        FeeSource.TARIFF,
                        DistanceMode.RADIUS,
                        13_000,
                        null,
                        10_000,
                        0L,
                        null,
                        List.of(new TariffBand(0, 0, 10_000, DELIVERY_FEE, 0L)),
                        List.of()),
                actor);
        tariffs.activate(TENANT, BRAND, tariffId, drafted.version(), actor);

        UUID zoneId = zones.createZone(TENANT, BRAND, ZoneRole.DELIVERY, "DEFAULT", "Default", "Default", "Default");
        var version = zones.draftCircleVersion(
                new ServiceZoneService.NewVersion(
                        TENANT, BRAND, zoneId, ZoneRole.DELIVERY, null, 0, "UZS", tariffId, null, null, actor),
                LOCATION,
                10_000);
        zones.activate(TENANT, BRAND, zoneId, version.version(), actor);
        zones.bindLocation(TENANT, BRAND, zoneId, LOCATION);
    }

    private UUID insertAddress() {
        UUID id = UUID.randomUUID();
        String document = objectMapper.writeValueAsString(Map.of(
                "line1", "Amir Temur 12",
                "city", "Tashkent",
                "district", "Yunusobod",
                "entrance", "2",
                "floor", "5",
                "apartment", "41",
                "landmark", "blue gate"));
        String fields = protection
                .protect(
                        TENANT,
                        DataClass.PERSONAL,
                        new FieldProtection.RecordRef("customer.addresses", "encrypted_fields", id),
                        document)
                .serialize();
        String instructions = protection
                .protect(
                        TENANT,
                        DataClass.PERSONAL,
                        new FieldProtection.RecordRef("customer.addresses", "delivery_instructions_encrypted", id),
                        "Ring the top bell")
                .serialize();
        jdbc.sql("""
                INSERT INTO customer.addresses (id, tenant_id, customer_account_id, label,
                    encrypted_fields, delivery_instructions_encrypted, latitude, longitude,
                    coordinate_source, status, version)
                VALUES (:id, :tenantId, :accountId, 'Home', :fields, :instructions,
                    41.311081, 69.240562, 'CUSTOMER_PIN', 'ACTIVE', 1)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("accountId", CUSTOMER)
                .param("fields", fields)
                .param("instructions", instructions)
                .update();
        return id;
    }

    private void seedMenu() {
        catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "BURGER",
                "Burger",
                null,
                LOCALE,
                "SKU-BURGER",
                "PIECE",
                FiscalClassification.unclassified(),
                ACTOR);
        burgerVariant = burger.defaultVariantId();
    }

    private void seedPricing() {
        priceBook = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO pricing.price_books (id, tenant_id, brand_id, name, currency, status, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, 'BRAND_MENU', 'UZS', 'ACTIVE', :from, 0)
                """)
                .param("id", priceBook)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("from", from)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.price_book_assignments (id, tenant_id, brand_id, price_book_id,
                    scope_type, scope_id, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, :book, 'BRAND', NULL, :from, 0)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("book", priceBook)
                .param("from", from)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.tax_profiles (id, tenant_id, brand_id, jurisdiction_code, mode,
                    rate_basis_points, valid_from)
                VALUES (:id, :tenantId, :brandId, 'UZ', 'INCLUSIVE', 1200, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("from", from)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.prices (id, tenant_id, brand_id, price_book_id, priceable_type,
                    priceable_id, amount_minor, valid_from)
                VALUES (:id, :tenantId, :brandId, :book, 'VARIANT', :variant, :amount, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("book", priceBook)
                .param("variant", burgerVariant)
                .param("amount", BURGER)
                .param("from", from)
                .update();
    }

    private void publishTheMenu() {
        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        assertThat(result.status())
                .as(
                        "the menu must publish through the real validator: %s",
                        result.report().blockers())
                .isEqualTo(PublicationStatus.PUBLISHED);
    }

    private void grant(String subject, UUID location) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :scopeId,
                        'ACTIVE', 'test-fixture', 'order entry http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param(
                        "id",
                        UUID.nameUUIDFromBytes(
                                (subject + PlatformRole.LOCATION_MANAGER.code() + location).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.LOCATION_MANAGER))
                .param("scopeId", location)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }
}
