package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.ordering.application.CheckoutService;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.DiscountShape;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.PromoCodeDraft;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Batch 14's money bug, over the operator console's own HTTP contract: amending
 * a promo-coded order dropped its discount, so the amount the operator was told
 * to confirm with the customer included the promo the customer had already
 * earned.
 *
 * <p>The order is placed through the real customer path (cart, promo code,
 * pricing, checkout -- the services the storefront controller calls) against the
 * real, fully wired application; everything after that is HTTP: the amendment
 * preview, the customer's confirmation, and the order read-back. The coupon is
 * capped at one redemption and one per customer, so the order's own checkout
 * leaves it exactly full -- the state in which merely presenting the code again
 * reads "limit reached" and silently prices the amendment at full price.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderAmendmentPromoDiscountHttpTests {

    private static final UUID TENANT = UUID.fromString("018fb900-b000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb900-b000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb900-b000-7000-8000-0000000000c1");
    private static final UUID CUSTOMER = UUID.fromString("018fb900-b000-7000-8000-0000000000d1");

    private static final String OPERATOR = "amendment-promo-operator";
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    private PromoCodeAuthoringService promoAuthoring;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID burgerVariant;

    @BeforeEach
    void reset() {
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
                    ordering.cart_lines, ordering.carts CASCADE
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
        grant(OPERATOR, PlatformRole.TENANT_OWNER);
    }

    @Test
    @DisplayName("the amendment preview, the confirmed apply and the order read-back all carry the promo-code "
            + "discount; the order reconciles and the code is not redeemed a second time")
    void anAmendmentOverHttpKeepsThePromoCodeDiscount() throws Exception {
        var coupon = authorPromoCode("HTTP10");
        UUID orderId = placePromoOrder("HTTP10");

        JsonNode placed = orderDetail(orderId);
        assertThat(placed.get("summary").get("discountMinor").asLong()).isEqualTo(10_000L);
        assertThat(placed.get("summary").get("totalMinor").asLong()).isEqualTo(90_000L);
        assertThat(consumedCount(coupon.couponId()))
                .as("the checkout filled the code's only slot")
                .isEqualTo(1);

        // The operator adds one more 50,000 burger. Full price would be +60,000 on a 90,000 order;
        // with the 10% the customer already holds it is 150,000 - 15,000 - 90,000 = +45,000.
        MvcResult proposed = mvc.perform(post(orderPath(orderId) + "/amendments")
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "amend-promo-http-1")
                        .header(
                                "If-Match",
                                "\"" + placed.get("summary").get("version").asInt() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commands":[{"type":"ADD_LINES","lines":[{"variantId":"%s","quantity":1}]}],\
                                "applyImmediately":false,"reasonCode":"OPERATOR_EDIT"}""".formatted(burgerVariant)))
                .andReturn();
        assertThat(proposed.getResponse().getStatus())
                .as(proposed.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode preview = JSON.readTree(proposed.getResponse().getContentAsString());
        assertThat(preview.get("status").asText()).isEqualTo("PRICED");
        assertThat(preview.get("deltaTotalMinor").asLong())
                .as("what the operator is told to confirm with the customer")
                .isEqualTo(45_000L);

        // The customer agrees on the phone; the confirmation applies the amendment.
        MvcResult confirmed = mvc.perform(post(orderPath(orderId) + "/amendments/"
                                + preview.get("amendmentId").asText() + "/confirmation")
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "amend-promo-http-confirm-1")
                        .header(
                                "If-Match",
                                "\"" + preview.get("amendmentVersion").asInt() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"PHONE\"}"))
                .andReturn();
        assertThat(confirmed.getResponse().getStatus())
                .as(confirmed.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(JSON.readTree(confirmed.getResponse().getContentAsString())
                        .get("status")
                        .asText())
                .isEqualTo("APPLIED");

        JsonNode amended = orderDetail(orderId);
        assertThat(amended.get("summary").get("discountMinor").asLong()).isEqualTo(15_000L);
        assertThat(amended.get("summary").get("totalMinor").asLong()).isEqualTo(135_000L);
        long subtotal = amended.get("subtotalMinor").asLong();
        long tax = amended.get("taxMinor").asLong();
        long fee = amended.get("summary").get("feeMinor").asLong();
        assertThat(subtotal
                        + tax
                        + fee
                        - amended.get("summary").get("discountMinor").asLong())
                .as("ck_order_total_reconciles: total = subtotal + tax + fee - discount, gross subtotal")
                .isEqualTo(135_000L);

        assertThat(consumedCount(coupon.couponId()))
                .as("an amendment never takes a second redemption")
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT amount_minor FROM pricing.coupon_redemptions WHERE order_id = :id")
                        .param("id", orderId)
                        .query(Long.class)
                        .single())
                .as("the redemption now stands for the discount the order carries")
                .isEqualTo(15_000L);
    }

    // ---------------------------------------------------------------- helpers

    private uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore.PromoCodeAuthoringRow
            authorPromoCode(String code) {
        var drafted = promoAuthoring.draft(
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
                        1,
                        1,
                        null,
                        null));
        promoAuthoring.activate(TENANT, BRAND, drafted.couponId());
        return drafted;
    }

    /** Two burgers at 50,000 with 10% off, checked out through the customer's own path. */
    private UUID placePromoOrder(String code) {
        UUID cart = tx(() ->
                        carts.create(TENANT, BRAND, LOCATION, "STOREFRONT", FulfillmentMode.PICKUP, CUSTOMER, null))
                .cartId();
        tx(() -> carts.putLine(
                TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), "a", burgerVariant, 2, List.of(), null));
        tx(() -> carts.applyPromoCode(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), code));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));

        var row = cartStore.find(TENANT, BRAND, cart).orElseThrow();
        var result = tx(() -> checkout.checkout(new CheckoutService.CheckoutCommand(
                TENANT,
                BRAND,
                cart,
                row.version(),
                Objects.requireNonNull(row.pricingQuoteId(), "the cart was priced first"),
                Objects.requireNonNull(row.pricingContextHash(), "the cart was priced first"),
                "idem-amend-promo-http",
                "CASH",
                0L,
                "CUSTOMER",
                CUSTOMER.toString(),
                null,
                null,
                false)));
        return Objects.requireNonNull(result.orderId(), "a created checkout always has an order id");
    }

    private int cartVersion(UUID cartId) {
        return cartStore.find(TENANT, BRAND, cartId).orElseThrow().version();
    }

    private int consumedCount(UUID couponId) {
        return jdbc.sql("SELECT consumed_count FROM pricing.coupon_codes WHERE id = :id")
                .param("id", couponId)
                .query(Integer.class)
                .single();
    }

    private String orderPath(UUID orderId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/" + orderId;
    }

    private JsonNode orderDetail(UUID orderId) throws Exception {
        MvcResult result =
                mvc.perform(get(orderPath(orderId)).with(tokenFor(OPERATOR))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private void tx(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'amendment promo endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", role.scopeType().name())
                .param("scopeId", TENANT)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
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
                VALUES (:id, 'amendment-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
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

        // ADR 0036's channel payment matrix. Every checkout in this class pays
        // with CASH (placeOrder, below), and CheckoutEligibilityGuard now checks
        // the matrix as well as the merchant account, so the row must exist
        // before a single test method runs — V0175 points the matrix at this
        // registry row by foreign key, so it goes first.
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
        for (FulfillmentMode mode : List.of(FulfillmentMode.PICKUP, FulfillmentMode.DELIVERY)) {
            jdbc.sql("""
                    INSERT INTO tenant.location_service_bindings (tenant_id, brand_id,
                        location_id, fulfillment_mode, schedule_id)
                    VALUES (:tenantId, :brandId, :locationId, :mode, :scheduleId)
                    """)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("locationId", LOCATION)
                    .param("mode", mode.name())
                    .param("scheduleId", scheduleId)
                    .update();
        }

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        UUID productId = UUID.randomUUID();
        burgerVariant = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, 'BURGER', 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, 'SKU-BURGER', 'ACTIVE')
                """)
                .param("id", burgerVariant)
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
                INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id,
                    locale, name)
                VALUES (:tenantId, :brandId, 'PRODUCT', :productId, 'uz', 'Qo''y burger')
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash',
                    now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
    }

    private void seedPricingAndStock() {
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
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'VARIANT', :variantId, 50000, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("priceBookId", priceBook)
                .param("variantId", burgerVariant)
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

        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, burgerVariant, TrackingMode.BINARY);
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
