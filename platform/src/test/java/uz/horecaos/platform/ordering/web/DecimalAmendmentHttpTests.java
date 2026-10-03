package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService;
import uz.horecaos.platform.catalog.application.PhysicalAttributesAuthoringService;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.PhysicalAttributes;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.kitchen.application.KitchenTicketService;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.ordering.application.CheckoutService;
import uz.horecaos.platform.ordering.application.OrderStateService;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.pos.infrastructure.ordering.JdbcPosOrderSource;
import uz.horecaos.platform.pricing.application.PromotionAuthoringService;
import uz.horecaos.platform.reporting.application.DayCloseService;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcClassificationStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0137 through ADR 0039: a splittable dish is amended by the portion.
 *
 * <p>An order already placed holds a plov; the operator raises it to one and a half portions, adds
 * half a portion of another, and is refused a fraction of a can of soda -- all through the
 * console's own JSON against the real, fully wired application, with the same published menu the
 * cart reads. The cart's rule and the amendment's rule are one rule ({@code
 * CartMenuRules.PhysicalRules#refusalOf}); what this proves is that an amendment cannot be a way
 * round it, and that a half portion survives pricing, reservation, the revision and the read-back.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DecimalAmendmentHttpTests {

    private static final UUID TENANT = UUID.fromString("018fc100-c000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fc100-c000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018fc100-c000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018fc100-c000-7000-8000-0000000000b2");
    private static final UUID LOCATION = UUID.fromString("018fc100-c000-7000-8000-0000000000c1");
    private static final UUID CUSTOMER = UUID.fromString("018fc100-c000-7000-8000-0000000000d1");

    private static final String OPERATOR = "amend-operator";
    private static final String OUTSIDER = "amend-outsider";
    private static final String ACTOR_SUBJECT = "amend-author";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

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

    // --------------------------------------------------------------- the amendment

    @Test
    @DisplayName("a plov is raised to one and a half portions: previewed, confirmed and applied, with the fraction "
            + "priced, written and read back, and the old line closed")
    void aSplittableLineIsAmendedByThePortion() throws Exception {
        UUID orderId = placeOrder(Map.of("plov", plovVariant), "dec-amend-order-1");
        UUID lineId = lineIdOf(orderId, plovVariant);
        assertThat(totalOf(orderId)).isEqualTo(40_000L);

        JsonNode preview = amend(orderId, "dec-amend-1", changeQuantity(lineId, "1.5"), 200);

        assertThat(preview.get("status").asText()).isEqualTo("PRICED");
        assertThat(preview.get("deltaTotalMinor").asLong())
                .as("half a portion of a 40,000 plov")
                .isEqualTo(20_000L);
        confirm(orderId, preview);

        assertThat(totalOf(orderId)).isEqualTo(60_000L);
        assertThat(jdbc.sql("SELECT quantity FROM ordering.order_lines "
                                + "WHERE order_id = :id AND revision_to IS NULL")
                        .param("id", orderId)
                        .query(BigDecimal.class)
                        .single())
                .as("the live line holds the fraction, not a rounded whole")
                .isEqualByComparingTo("1.5");
        assertThat(jdbc.sql("SELECT quantity FROM ordering.order_lines WHERE id = :id")
                        .param("id", lineId)
                        .query(BigDecimal.class)
                        .single())
                .as("the old line is closed, never edited")
                .isEqualByComparingTo("1");
        JsonNode line = orderDetail(orderId).get("lines").get(0);
        assertThat(line.get("quantity").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(line.get("finalAmountMinor").asLong()).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("a whole quantity sent as a bare integer still amends, exactly as before quantities were decimal")
    void aWholeQuantityStillAmends() throws Exception {
        UUID orderId = placeOrder(Map.of("soda", sodaVariant), "dec-amend-order-2");
        UUID lineId = lineIdOf(orderId, sodaVariant);

        JsonNode preview = amend(orderId, "dec-amend-2", changeQuantity(lineId, "3"), 200);

        assertThat(preview.get("deltaTotalMinor").asLong()).isEqualTo(10_000L);
        confirm(orderId, preview);
        assertThat(orderDetail(orderId).get("lines").get(0).get("quantity").asInt())
                .isEqualTo(3);
        assertThat(jdbc.sql("SELECT payload_json FROM ordering.order_amendment_commands " + "WHERE amendment_id = :id")
                        .param("id", UUID.fromString(preview.get("amendmentId").asText()))
                        .query(String.class)
                        .single())
                .as("the stored command reads as it did when the quantity was an integer")
                .contains("\"quantity\":3}");
    }

    @Test
    @DisplayName("the amendment names the rule the cart names: a fraction of a can, a fraction off the portion step, "
            + "and a quantity the column cannot hold are each refused, and nothing is written")
    void anAmendmentCannotBeAWayRoundThePortionRule() throws Exception {
        UUID orderId =
                placeOrder(Map.of("plov", plovVariant, "soda", sodaVariant, "cake", cakeVariant), "dec-amend-order-3");
        UUID plov = lineIdOf(orderId, plovVariant);
        UUID soda = lineIdOf(orderId, sodaVariant);
        UUID cake = lineIdOf(orderId, cakeVariant);

        assertThat(refusalCode(orderId, "dec-refuse-1", changeQuantity(soda, "1.5")))
                .as("a can of soda has no physical block")
                .isEqualTo("FRACTIONAL_QUANTITY_NOT_ALLOWED");
        assertThat(refusalCode(orderId, "dec-refuse-2", changeQuantity(cake, "1.5")))
                .as("a cake sold by weight is not splittable")
                .isEqualTo("FRACTIONAL_QUANTITY_NOT_ALLOWED");
        assertThat(refusalCode(orderId, "dec-refuse-3", changeQuantity(plov, "1.3")))
                .as("1.3 is not a whole number of 0.5 portions")
                .isEqualTo("QUANTITY_NOT_A_PORTION");
        assertThat(refusalCode(orderId, "dec-refuse-4", changeQuantity(plov, "2.0001")))
                .as("the column holds three fraction digits")
                .isIn("QUANTITY_NOT_A_PORTION", "QUANTITY_OUT_OF_RANGE");
        assertThat(amendmentCount(orderId))
                .as("a refused propose leaves no amendment row")
                .isZero();
        assertThat(totalOf(orderId)).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("a refusal over the line step carries the step in its message, so the operator is told what to type")
    void theRefusalNamesThePortionStep() throws Exception {
        UUID orderId = placeOrder(Map.of("plov", plovVariant), "dec-amend-order-4");
        UUID plov = lineIdOf(orderId, plovVariant);

        JsonNode refused = amend(orderId, "dec-step-1", changeQuantity(plov, "1.2"), 409);

        assertThat(refused.get("detail").asText()).contains("0.5");
    }

    @Test
    @DisplayName("a quantity that is no larger than the line's is not an increase, whatever its precision")
    void aDecimalCannotShrinkALineEither() throws Exception {
        UUID orderId = placeOrder(Map.of("plov", plovVariant), "dec-amend-order-5");
        UUID plov = lineIdOf(orderId, plovVariant);
        JsonNode raised = amend(orderId, "dec-down-1", changeQuantity(plov, "2.5"), 200);
        confirm(orderId, raised);

        assertThat(refusalCode(orderId, "dec-down-2", changeQuantity(plov, "1.5")))
                .isEqualTo("QUANTITY_DECREASE_NOT_SUPPORTED");
        assertThat(refusalCode(orderId, "dec-down-3", changeQuantity(plov, "2.500")))
                .isEqualTo("QUANTITY_DECREASE_NOT_SUPPORTED");
    }

    @Test
    @DisplayName("half a portion can be added to an order, and half a can cannot")
    void addingLinesFollowsTheSameRule() throws Exception {
        UUID orderId = placeOrder(Map.of("soda", sodaVariant), "dec-amend-order-6");

        assertThat(refusalCode(orderId, "dec-add-1", addLine(sodaVariant, "0.5")))
                .isEqualTo("FRACTIONAL_QUANTITY_NOT_ALLOWED");
        JsonNode preview = amend(orderId, "dec-add-2", addLine(plovVariant, "0.5"), 200);
        assertThat(preview.get("deltaTotalMinor").asLong()).isEqualTo(20_000L);
        confirm(orderId, preview);

        assertThat(jdbc.sql("SELECT quantity FROM ordering.order_lines "
                                + "WHERE order_id = :id AND revision_to IS NULL AND source_variant_id = :variant")
                        .param("id", orderId)
                        .param("variant", plovVariant)
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("the order read carries the step a line may be amended in, for a splittable dish only")
    void theOrderReadCarriesThePortionStep() throws Exception {
        UUID orderId = placeOrder(Map.of("plov", plovVariant, "soda", sodaVariant), "dec-amend-order-7");

        JsonNode lines = orderDetail(orderId).get("lines");

        for (JsonNode line : lines) {
            if (line.get("productName").asText().contains("Osh")) {
                assertThat(line.get("portionSize").decimalValue()).isEqualByComparingTo("0.5");
            } else {
                assertThat(line.path("portionSize").isNull()
                                || line.path("portionSize").isMissingNode())
                        .as("a can is sold in whole units: %s", line)
                        .isTrue();
            }
        }
    }

    // -------------------------------------------------------------- the console's call

    private JsonNode amend(UUID orderId, String key, String command, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post(orderPath(orderId) + "/amendments")
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key)
                        .header("If-Match", "\"" + version(orderId) + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commands":[%s],"applyImmediately":false,"reasonCode":"OPERATOR_EDIT"}""".formatted(command)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private String refusalCode(UUID orderId, String key, String command) throws Exception {
        JsonNode refused = amend(orderId, key, command, 409);
        return refused.path("reasonCode").asText(refused.toString());
    }

    private void confirm(UUID orderId, JsonNode preview) throws Exception {
        MvcResult confirmed = mvc.perform(post(orderPath(orderId) + "/amendments/"
                                + preview.get("amendmentId").asText() + "/confirmation")
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "confirm-" + UUID.randomUUID())
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
    }

    private static String changeQuantity(UUID lineId, String quantity) {
        return """
                {"type":"CHANGE_LINE_QUANTITY","orderLineId":"%s","quantity":%s}""".formatted(lineId, quantity);
    }

    private static String addLine(UUID variantId, String quantity) {
        return """
                {"type":"ADD_LINES","lines":[{"variantId":"%s","quantity":%s}]}""".formatted(variantId, quantity);
    }

    private int amendmentCount(UUID orderId) {
        return jdbc.sql("SELECT count(*) FROM ordering.order_amendments WHERE order_id = :id")
                .param("id", orderId)
                .query(Integer.class)
                .single();
    }

    private long totalOf(UUID orderId) throws Exception {
        return orderDetail(orderId).get("summary").get("totalMinor").asLong();
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
        var result = checkoutResult(cart, idempotencyKey, "CASH");
        return Objects.requireNonNull(result.orderId(), "a created checkout always has an order id");
    }

    private CheckoutService.CheckoutResult checkoutResult(UUID cart, String idempotencyKey, String paymentMethod) {
        var row = cartStore.find(TENANT, BRAND, cart).orElseThrow();
        return tx(() -> checkout.checkout(new CheckoutService.CheckoutCommand(
                TENANT,
                BRAND,
                cart,
                row.version(),
                Objects.requireNonNull(row.pricingQuoteId(), "the cart was priced first"),
                Objects.requireNonNull(row.pricingContextHash(), "the cart was priced first"),
                idempotencyKey,
                paymentMethod,
                0L,
                "CUSTOMER",
                CUSTOMER.toString(),
                null,
                null,
                false)));
    }

    private int version(UUID orderId) throws Exception {
        return orderDetail(orderId).get("summary").get("version").asInt();
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

    /** 10% off the goods from 170,000 up, which also keeps the order from earning points. */
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
                        'ACTIVE', 'test-fixture', 'decimal amendment endpoint test', :validFrom)
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
                VALUES (:id, 'decimal-amend-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'decimal-amend-http-other', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
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
