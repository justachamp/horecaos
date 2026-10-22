package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The split-tender surfaces over HTTP, against the real, fully wired application (ADR 0046, ADR 0185): what a
 * customer may spend of their points, what they hold, how an order they placed is being paid, and the same
 * offer for an operator on the phone, carried through to an order that redeems at entry.
 *
 * <p>Nothing is planted that production produces another way. The points are given by the production adjustment
 * path; the order is taken by the operator's production endpoint and so planned by the production settlement
 * planner; the customer's split is read back from the rows that endpoint wrote. The JSON an operator console sends
 * is sent here, so a field Jackson 3 would refuse as a missing primitive fails this test and not the first phone
 * call.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SplitTenderEndpointsHttpTests {

    private static final UUID TENANT = UUID.fromString("018fc200-c000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fc200-c000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fc200-c000-7000-8000-0000000000c1");
    private static final UUID CUSTOMER = UUID.fromString("018fc200-c000-7000-8000-0000000000d1");
    private static final UUID STRANGER_ACCOUNT = UUID.fromString("018fc200-c000-7000-8000-0000000000d2");

    private static final String OPERATOR = "split-operator";
    private static final String CUSTOMER_SUBJECT = "split-customer";
    private static final String STRANGER_SUBJECT = "split-stranger";
    private static final String STAFF_ONLY = "split-staff-without-an-account";

    /** The realm the resource server trusts by default; a customer is the pair (issuer, subject). */
    private static final String ISSUER = "http://localhost:8081/realms/horecaos";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

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
        // The kitchen ticket raises a realtime signal on the calling thread; against the dead broker above every
        // one of them waits out the producer's ten-second metadata block.
        registry.add("horecaos.realtime.signals.publish", () -> "false");
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
    private JdbcCartStore cartStore;

    @Autowired
    private CatalogAuthoringService catalogAuthoring;

    @Autowired
    private CatalogPublicationService publication;

    @Autowired
    private LoyaltyAdjustmentService loyaltyAdjustments;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID plovVariant;
    private UUID catalogId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE kitchen.tickets, kitchen.stations CASCADE").update();
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
                    payments.order_remedies, payments.tenders, payments.order_settlements,
                    payments.payment_methods CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE loyalty.reservation_lots, loyalty.reservations, loyalty.lots,
                    loyalty.entries, loyalty.accrual_rules, loyalty.redemption_policies,
                    loyalty.accounts CASCADE
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
                TRUNCATE TABLE catalog.publication_items, catalog.publications, catalog.location_offerings,
                    catalog.translations, catalog.catalog_products, catalog.variants, catalog.products,
                    catalog.catalogs CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        roleRegistry.synchronize();

        seedTenancy();
        seedCatalogAndPricing();
        seedFallbackStation();
        seedRedemptionPolicy();
        grant(OPERATOR, PlatformRole.TENANT_OWNER, TENANT);
    }

    // ------------------------------------------------------------ the customer's own balance

    @Test
    @DisplayName("a customer reads their own points balance, and only their own, with no staff capability")
    void aCustomerReadsTheirOwnBalance() throws Exception {
        seedBalance(30_000L);

        MvcResult own = mvc.perform(get(balanceUrl()).with(customerToken(CUSTOMER_SUBJECT)))
                .andReturn();

        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        JsonNode balance = JSON.readTree(own.getResponse().getContentAsString());
        assertThat(balance.get("balance").get("amountMinor").asLong()).isEqualTo(30_000L);
        assertThat(balance.get("spendable").get("amountMinor").asLong()).isEqualTo(30_000L);
        assertThat(balance.get("held").get("amountMinor").asLong()).isZero();
        assertThat(balance.get("balance").get("currency").asText()).isEqualTo("UZS");
        assertThat(balance.get("brandId").asText()).isEqualTo(BRAND.toString());
        assertThat(own.getResponse().getHeader("Cache-Control")).contains("no-store");

        // Another customer reads their own, which is nothing: no account, and no amounts to state a currency for.
        MvcResult other = mvc.perform(get(balanceUrl()).with(customerToken(STRANGER_SUBJECT)))
                .andReturn();
        assertThat(other.getResponse().getStatus()).isEqualTo(200);
        JsonNode nothing = JSON.readTree(other.getResponse().getContentAsString());
        assertThat(nothing.path("accountId").isNull()
                        || nothing.path("accountId").isMissingNode())
                .isTrue();
        assertThat(nothing.path("balance").isNull() || nothing.path("balance").isMissingNode())
                .isTrue();
        assertThat(other.getResponse().getContentAsString())
                .as("another customer's points are not in this answer")
                .doesNotContain("30000");
    }

    @Test
    @DisplayName("a principal that is not a customer of this brand is told there is no such account, and a caller "
            + "with no token is not let in")
    void theBalanceIsForCustomersOnly() throws Exception {
        seedBalance(30_000L);

        assertThat(mvc.perform(get(balanceUrl()).with(tokenFor(STAFF_ONLY)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a staff token is not a customer, whatever else it can do")
                .isEqualTo(404);
        assertThat(mvc.perform(get(balanceUrl())).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    // ----------------------------------------------------------------- the customer's cart offer

    @Test
    @DisplayName("a customer's cart offer is what the policy and their balance allow, read from the priced quote, "
            + "and it holds nothing")
    void aCartOfferIsReadFromThePricedQuote() throws Exception {
        seedBalance(30_000L);
        UUID cart = pricedCart(2);

        MvcResult result = mvc.perform(get(cartOfferUrl(cart)).with(customerToken(CUSTOMER_SUBJECT)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode offer = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(offer.get("priced").asBoolean()).isTrue();
        assertThat(offer.get("orderTotal").get("amountMinor").asLong()).isEqualTo(80_000L);
        assertThat(offer.get("offer").get("available").get("amountMinor").asLong())
                .isEqualTo(30_000L);
        assertThat(offer.get("offer").get("maximum").get("amountMinor").asLong())
                .as("the smaller of the 50% cap (40 000) and what they hold (30 000)")
                .isEqualTo(30_000L);
        assertThat(offer.get("offer").get("redeemable").asBoolean()).isTrue();
        assertThat(offer.get("offer").get("refusal").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM loyalty.reservations")
                        .query(Long.class)
                        .single())
                .as("asking holds nothing")
                .isZero();
    }

    @Test
    @DisplayName("another customer's cart, a missing one and a cart nobody priced are each answered by name")
    void aCartOfferRefusesStrangersAndSaysWhenNothingIsPriced() throws Exception {
        seedBalance(30_000L);
        UUID cart = pricedCart(2);
        UUID unpriced = openCart();
        putLine(unpriced, 2);

        assertThat(mvc.perform(get(cartOfferUrl(cart)).with(customerToken(STRANGER_SUBJECT)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a cart id is not a bearer token")
                .isEqualTo(404);
        assertThat(mvc.perform(get(cartOfferUrl(UUID.randomUUID())).with(customerToken(CUSTOMER_SUBJECT)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);

        MvcResult notPriced = mvc.perform(get(cartOfferUrl(unpriced)).with(customerToken(CUSTOMER_SUBJECT)))
                .andReturn();
        JsonNode offer = JSON.readTree(notPriced.getResponse().getContentAsString());
        assertThat(offer.get("priced").asBoolean()).isFalse();
        assertThat(offer.get("offer").get("refusal").asText()).isEqualTo("CART_NOT_PRICED");
        assertThat(offer.get("offer").get("redeemable").asBoolean()).isFalse();
    }

    // ----------------------------------------------- the operator's offer, order and the customer's split

    @Test
    @DisplayName("an operator offers the customer their points, takes the order with the redemption, and the customer "
            + "then reads the split on their own order page")
    void anOperatorOffersTakesAndTheCustomerReadsTheSplit() throws Exception {
        seedBalance(30_000L);

        // The offer: the same body as the order, priced by the same path and then undone.
        JsonNode offer = postOffer(orderBody(0L, null), 200);
        assertThat(offer.get("orderTotal").get("amountMinor").asLong()).isEqualTo(80_000L);
        assertThat(offer.get("offer").get("maximum").get("amountMinor").asLong())
                .isEqualTo(30_000L);
        assertThat(offer.get("requested").isNull()).isTrue();
        assertThat(offer.get("cashToCollect").get("amountMinor").asLong())
                .as("no points asked for: the whole total is owed")
                .isEqualTo(80_000L);

        JsonNode withPoints = postOffer(orderBody(10_000L, true), 200);
        assertThat(withPoints.get("requested").get("amountMinor").asLong()).isEqualTo(10_000L);
        assertThat(withPoints.get("cashToCollect").get("amountMinor").asLong()).isEqualTo(70_000L);

        JsonNode tooMuch = postOffer(orderBody(35_000L, true), 200);
        assertThat(tooMuch.get("requestedRefusal").asText()).isEqualTo("EXCEEDS_MAXIMUM");
        assertThat(tooMuch.get("cashToCollect").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.orders")
                        .query(Long.class)
                        .single())
                .as("three offers later there is still no order and no cart")
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.carts WHERE status = 'ACTIVE'")
                        .query(Long.class)
                        .single())
                .isZero();

        // The order, with the redemption and the operator's confirmation that the customer agreed.
        MvcResult placed = mvc.perform(post(ordersUrl())
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "split-place-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(10_000L, true)))
                .andReturn();
        assertThat(placed.getResponse().getStatus())
                .as(placed.getResponse().getContentAsString())
                .isEqualTo(201);
        UUID orderId = UUID.fromString(JSON.readTree(placed.getResponse().getContentAsString())
                .get("orderId")
                .asText());

        // The staff payment[] and the customer's split say the same thing.
        MvcResult staff = mvc.perform(get("/api/v1/operations/tenants/" + TENANT + "/orders/" + orderId + "/payment")
                        .with(tokenFor(OPERATOR)))
                .andReturn();
        assertThat(staff.getResponse().getStatus()).isEqualTo(200);
        JsonNode payment =
                JSON.readTree(staff.getResponse().getContentAsString()).get("payment");
        assertThat(payment).hasSize(2);

        MvcResult split = mvc.perform(get(splitUrl(orderId)).with(customerToken(CUSTOMER_SUBJECT)))
                .andReturn();
        assertThat(split.getResponse().getStatus()).isEqualTo(200);
        assertThat(split.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode body = JSON.readTree(split.getResponse().getContentAsString());
        assertThat(body.get("total").get("amountMinor").asLong()).isEqualTo(80_000L);
        JsonNode tenders = body.get("tenders");
        assertThat(tenders).hasSize(2);
        assertThat(tenders.get(0).get("kind").asText()).isEqualTo("POINTS");
        assertThat(tenders.get(0).get("amount").get("amountMinor").asLong()).isEqualTo(10_000L);
        assertThat(tenders.get(0).get("status").asText()).isEqualTo("RESERVED");
        assertThat(tenders.get(0).get("methodCode").isNull()).isTrue();
        assertThat(tenders.get(1).get("kind").asText()).isEqualTo("MONEY");
        assertThat(tenders.get(1).get("methodCode").asText()).isEqualTo("CASH");
        assertThat(tenders.get(1).get("amount").get("amountMinor").asLong()).isEqualTo(70_000L);
        assertThat(split.getResponse().getContentAsString())
                .as("nothing a staff member would be told for a stated purpose reaches the customer")
                .doesNotContain("refunded")
                .doesNotContain("loyaltyReservation")
                .doesNotContain("intent")
                .doesNotContain("responsibility");

        // The balance the customer reads afterwards is the points less the hold.
        JsonNode after = JSON.readTree(mvc.perform(get(balanceUrl()).with(customerToken(CUSTOMER_SUBJECT)))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(after.get("balance").get("amountMinor").asLong()).isEqualTo(20_000L);

        // And it is the customer's order page alone.
        assertThat(mvc.perform(get(splitUrl(orderId)).with(customerToken(STRANGER_SUBJECT)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(get(splitUrl(UUID.randomUUID())).with(customerToken(CUSTOMER_SUBJECT)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("an operator who spends points without confirming the customer agreed is refused by name, and the "
            + "customer's points are untouched")
    void anOperatorMustConfirmTheCustomerAgreed() throws Exception {
        seedBalance(30_000L);

        MvcResult refused = mvc.perform(post(ordersUrl())
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "split-place-unattested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(10_000L, null)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("REDEMPTION_NEEDS_CUSTOMER_AGREEMENT");
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.orders")
                        .query(Long.class)
                        .single())
                .isZero();
        JsonNode balance = JSON.readTree(mvc.perform(get(balanceUrl()).with(customerToken(CUSTOMER_SUBJECT)))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(balance.get("balance").get("amountMinor").asLong()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("an order paid entirely in cash has no points to show, but its split is still there to read")
    void aCashOnlyOrderHasASingleMoneyTender() throws Exception {
        MvcResult placed = mvc.perform(post(ordersUrl())
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "split-place-cash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(null, null)))
                .andReturn();
        assertThat(placed.getResponse().getStatus())
                .as(placed.getResponse().getContentAsString())
                .isEqualTo(201);
        UUID orderId = UUID.fromString(JSON.readTree(placed.getResponse().getContentAsString())
                .get("orderId")
                .asText());

        JsonNode tenders = JSON.readTree(mvc.perform(get(splitUrl(orderId)).with(customerToken(CUSTOMER_SUBJECT)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("tenders");

        assertThat(tenders).hasSize(1);
        assertThat(tenders.get(0).get("kind").asText()).isEqualTo("MONEY");
        assertThat(tenders.get(0).get("amount").get("amountMinor").asLong()).isEqualTo(80_000L);
    }

    // ---------------------------------------------------------------------------------- helpers

    private JsonNode postOffer(String body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post(ordersUrl() + "/redemption-offer")
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "offer-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Two plovs for the customer on the phone, paid in cash. The two redemption fields are boxed on the wire, so
     * absent is a valid body and an omitted primitive would be a 400.
     */
    private String orderBody(@Nullable Long redeemFromBalanceMinor, @Nullable Boolean attests) {
        StringBuilder body = new StringBuilder("{")
                .append("\"customerAccountId\":\"")
                .append(CUSTOMER)
                .append("\",\"channelCode\":\"STOREFRONT\",\"fulfillmentMode\":\"PICKUP\",")
                .append("\"lines\":[{\"variantId\":\"")
                .append(plovVariant)
                .append("\",\"quantity\":2}],\"paymentMethodCode\":\"CASH\"");
        if (redeemFromBalanceMinor != null) {
            body.append(",\"redeemFromBalanceMinor\":").append(redeemFromBalanceMinor);
        }
        if (attests != null) {
            body.append(",\"operatorAttestsCustomerAgreement\":").append(attests);
        }
        return body.append('}').toString();
    }

    private String balanceUrl() {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/loyalty/balance";
    }

    private String cartOfferUrl(UUID cartId) {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/carts/" + cartId + "/redemption-offer";
    }

    private String splitUrl(UUID orderId) {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/orders/" + orderId + "/payment-split";
    }

    private String ordersUrl() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders";
    }

    private UUID openCart() {
        return tx(() -> carts.create(TENANT, BRAND, LOCATION, "STOREFRONT", FulfillmentMode.PICKUP, CUSTOMER, null))
                .cartId();
    }

    private void putLine(UUID cartId, int quantity) {
        tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                cartId,
                cartStore.find(TENANT, BRAND, cartId).orElseThrow().version(),
                "plov",
                plovVariant,
                new BigDecimal(quantity),
                List.of(),
                List.of(),
                null));
    }

    private UUID pricedCart(int quantity) {
        UUID cart = openCart();
        putLine(cart, quantity);
        tx(() -> carts.price(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartStore.find(TENANT, BRAND, cart).orElseThrow().version()));
        return cart;
    }

    /** Gives the customer a spendable balance the way an operator would: an adjustment with a reason. */
    private void seedBalance(long amountMinor) {
        tx(() -> loyaltyAdjustments.adjust(new LoyaltyAdjustmentService.AdjustmentCommand(
                TENANT,
                BRAND,
                CUSTOMER,
                amountMinor,
                "UZS",
                LoyaltyAdjustmentService.REASON_LEGACY_OPENING_BALANCE,
                "Seeded for the test",
                ActorRef.user("seed-operator", "Seed"),
                "seed-" + UUID.randomUUID(),
                "corr-seed")));
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
                        'ACTIVE', 'test-fixture', 'split tender endpoint test', :validFrom)
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

    /** A realm token as the storefront presents it: the issuer is part of a customer's identity. */
    private static RequestPostProcessor customerToken(String subject) {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject));
    }

    // ------------------------------------------------------------------ seeds

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'split-tender-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        for (UUID account : List.of(CUSTOMER, STRANGER_ACCOUNT)) {
            jdbc.sql("""
                    INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                        identity_policy_version, version)
                    VALUES (:id, :tenantId, 'ACTIVE', 'Customer', 1, 1)
                    """).param("id", account).param("tenantId", TENANT).update();
        }
        linkPrincipal(CUSTOMER, CUSTOMER_SUBJECT);
        linkPrincipal(STRANGER_ACCOUNT, STRANGER_SUBJECT);

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
        jdbc.sql("""
                INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id, fulfillment_mode, enabled)
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
        jdbc.sql("""
                INSERT INTO tenant.location_service_bindings (tenant_id, brand_id, location_id, fulfillment_mode,
                    schedule_id)
                VALUES (:tenantId, :brandId, :locationId, 'PICKUP', :scheduleId)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("scheduleId", scheduleId)
                .update();
    }

    private void linkPrincipal(UUID account, String subject) {
        jdbc.sql("""
                INSERT INTO customer.principal_links (id, tenant_id, customer_account_id, issuer, subject,
                    status, linked_at)
                VALUES (:id, :t, :account, :issuer, :subject, 'ACTIVE', :now)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("account", account)
                .param("issuer", ISSUER)
                .param("subject", subject)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** One plov at 40 000, authored as a product, priced, offered and published. */
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
        UUID productId = UUID.randomUUID();
        plovVariant = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, 'PLOV', 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status, is_default)
                VALUES (:id, :tenantId, :brandId, :productId, 'SKU-PLOV', 'ACTIVE', true)
                """)
                .param("id", plovVariant)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
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
                VALUES (:tenantId, :brandId, 'PRODUCT', :productId, 'uz', 'Osh')
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .update();

        UUID priceBook = UUID.randomUUID();
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
        jdbc.sql("""
                INSERT INTO pricing.prices (id, tenant_id, brand_id, price_book_id, priceable_type,
                    priceable_id, amount_minor, valid_from)
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'VARIANT', :variantId, 40000, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("priceBookId", priceBook)
                .param("variantId", plovVariant)
                .param("from", validFrom)
                .update();
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

        catalogAuthoring.setOffering(TENANT, BRAND, LOCATION, plovVariant, OfferingStatus.AVAILABLE, List.of("PICKUP"));
        var published = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        assertThat(published.status())
                .as(String.valueOf(published.report().blockers()))
                .isEqualTo(PublicationStatus.PUBLISHED);
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

    /** A brand that lets points pay for up to half of an order of at least 10 000, delivery fee excluded. */
    private void seedRedemptionPolicy() {
        jdbc.sql("""
                INSERT INTO loyalty.redemption_policies (id, tenant_id, brand_id,
                    max_share_basis_points, min_order_minor, excludes_delivery_fee,
                    allowed_channels, status, version, valid_from)
                VALUES (:id, :tenantId, :brandId, 5000, 10000, true, '{}', 'ACTIVE', 1, :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("validFrom", Instant.now().minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
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
