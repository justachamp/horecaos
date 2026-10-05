package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
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
import org.springframework.context.annotation.Primary;
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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.ordering.application.CheckoutService;
import uz.horecaos.platform.ordering.application.ScheduledOrderRequoteService;
import uz.horecaos.platform.ordering.application.ScheduledOrderRequoteWorker;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderRequoteStore;
import uz.horecaos.platform.pricing.PromotionDbFixture;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.DiscountShape;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.PromoCodeDraft;
import uz.horecaos.platform.pricing.application.PromotionAuthoringService;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.reporting.application.DayCloseService;
import uz.horecaos.platform.reporting.application.SubjectPseudonym;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0140 across an order's life, against the real, fully wired application (rows 6.1 and 7.9).
 *
 * <p><b>The scheduled re-quote.</b> A pre-order is priced the moment it is taken; its promotions are
 * judged again at its checkpoint. The orders here are placed through the customer's own path (cart,
 * pricing, checkout, with a promised time) and the sweep, the operator's re-check and the read-back are the
 * production worker and the HTTP contract. The clock is the test's, because a lunch window is a statement
 * about the time of day and a test that sleeps until lunch proves nothing: 2026-10-05 is a Monday and
 * Tashkent is UTC+5, so 07:30Z is 12:30 there.
 *
 * <p><b>Checkout to report.</b> A firing promotion is claimed at a real checkout, the order is completed,
 * the day is closed by the production {@link DayCloseService}, and the 7.9 summary and log are read over
 * HTTP -- the chain ADR 0140 lists as proved only in segments. «Who redeemed it» is then asked of the
 * redemption the report names.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PromotionLifecycleHttpTests {

    private static final UUID TENANT = UUID.fromString("018fd400-d000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fd400-d000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fd400-d000-7000-8000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fd400-d000-7000-8000-0000000000c2");
    private static final UUID CUSTOMER = UUID.fromString("018fd400-d000-7000-8000-0000000000d1");

    private static final String ISSUER = "https://issuer.test/realms/horecaos";
    private static final String SHOPPER = "lifecycle-shopper";
    private static final String OPERATOR = "lifecycle-operator";
    private static final String MARKETER = "lifecycle-marketer";
    private static final String FINANCE = "lifecycle-finance";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 10:00 in Tashkent on Monday 2026-10-05. */
    private static final Instant TEN_AM = Instant.parse("2026-10-05T05:00:00Z");

    private static final PromotionDbFixture.MutableClock CLOCK = new PromotionDbFixture.MutableClock(TEN_AM);

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
        registry.add("horecaos.realtime.signals.publish", () -> "false");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
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
    private InventoryService inventory;

    @Autowired
    private PromoCodeAuthoringService promoCodes;

    @Autowired
    private PromotionAuthoringService promotionAuthoring;

    @Autowired
    private ScheduledOrderRequoteService requoteService;

    @Autowired
    private JdbcOrderRequoteStore requoteStore;

    @Autowired
    private DayCloseService dayClose;

    @Autowired
    private SubjectPseudonym pseudonym;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID burgerVariant;
    private UUID burgerProduct;
    private UUID colaVariant;
    private UUID priceBook;
    private ScheduledOrderRequoteWorker sweep;

    @BeforeEach
    void reset() {
        CLOCK.set(TEN_AM);
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("""
                TRUNCATE TABLE kitchen.tickets, kitchen.stations CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE reporting.fact_order_line, reporting.fact_order, reporting.fact_order_tender,
                    reporting.fact_refund, reporting.fact_promotion_redemption CASCADE
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
                TRUNCATE TABLE catalog.publication_items, catalog.publications,
                    catalog.location_offerings, catalog.translations, catalog.catalog_products,
                    catalog.variants, catalog.products, catalog.catalogs CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        roleRegistry.synchronize();

        seedTenancyAndCatalog();
        seedPricingAndStock();
        jdbc.sql("""
                INSERT INTO customer.principal_links (
                    id, tenant_id, customer_account_id, issuer, subject, status, linked_at)
                VALUES (:id, :t, :accountId, :issuer, :subject, 'ACTIVE', :now)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("accountId", CUSTOMER)
                .param("issuer", ISSUER)
                .param("subject", SHOPPER)
                .param("now", TEN_AM.minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
                .update();
        grant(OPERATOR, PlatformRole.TENANT_OWNER);
        grant(MARKETER, PlatformRole.TENANT_OWNER);
        grant(FINANCE, PlatformRole.TENANT_FINANCE);
        // The sweep is wired by hand: the scheduled one is switched off under test (it prices real orders on a
        // timer), and the hour before the promised time is the checkpoint the worker documents.
        sweep = new ScheduledOrderRequoteWorker(requoteService, requoteStore, CLOCK, Duration.ofHours(1), 50);
    }

    // ============================================================= the scheduled re-quote

    @Test
    @DisplayName("a pre-order taken before the lunch window gains the lunch promotion at its checkpoint, "
            + "and the order keeps the price it was taken at")
    void aPreOrderGainsAPromotionAtItsCheckpointWithoutChangingItsPrice() throws Exception {
        UUID lunch = activateLunch();
        UUID orderId = placeOrder(null, localTime(13, 30));
        assertThat(orderDetail(orderId).get("summary").get("discountMinor").asLong())
                .as("10:00, before the window: no lunch discount when the order is taken")
                .isZero();
        Map<String, Object> before = orderRow(orderId);

        // 11:30 local: the checkpoint (13:30 less an hour) has not arrived.
        CLOCK.set(instantAt(11, 30));
        assertThat(sweep.sweepOnce(CLOCK.instant())).as("not yet due").isZero();

        // 12:30 local, an hour before the promised time, inside 12:00 to 15:00.
        CLOCK.set(instantAt(12, 30));
        assertThat(sweep.sweepOnce(CLOCK.instant())).isEqualTo(1);

        JsonNode finding = onlyFinding(orderId);
        assertThat(finding.get("trigger").asText()).isEqualTo("CHECKPOINT");
        assertThat(finding.get("outcome").asText()).isEqualTo("CHANGED");
        assertThat(finding.get("checkpointAt").asText())
                .isEqualTo(instantAt(12, 30).toString());
        assertThat(finding.get("heldTotalMinor").asLong()).isEqualTo(100_000L);
        assertThat(finding.get("requoteTotalMinor").asLong()).isEqualTo(90_000L);
        assertThat(finding.get("deltaTotalMinor").asLong())
                .as("what the customer would pay less if the order were priced again")
                .isEqualTo(-10_000L);
        assertThat(finding.get("promotionChanges")).hasSize(1);
        JsonNode change = finding.get("promotionChanges").get(0);
        assertThat(change.get("promotionId").asText()).isEqualTo(lunch.toString());
        assertThat(change.get("change").asText()).isEqualTo("GAINED");
        assertThat(change.get("heldMinor").asLong()).isZero();
        assertThat(change.get("requoteMinor").asLong()).isEqualTo(-10_000L);

        // Evidence, not a repricing (ADR 0019): the order is exactly what the customer was quoted, and the
        // re-quote claimed nothing.
        assertThat(orderRow(orderId)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM pricing.promotion_redemptions WHERE order_id = :id", orderId))
                .as("the re-quote takes no promotion slot")
                .isZero();
    }

    @Test
    @DisplayName("a pre-order taken inside the lunch window loses the promotion when the window has closed by its "
            + "checkpoint, and the order and its ledger row are untouched")
    void aPreOrderDropsAPromotionWhoseWindowClosed() throws Exception {
        UUID lunch = activateLunch();
        CLOCK.set(instantAt(12, 10));
        UUID orderId = placeOrder(null, localTime(16, 0));
        JsonNode placed = orderDetail(orderId);
        assertThat(placed.get("summary").get("discountMinor").asLong()).isEqualTo(10_000L);
        assertThat(placed.get("summary").get("totalMinor").asLong()).isEqualTo(90_000L);
        Map<String, Object> before = orderRow(orderId);
        Map<String, Object> ledgerBefore = ledgerRow(orderId);

        // 15:05 local: an hour before the promised 16:00, five minutes past the window.
        CLOCK.set(instantAt(15, 5));
        assertThat(sweep.sweepOnce(CLOCK.instant())).isEqualTo(1);

        JsonNode finding = onlyFinding(orderId);
        assertThat(finding.get("outcome").asText()).isEqualTo("CHANGED");
        assertThat(finding.get("heldTotalMinor").asLong()).isEqualTo(90_000L);
        assertThat(finding.get("requoteTotalMinor").asLong()).isEqualTo(100_000L);
        assertThat(finding.get("deltaTotalMinor").asLong()).isEqualTo(10_000L);
        JsonNode change = finding.get("promotionChanges").get(0);
        assertThat(change.get("promotionId").asText()).isEqualTo(lunch.toString());
        assertThat(change.get("change").asText()).isEqualTo("DROPPED");
        assertThat(change.get("heldMinor").asLong()).isEqualTo(-10_000L);
        assertThat(change.get("requoteMinor").asLong()).isZero();

        assertThat(orderRow(orderId))
                .as("the customer keeps the price they were quoted")
                .isEqualTo(before);
        assertThat(ledgerRow(orderId)).as("and the redemption they hold").isEqualTo(ledgerBefore);
    }

    @Test
    @DisplayName("a pre-order whose promotions hold at the checkpoint is recorded as unchanged, once, however "
            + "often the sweep runs")
    void anUnchangedPreOrderIsRecordedOnce() throws Exception {
        activateLunch();
        CLOCK.set(instantAt(12, 10));
        UUID orderId = placeOrder(null, localTime(14, 30));

        CLOCK.set(instantAt(13, 30));
        assertThat(sweep.sweepOnce(CLOCK.instant())).isEqualTo(1);
        assertThat(sweep.sweepOnce(CLOCK.instant()))
                .as("the second pass finds it done")
                .isZero();
        CLOCK.set(instantAt(13, 45));
        assertThat(sweep.sweepOnce(CLOCK.instant()))
                .as("and so does a later one")
                .isZero();

        JsonNode finding = onlyFinding(orderId);
        assertThat(finding.get("outcome").asText()).isEqualTo("UNCHANGED");
        assertThat(finding.get("promotionChanges")).isEmpty();
        assertThat(finding.get("heldTotalMinor").asLong()).isEqualTo(90_000L);
        assertThat(finding.get("requoteTotalMinor").asLong()).isEqualTo(90_000L);
        assertThat(finding.get("deltaTotalMinor").asLong()).isZero();
        assertThat(count(
                        "SELECT count(*) FROM ordering.order_promotion_requotes WHERE order_id = :id"
                                + " AND trigger_kind = 'CHECKPOINT'",
                        orderId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an immediate order, a pre-order the kitchen has started and an order past its promised time are "
            + "not swept")
    void onlyAScheduledOrderWaitingForItsKitchenIsSwept() throws Exception {
        activateLunch();
        UUID immediate = placeOrder(null, null);
        UUID started = placeOrder(null, localTime(13, 30));
        advance(started, "PREPARING");
        UUID soon = placeOrder(null, localTime(13, 0));

        // 12:30: both pre-orders are within the hour; the immediate one has no promised time to approach.
        CLOCK.set(instantAt(12, 30));
        assertThat(sweep.sweepOnce(CLOCK.instant()))
                .as("only the pre-order still waiting")
                .isEqualTo(1);
        assertThat(findings(immediate)).isEmpty();
        assertThat(findings(started)).as("the kitchen has it").isEmpty();
        assertThat(findings(soon)).hasSize(1);

        // The promised 13:00 passes without the sweep having run for it again: nothing more to judge.
        UUID missed = placeOrder(null, localTime(13, 15));
        CLOCK.set(instantAt(13, 20));
        assertThat(sweep.sweepOnce(CLOCK.instant()))
                .as("past its promised time")
                .isZero();
        assertThat(findings(missed)).isEmpty();
    }

    @Test
    @DisplayName("a basket that can no longer be priced is a finding with the refusal, not a failure, and is not "
            + "retried")
    void anUnpriceableBasketIsRecordedAndNotRetried() throws Exception {
        activateLunch();
        UUID orderId = placeOrder(null, localTime(13, 30));
        // The menu price lapses before the checkpoint.
        jdbc.sql("UPDATE pricing.prices SET valid_until = :until WHERE price_book_id = :book")
                .param("until", instantAt(11, 0).atOffset(ZoneOffset.UTC))
                .param("book", priceBook)
                .update();
        Map<String, Object> before = orderRow(orderId);

        CLOCK.set(instantAt(12, 30));
        assertThat(sweep.sweepOnce(CLOCK.instant())).isEqualTo(1);
        assertThat(sweep.sweepOnce(CLOCK.instant()))
                .as("recorded, so not tried again")
                .isZero();

        JsonNode finding = onlyFinding(orderId);
        assertThat(finding.get("outcome").asText()).isEqualTo("NOT_PRICEABLE");
        assertThat(finding.get("refusalCode").asText()).isEqualTo("ITEM_NOT_PRICED");
        assertThat(finding.get("requoteTotalMinor").isNull()).isTrue();
        assertThat(finding.get("deltaTotalMinor").isNull()).isTrue();
        assertThat(orderRow(orderId)).isEqualTo(before);
    }

    @Test
    @DisplayName(
            "an operator re-checks a pre-order's promotions over HTTP, and the findings are read back newest first")
    void anOperatorCanRecheckAndReadTheFindings() throws Exception {
        UUID lunch = activateLunch();
        UUID orderId = placeOrder(null, localTime(13, 30));

        CLOCK.set(instantAt(12, 45));
        MvcResult rechecked = mvc.perform(post(requotePath(orderId))
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "recheck-" + UUID.randomUUID()))
                .andReturn();
        assertThat(rechecked.getResponse().getStatus())
                .as(rechecked.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode recheck = JSON.readTree(rechecked.getResponse().getContentAsString());
        assertThat(recheck.get("trigger").asText()).isEqualTo("OPERATOR");
        assertThat(recheck.get("outcome").asText()).isEqualTo("CHANGED");
        assertThat(recheck.get("promotionChanges").get(0).get("promotionId").asText())
                .isEqualTo(lunch.toString());

        CLOCK.set(instantAt(12, 30).plus(Duration.ofMinutes(30)));
        assertThat(sweep.sweepOnce(CLOCK.instant())).isEqualTo(1);

        List<JsonNode> all = findings(orderId);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).get("trigger").asText()).as("newest first").isEqualTo("CHECKPOINT");
        assertThat(all.get(1).get("trigger").asText()).isEqualTo("OPERATOR");

        // The audit trail names who asked, and for the sweep that it was the job.
        assertThat(count(
                        "SELECT count(*) FROM audit.audit_events WHERE action_code = 'ordering.order.promotion_requoted'"
                                + " AND target_id = :id",
                        orderId))
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT actor_type FROM audit.audit_events"
                                + " WHERE action_code = 'ordering.order.promotion_requoted' AND target_id = :id"
                                + " ORDER BY recorded_at")
                        .param("id", orderId)
                        .query(String.class)
                        .list())
                .containsExactly("USER", "SYSTEM_JOB");
    }

    @Test
    @DisplayName("the re-check and the findings enforce their capabilities, the order's own location and the order's "
            + "kind")
    void theEndpointsRefuseWhatTheyShould() throws Exception {
        activateLunch();
        UUID scheduled = placeOrder(null, localTime(13, 30));
        UUID immediate = placeOrder(null, null);

        // Finance reads orders and reports and cannot amend them: it sees what the checkpoint found, and cannot ask for
        // more.
        MvcResult noCapability = mvc.perform(post(requotePath(scheduled))
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "recheck-" + UUID.randomUUID()))
                .andReturn();
        assertThat(noCapability.getResponse().getStatus()).isEqualTo(403);
        assertThat(noCapability.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");
        assertThat(mvc.perform(get(requotePath(scheduled)).with(tokenFor(FINANCE)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(get(requotePath(scheduled)).with(tokenFor("lifecycle-nobody")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("and a caller with no grant at all sees nothing")
                .isEqualTo(403);

        MvcResult notScheduled = mvc.perform(post(requotePath(immediate))
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "recheck-" + UUID.randomUUID()))
                .andReturn();
        assertThat(notScheduled.getResponse().getStatus()).isEqualTo(409);
        assertThat(notScheduled.getResponse().getContentAsString()).contains("NOT_AWAITING_CHECKPOINT");

        String elsewhere = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + OTHER_LOCATION
                + "/orders/" + scheduled + "/promotion-requotes";
        assertThat(mvc.perform(get(elsewhere).with(tokenFor(OPERATOR)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("an order that is in another location is not found, not forbidden")
                .isEqualTo(404);
        assertThat(findings(scheduled)).isEmpty();
    }

    // ================================================================ the gift, offered on the priced cart

    @Test
    @DisplayName("the priced cart over HTTP offers the gift a firing FREE_ITEM rule would price free, adds nothing, "
            + "and prices it free once the customer puts it in")
    void thePricedCartOffersTheGiftAndAddsNothing() throws Exception {
        UUID rule = activate(PromotionDbFixture.definition(
                "FREECOLA",
                Promotion.Scope.ITEM,
                "gift",
                List.of(PromotionDbFixture.condition(
                        1, Promotion.Condition.Type.PRODUCT, "productIds", List.of(burgerProduct.toString()))),
                List.of(PromotionDbFixture.action(
                        1,
                        Promotion.Action.Type.FREE_ITEM,
                        "variantIds",
                        List.of(colaVariant.toString()),
                        "quantity",
                        1L))));
        UUID cart = tx(() ->
                        carts.create(TENANT, BRAND, LOCATION, "STOREFRONT", FulfillmentMode.PICKUP, CUSTOMER, null))
                .cartId();
        tx(() -> carts.putLine(
                TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), "burger", burgerVariant, 1, List.of(), null));

        JsonNode offered = priceStorefrontCart(cart);
        assertThat(offered.get("totalMinor").asLong())
                .as("the gift is not in the cart, so nothing is priced for it")
                .isEqualTo(50_000L);
        assertThat(offered.get("discountMinor").asLong()).isZero();
        assertThat(offered.get("giftOffers")).hasSize(1);
        JsonNode offer = offered.get("giftOffers").get(0);
        assertThat(offer.get("ruleId").asText()).isEqualTo(rule.toString());
        assertThat(offer.get("variantId").asText()).isEqualTo(colaVariant.toString());
        assertThat(offer.get("quantity").decimalValue()).isEqualByComparingTo("1");
        assertThat(offer.get("inCart").asBoolean()).isFalse();
        assertThat(offer.get("toAdd").decimalValue()).isEqualByComparingTo("1");
        assertThat(count("SELECT count(*) FROM ordering.cart_lines WHERE cart_id = :id", cart))
                .as("pricing never invents a line")
                .isEqualTo(1);

        // The customer takes the offer: the Cola is in the cart, priced free, and there is nothing left to add.
        tx(() -> carts.putLine(
                TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), "cola", colaVariant, 1, List.of(), null));
        JsonNode taken = priceStorefrontCart(cart);
        assertThat(taken.get("discountMinor").asLong()).isEqualTo(12_000L);
        assertThat(taken.get("totalMinor").asLong()).isEqualTo(50_000L);
        JsonNode inCart = taken.get("giftOffers").get(0);
        assertThat(inCart.get("inCart").asBoolean()).isTrue();
        assertThat(inCart.get("toAdd").decimalValue()).isEqualByComparingTo("0");
    }

    // ===================================================== checkout, day close, the report, who redeemed it

    @Test
    @DisplayName("a promotion that fires at a real checkout is on the day's report after the day closes, and the "
            + "redemption names its customer only to someone who asks, with a purpose, on the record")
    void aFiringPromotionTravelsFromCheckoutThroughDayCloseToTheReportAndCanBeTraced() throws Exception {
        UUID promotion = activate(promotion(
                "BULK",
                List.of(PromotionDbFixture.condition(
                        1, Promotion.Condition.Type.SUBTOTAL_AT_LEAST, "amountMinor", 150_000L)),
                List.of(PromotionDbFixture.action(
                        1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));

        // 3 burgers: 150,000, over the threshold -> 15,000 off. 2 burgers: 100,000, no promotion.
        UUID withPromotion = placeOrder(null, null, 3);
        UUID without = placeOrder(null, null, 2);
        assertThat(orderDetail(withPromotion)
                        .get("summary")
                        .get("discountMinor")
                        .asLong())
                .isEqualTo(15_000L);
        assertThat(orderDetail(without).get("summary").get("discountMinor").asLong())
                .isZero();
        complete(withPromotion);
        complete(without);

        // Before the day closes the report has nothing: a redemption appears after the close (ADR 0140).
        assertThat(promotionReport("summary").get("rows")).isEmpty();

        dayClose.close(TENANT, LocalDate.of(2026, 10, 5));

        JsonNode summary = promotionReport("summary");
        assertThat(summary.get("rows")).hasSize(1);
        JsonNode row = summary.get("rows").get(0);
        assertThat(row.get("promotionId").asText()).isEqualTo(promotion.toString());
        assertThat(row.get("promotionCode").asText()).isEqualTo("BULK");
        assertThat(row.get("sourceKind").asText()).isEqualTo("AUTOMATIC");
        assertThat(row.get("redemptions").asLong()).isEqualTo(1);
        assertThat(row.get("uniqueCustomers").asLong()).isEqualTo(1);
        assertThat(row.get("discountSom").asLong()).isEqualTo(15_000L);
        assertThat(row.get("revenueWithSom").asLong()).isEqualTo(150_000L);
        assertThat(row.get("averageCheckWithSom").asLong())
                .as("the order that carried the promotion")
                .isEqualTo(150_000L);
        assertThat(row.get("averageCheckWithoutSom").asLong())
                .as("the brand's other completed order in the period")
                .isEqualTo(100_000L);

        JsonNode log = promotionReport("redemptions");
        assertThat(log.get("rows")).hasSize(1);
        JsonNode redemption = log.get("rows").get(0);
        assertThat(redemption.get("orderId").asText()).isEqualTo(withPromotion.toString());
        assertThat(redemption.get("discountSom").asLong()).isEqualTo(15_000L);
        assertThat(redemption.get("channelCode").asText()).isEqualTo("STOREFRONT");
        assertThat(redemption.get("orderStatus").asText()).isEqualTo("COMPLETED");
        assertThat(redemption.get("customerSubject").asText())
                .as("the customer is the ADR 0029 pseudonym and nothing that opens the customer")
                .isEqualTo(pseudonym.of(TENANT, CUSTOMER))
                .isNotEqualTo(CUSTOMER.toString());
        assertThat(log.toString()).doesNotContain(CUSTOMER.toString());
        UUID redemptionId = UUID.fromString(redemption.get("redemptionId").asText());

        // «Who redeemed it»: refused without a purpose, refused without customer.read, found with both.
        assertThat(reveal(promotion, redemptionId, MARKETER, "{\"purpose\":\"  \"}")
                        .getResponse()
                        .getStatus())
                .as("a purpose is required")
                .isEqualTo(400);
        assertThat(mvc.perform(get("/api/v1/tenants/" + TENANT + "/reporting/promotions/redemptions")
                                .with(tokenFor(FINANCE))
                                .queryParam("from", "2026-10-05")
                                .queryParam("to", "2026-10-05"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("finance reads the report, which is not the same as being told whose it is")
                .isEqualTo(200);
        MvcResult refused = reveal(promotion, redemptionId, FINANCE, "{\"purpose\":\"Checking a complaint\"}");
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains("customer.read");
        assertThat(count("SELECT count(*) FROM audit.audit_events WHERE action_code LIKE :a", "%customer_revealed%"))
                .as("a refused lookup reveals nothing and records no reveal")
                .isZero();

        MvcResult found =
                reveal(promotion, redemptionId, MARKETER, "{\"purpose\":\"Checking a complaint about order\"}");
        assertThat(found.getResponse().getStatus())
                .as(found.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode who = JSON.readTree(found.getResponse().getContentAsString());
        assertThat(who.get("customerAccountId").asText())
                .as("the account the customer card opens with")
                .isEqualTo(CUSTOMER.toString());
        assertThat(who.get("sourceKind").asText()).isEqualTo("AUTOMATIC");
        assertThat(who.get("orderId").asText()).isEqualTo(withPromotion.toString());
        assertThat(who.size())
                .as("an account id and what it was asked about, no name or contact")
                .isEqualTo(5);

        Map<String, Object> audit = jdbc.sql("""
                SELECT audit_class, actor_subject, target_type, target_id, reason, capability_used,
                       change_document::text AS document
                FROM audit.audit_events WHERE action_code = 'pricing.promotion.redemption.customer_revealed'
                """).query().singleRow();
        assertThat(audit.get("audit_class")).isEqualTo("SECURITY");
        assertThat(audit.get("actor_subject")).isEqualTo(MARKETER);
        assertThat(audit.get("target_type"))
                .as("on the customer's own access log")
                .isEqualTo("customer_account");
        assertThat(audit.get("target_id")).isEqualTo(CUSTOMER);
        assertThat(audit.get("reason")).isEqualTo("Checking a complaint about order");
        assertThat(audit.get("capability_used")).isEqualTo("customer.read");
        assertThat(String.valueOf(audit.get("document")))
                .contains(redemptionId.toString())
                .contains("AUTOMATIC")
                .doesNotContain("15000");

        // Not another brand's, not another promotion's, not a made-up id.
        UUID otherBrand = UUID.randomUUID();
        assertThat(mvc.perform(post("/api/v1/operations/tenants/" + TENANT + "/brands/" + otherBrand + "/promotions/"
                                        + promotion + "/redemptions/" + redemptionId + "/customer-reveal")
                                .with(tokenFor(MARKETER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reveal-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"purpose\":\"Checking\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(reveal(UUID.randomUUID(), redemptionId, MARKETER, "{\"purpose\":\"Checking\"}")
                        .getResponse()
                        .getStatus())
                .as("the redemption belongs to a different promotion")
                .isEqualTo(404);
        assertThat(reveal(promotion, UUID.randomUUID(), MARKETER, "{\"purpose\":\"Checking\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);

        // A guest order has no account to open: the answer says so, and is recorded all the same.
        jdbc.sql("UPDATE pricing.promotion_redemptions SET customer_account_id = NULL WHERE id = :id")
                .param("id", redemptionId)
                .update();
        MvcResult guest = reveal(promotion, redemptionId, MARKETER, "{\"purpose\":\"Checking a guest order\"}");
        assertThat(guest.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(guest.getResponse().getContentAsString())
                        .get("customerAccountId")
                        .isNull())
                .isTrue();
        assertThat(count(
                        "SELECT count(*) FROM audit.audit_events WHERE action_code ="
                                + " 'pricing.promotion.redemption.customer_revealed' AND target_type ="
                                + " 'promotion_redemption'",
                        null))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a typed promo code is on the same report, told apart by its source, and is traced the same way")
    void aTypedCodeIsReportedAndTracedTheSameWay() throws Exception {
        var coupon = authorPromoCode("REPORT10");
        UUID orderId = placeOrder("REPORT10", null);
        complete(orderId);
        dayClose.close(TENANT, LocalDate.of(2026, 10, 5));

        JsonNode summary = promotionReport("summary").get("rows");
        assertThat(summary).hasSize(1);
        assertThat(summary.get(0).get("sourceKind").asText()).isEqualTo("COUPON");
        assertThat(summary.get(0).get("redemptions").asLong()).isEqualTo(1);
        assertThat(summary.get(0).get("discountSom").asLong()).isEqualTo(10_000L);

        JsonNode redemption = promotionReport("redemptions").get("rows").get(0);
        assertThat(redemption.get("sourceKind").asText()).isEqualTo("COUPON");
        UUID promotionId = UUID.fromString(redemption.get("promotionId").asText());
        UUID redemptionId = UUID.fromString(redemption.get("redemptionId").asText());

        MvcResult found = reveal(promotionId, redemptionId, MARKETER, "{\"purpose\":\"Goodwill code review\"}");
        assertThat(found.getResponse().getStatus())
                .as(found.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode who = JSON.readTree(found.getResponse().getContentAsString());
        assertThat(who.get("customerAccountId").asText()).isEqualTo(CUSTOMER.toString());
        assertThat(who.get("sourceKind").asText()).isEqualTo("COUPON");
        assertThat(coupon.couponId()).isNotNull();
    }

    // ---------------------------------------------------------------- helpers: promotions

    private static PromotionDefinition promotion(
            String code,
            List<PromotionDefinition.ConditionDefinition> conditions,
            List<PromotionDefinition.ActionDefinition> actions) {
        return PromotionDbFixture.definition(
                code, Promotion.Scope.ORDER, "g-" + code.toLowerCase(), conditions, actions);
    }

    /** 10% off the order between 12:00 and 15:00 Tashkent time, unlimited. */
    private UUID activateLunch() {
        return activate(promotion(
                "LUNCH",
                List.of(PromotionDbFixture.condition(
                        1,
                        Promotion.Condition.Type.TIME_OF_DAY,
                        "fromMinuteOfDay",
                        12 * 60L,
                        "toMinuteOfDay",
                        15 * 60L)),
                List.of(PromotionDbFixture.action(
                        1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));
    }

    /** Authors, validates and activates a promotion through the production service, as a marketer would. */
    private UUID activate(PromotionDefinition definition) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(
                        Jwt.withTokenValue("promotion-author")
                                .header("alg", "none")
                                .subject(MARKETER)
                                .build(),
                        List.of()));
        try {
            var drafted = promotionAuthoring.create(TENANT, BRAND, definition);
            var validated = promotionAuthoring.validate(TENANT, BRAND, drafted.id(), drafted.version());
            assertThat(validated.report().isValid())
                    .as(String.valueOf(validated.report().refusals()))
                    .isTrue();
            var activated = promotionAuthoring.activate(
                    TENANT, BRAND, drafted.id(), validated.promotion().version(), "lifecycle test");
            assertThat(activated.isPending())
                    .as("a promotion this small needs no second person")
                    .isFalse();
            return drafted.id();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    private uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore.PromoCodeAuthoringRow
            authorPromoCode(String code) {
        var drafted = promoCodes.draft(
                TENANT,
                BRAND,
                new PromoCodeDraft(
                        "Promo " + code,
                        code,
                        DiscountShape.PERCENTAGE_OFF_ORDER,
                        1_000,
                        null,
                        "UZS",
                        0,
                        List.of(),
                        List.of(),
                        5,
                        5,
                        null,
                        null));
        promoCodes.activate(TENANT, BRAND, drafted.couponId());
        return drafted;
    }

    // ------------------------------------------------------------------ helpers: orders

    /** {@code quantity} burgers at 50,000, optionally with a code, optionally promised for later. */
    private UUID placeOrder(@Nullable String code, @Nullable Instant requestedFor) {
        return placeOrder(code, requestedFor, 2);
    }

    private UUID placeOrder(@Nullable String code, @Nullable Instant requestedFor, int quantity) {
        UUID cart = tx(() ->
                        carts.create(TENANT, BRAND, LOCATION, "STOREFRONT", FulfillmentMode.PICKUP, CUSTOMER, null))
                .cartId();
        tx(() -> carts.putLine(
                TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), "a", burgerVariant, quantity, List.of(), null));
        if (code != null) {
            tx(() -> carts.applyPromoCode(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), code));
        }
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));

        var row = cartStore.find(TENANT, BRAND, cart).orElseThrow();
        var result = tx(() -> checkout.checkout(new CheckoutService.CheckoutCommand(
                TENANT,
                BRAND,
                cart,
                row.version(),
                Objects.requireNonNull(row.pricingQuoteId(), "the cart was priced first"),
                Objects.requireNonNull(row.pricingContextHash(), "the cart was priced first"),
                "idem-lifecycle-" + UUID.randomUUID(),
                "CASH",
                0L,
                "CUSTOMER",
                CUSTOMER.toString(),
                null,
                requestedFor,
                false)));
        assertThat(result.rejectionCode()).as("the checkout was accepted").isNull();
        return Objects.requireNonNull(result.orderId(), "a created checkout always has an order id");
    }

    /** Takes a pickup order from confirmed to completed, the way the kitchen and the till do. */
    private void complete(UUID orderId) throws Exception {
        advance(orderId, "PREPARING");
        advance(orderId, "READY");
        advance(orderId, "COMPLETED");
    }

    private void advance(UUID orderId, String target) throws Exception {
        int version = orderDetail(orderId).get("summary").get("version").asInt();
        MvcResult moved = mvc.perform(post(orderPath(orderId) + "/state-actions")
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "state-" + UUID.randomUUID())
                        .header("If-Match", "\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"" + target + "\",\"reasonCode\":\"KITCHEN\"}"))
                .andReturn();
        assertThat(moved.getResponse().getStatus())
                .as("-> " + target + ": " + moved.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    /** The order as the customer holds it: version, revision, quote and every money figure. */
    private Map<String, Object> orderRow(UUID orderId) {
        return jdbc.sql("""
                SELECT version, current_revision, pricing_quote_id, subtotal_minor, tax_minor, discount_minor,
                       fee_minor, total_minor, status
                FROM ordering.orders WHERE id = :id
                """).param("id", orderId).query().singleRow();
    }

    private Map<String, Object> ledgerRow(UUID orderId) {
        return jdbc.sql("""
                SELECT id, status, discount_minor, claimed_quote_id, current_quote_id, last_revision
                FROM pricing.promotion_redemptions WHERE order_id = :id
                """).param("id", orderId).query().singleRow();
    }

    private long count(String sql, @Nullable Object parameter) {
        var statement = jdbc.sql(sql);
        if (parameter instanceof UUID id) {
            statement = statement.param("id", id);
        } else if (parameter instanceof String text) {
            statement = statement.param("a", text);
        }
        return statement.query(Long.class).single();
    }

    // -------------------------------------------------------------------- helpers: HTTP

    private List<JsonNode> findings(UUID orderId) throws Exception {
        MvcResult result =
                mvc.perform(get(requotePath(orderId)).with(tokenFor(OPERATOR))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        List<JsonNode> list = new java.util.ArrayList<>();
        JSON.readTree(result.getResponse().getContentAsString()).forEach(list::add);
        return list;
    }

    private JsonNode onlyFinding(UUID orderId) throws Exception {
        List<JsonNode> all = findings(orderId);
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    private JsonNode promotionReport(String which) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/tenants/" + TENANT + "/reporting/promotions/" + which)
                        .with(tokenFor(OPERATOR))
                        .queryParam("from", "2026-10-05")
                        .queryParam("to", "2026-10-05"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** The storefront's own call: the signed-in customer prices the cart at its current version. */
    private JsonNode priceStorefrontCart(UUID cart) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/carts/"
                                + cart + "/pricing")
                        .with(jwt().jwt(builder -> builder.issuer(ISSUER).subject(SHOPPER)))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"" + cartVersion(cart) + "\""))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult reveal(UUID promotionId, UUID redemptionId, String subject, String body) throws Exception {
        return mvc.perform(
                        post(promotionsPath() + "/" + promotionId + "/redemptions/" + redemptionId + "/customer-reveal")
                                .with(tokenFor(subject))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reveal-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andReturn();
    }

    private static String promotionsPath() {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/promotions";
    }

    private String orderPath(UUID orderId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/" + orderId;
    }

    private String requotePath(UUID orderId) {
        return orderPath(orderId) + "/promotion-requotes";
    }

    private JsonNode orderDetail(UUID orderId) throws Exception {
        MvcResult result =
                mvc.perform(get(orderPath(orderId)).with(tokenFor(OPERATOR))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private int cartVersion(UUID cartId) {
        return cartStore.find(TENANT, BRAND, cartId).orElseThrow().version();
    }

    private <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private void tx(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    /** Local Tashkent time on the test's Monday as an instant (UTC+5, no daylight saving). */
    private static Instant instantAt(int hour, int minute) {
        return TEN_AM.minus(Duration.ofHours(10)).plus(Duration.ofHours(hour).plusMinutes(minute));
    }

    private static Instant localTime(int hour, int minute) {
        return instantAt(hour, minute);
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'promotion lifecycle test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", role.scopeType().name())
                .param("scopeId", TENANT)
                // A day before the test's own clock, which is not the machine's.
                .param("validFrom", TEN_AM.minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    // ------------------------------------------------------------------ seeds

    private void seedTenancyAndCatalog() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'promo-lifecycle', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        for (UUID location : List.of(LOCATION, OTHER_LOCATION)) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, 'Branch', 'Asia/Tashkent',
                        'ACTIVE', 0)
                    """)
                    .param("id", location)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("code", location.equals(LOCATION) ? "MAIN01" : "MAIN02")
                    .param("slug", location.equals(LOCATION) ? "main-01" : "main-02")
                    .update();
        }
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
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id,
                    status)
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
        jdbc.sql("""
                INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id,
                    fulfillment_mode, enabled)
                VALUES (:tenantId, :channelId, 'PICKUP', true)
                """)
                .param("tenantId", TENANT)
                .param("channelId", storefrontChannel)
                .update();
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
                INSERT INTO tenant.service_schedules (id, tenant_id, brand_id, name,
                    accepts_scheduled_orders)
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
        jdbc.sql("""
                INSERT INTO tenant.location_service_bindings (tenant_id, brand_id,
                    location_id, fulfillment_mode, schedule_id)
                VALUES (:tenantId, :brandId, :locationId, 'PICKUP', :scheduleId)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("scheduleId", scheduleId)
                .update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        burgerProduct = UUID.randomUUID();
        burgerVariant = UUID.randomUUID();
        seedProduct(catalogId, burgerProduct, burgerVariant, "BURGER", "Qo'y burger");
        colaVariant = UUID.randomUUID();
        seedProduct(catalogId, UUID.randomUUID(), colaVariant, "COLA", "Cola");
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash',
                    :activatedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .param("activatedAt", TEN_AM.minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedProduct(UUID catalogId, UUID productId, UUID variantId, String code, String name) {
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
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, :sku, 'ACTIVE')
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
                INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id,
                    locale, name)
                VALUES (:tenantId, :brandId, 'PRODUCT', :productId, 'uz', :name)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .param("name", name)
                .update();
    }

    private void seedPrice(UUID variantId, long amountMinor, java.time.OffsetDateTime validFrom) {
        jdbc.sql("""
                INSERT INTO pricing.prices (id, tenant_id, brand_id, price_book_id, priceable_type,
                    priceable_id, amount_minor, valid_from)
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'VARIANT', :variantId, :amount, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("priceBookId", priceBook)
                .param("variantId", variantId)
                .param("amount", amountMinor)
                .param("from", validFrom)
                .update();
    }

    private void seedPricingAndStock() {
        priceBook = UUID.randomUUID();
        var validFrom = TEN_AM.minus(Duration.ofDays(2)).atOffset(ZoneOffset.UTC);

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
        seedPrice(burgerVariant, 50_000L, validFrom);
        seedPrice(colaVariant, 12_000L, validFrom);
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

        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, burgerVariant, TrackingMode.BINARY);
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, colaVariant, TrackingMode.BINARY);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }

        /** The test's clock, which moves when the test says so: a lunch window is about the time of day. */
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }
}
