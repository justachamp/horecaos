package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.catalog.api.PackageCodeLookup;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.AttachmentPolicy;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.NewComboGroup;
import uz.horecaos.platform.catalog.application.PhysicalAttributesAuthoringService;
import uz.horecaos.platform.catalog.domain.CompositeProducts.AttachmentOwnerType;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboComponent;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboGroup;
import uz.horecaos.platform.catalog.domain.CompositeProducts.Visibility;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.PhysicalAttributes;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
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
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderEntityMappingLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.kitchen.application.KitchenStationService;
import uz.horecaos.platform.kitchen.application.KitchenStationService.NewRoutingRule;
import uz.horecaos.platform.kitchen.application.KitchenStationService.NewStation;
import uz.horecaos.platform.kitchen.application.KitchenTicketService;
import uz.horecaos.platform.kitchen.domain.ReleaseMode;
import uz.horecaos.platform.kitchen.domain.StationRole;
import uz.horecaos.platform.kitchen.domain.TicketItemStatus;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketItemRow;
import uz.horecaos.platform.ordering.api.CustomerBotOrderingPort;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.ordering.application.CheckoutService;
import uz.horecaos.platform.ordering.application.CustomerBotOrderingAdapter;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.application.OrderStateService;
import uz.horecaos.platform.ordering.application.ReorderPlanService;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.pos.FakePosAdapter;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.PosAdapterRegistry;
import uz.horecaos.platform.pos.application.PosOrderExportService;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.application.port.PosOrderSource;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosCapabilityStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore;
import uz.horecaos.platform.reporting.application.DayCloseService;
import uz.horecaos.platform.reporting.application.ReportQueryService;
import uz.horecaos.platform.support.RecordingProviderActivityRecorder;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0136's order flow, end to end on the real application: a combo goes from the cart
 * through checkout into an order, a kitchen ticket and a till, and an operator can amend it
 * afterwards.
 *
 * <p>Everything a customer or an operator would do is driven through the services and
 * endpoints they use, over the real wiring. The catalog is authored through the authoring
 * services and published through the publication service, so the cart reads a menu a
 * publication wrote; the kitchen ticket is opened by the real {@code OrderConfirmed} listener
 * inside the checkout's own transaction, not constructed by the test. Where a collaborator
 * has to be a double it is the till: {@link FakePosAdapter} stands in for a vendor, and
 * everything that decides what it is sent -- the order source, the package-code lookup, the
 * export service -- is the production class reading the production tables.
 *
 * <p>What would still pass if the order flow ignored combos: the totals, if components were
 * priced at their standalone price (the burger is {@value #BURGER} alone and {@value
 * #BURGER_IN_LUNCH} in the box, so every total below distinguishes the two), and the kitchen
 * routing, which resolves each component by its own variant either way. What would not: the
 * shared selection, the container's name, the combo quantity, the hidden row and the repeat.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class ComboOrderFlowEndToEndTests {

    private static final UUID TENANT = UUID.fromString("018fb900-d000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb900-d000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb900-d000-7000-8000-0000000000c1");
    private static final UUID CUSTOMER = UUID.fromString("018fb900-d000-7000-8000-0000000000d1");
    private static final UUID ACTOR = UUID.fromString("018fb900-d000-7000-8000-0000000000e1");
    private static final UUID POS_INSTALLATION = UUID.fromString("018fb900-d000-7000-8000-0000000000f1");
    private static final UUID POS_BINDING = UUID.fromString("018fb900-d000-7000-8000-0000000000f2");

    private static final String LOCALE = "uz";
    private static final String OPERATOR = "combo-flow-operator";
    private static final String TESTER = "combo-flow-tester";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    /** What each dish costs on its own, and inside the lunch box, in som. */
    static final long BURGER = 30_000L;

    static final long BURGER_IN_LUNCH = 25_000L;
    private static final long WRAP_IN_LUNCH = 24_000L;
    private static final long COLA_IN_LUNCH = 3_000L;
    private static final long COLA = 5_000L;
    private static final long SALAD = 20_000L;
    private static final long BOX = 2_000L;
    private static final long CHILI = 1_000L;
    private static final long HOT = 500L;

    /** A fish sold by weight: priced per 100 g, quoted at 1,000 g, with a packing charge in every mode. */
    private static final long FISH_PER_QUANTUM = 2_000L;

    private static final long PACK = 1_500L;

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the combo order flow");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // Realtime signals are published to a broker that is not there; the default waits ten
        // seconds per signal for it, and a checkout emits several.
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "300");
        // A delivery order's address is envelope-encrypted (ADR 0029).
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
    private JdbcOrderStore orderStore;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private CatalogAuthoringService authoring;

    @Autowired
    private CompositeProductAuthoringService composites;

    @Autowired
    private PhysicalAttributesAuthoringService physical;

    @Autowired
    private CatalogPublicationService publication;

    @Autowired
    private KitchenStationService stations;

    @Autowired
    private KitchenTicketService tickets;

    @Autowired
    private FieldProtection protection;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderQueryService orderQuery;

    @Autowired
    private ReorderPlanService reorderPlans;

    @Autowired
    private CustomerBotOrderingAdapter bot;

    @Autowired
    private PosOrderSource posOrderSource;

    @Autowired
    private OrderStateService orderStates;

    @Autowired
    private DayCloseService dayClose;

    @Autowired
    private ReportQueryService reportQueries;

    @Autowired
    private PackageCodeLookup packageCodes;

    @Autowired
    private JdbcPosExportStore posExportStore;

    @Autowired
    private JdbcPosBindingConfiguration posBindingConfiguration;

    @Autowired
    private JdbcPosCapabilityStore posCapabilityStore;

    // ----------------------------------------------------------- the menu

    private UUID catalogId;
    private UUID lunchVariant;
    private UUID burgerVariant;
    private UUID wrapVariant;
    private UUID colaVariant;
    private UUID saladVariant;
    private UUID saladProduct;
    private UUID fishVariant;
    private UUID packOption;
    private UUID grill;
    private UUID bar;
    private ComboGroup mainGroup;
    private ComboGroup drinkGroup;
    private ComboComponent burgerInLunch;
    private ComboComponent wrapInLunch;
    private ComboComponent colaInLunch;
    private UUID boxOption;
    private UUID chiliOption;
    private UUID hotOption;
    private UUID mildOption;
    private UUID priceBook;
    private UUID addressId;

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
                    ordering.order_line_comment_presets, ordering.order_line_modifiers,
                    ordering.order_lines, ordering.bulk_operation_items, ordering.bulk_operations,
                    ordering.orders, ordering.order_number_counters, ordering.checkout_attempts,
                    ordering.cart_lines, ordering.carts CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE kitchen.ticket_events, kitchen.ticket_items, kitchen.tickets,
                    kitchen.location_routing_rules, kitchen.brand_routing_rules,
                    kitchen.station_capacity, kitchen.stations CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE integration.pos_export_attempts, integration.pos_order_exports,
                    integration.bindings, integration.installations CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE pricing.quote_adjustments, pricing.quote_lines, pricing.quotes,
                    pricing.prices, pricing.price_book_assignments, pricing.price_books,
                    pricing.tax_profiles CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE inventory.reservation_lines, inventory.reservations,
                    inventory.movements, inventory.positions, inventory.stock_items CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE catalog.publication_items, catalog.publications,
                    catalog.location_offerings, catalog.item_sale_windows, catalog.media_relations, catalog.translations,
                    catalog.combo_components, catalog.combo_groups,
                    catalog.product_modifier_groups, catalog.variant_modifier_groups,
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
        seedStock();
        seedKitchen();
        grant(OPERATOR, PlatformRole.TENANT_OWNER);
    }

    // ===================================================================== the round trip

    @Test
    @DisplayName("a combo goes cart -> order -> kitchen ticket -> till, with every component priced at its combo price")
    void aComboGoesFromTheCartToTheTill() throws Exception {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "lunch", lunchVariant, 2, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));

        CartService.CartView view = carts.view(TENANT, BRAND, CUSTOMER, cart).orElseThrow();
        assertThat(view.selectionsOf("lunch").comboPicks())
                .as("the cart remembers what the customer picked inside the combo")
                .containsExactlyInAnyOrder(
                        new CartService.ComboPick(burgerInLunch.id(), 1),
                        new CartService.ComboPick(colaInLunch.id(), 1));

        var priced = tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        assertThat(priced.quote().totalMinor())
                .as("2 boxes of (burger 25,000 + cola 3,000); the burger alone would be 30,000")
                .isEqualTo(2 * (BURGER_IN_LUNCH + COLA_IN_LUNCH));
        assertThat(priced.quote().lines()).hasSize(2);

        UUID orderId = checkOut(cart);

        // -- the order: ordinary lines sharing one selection, and nothing for the container.
        List<OrderLine> lines = orderLines(orderId);
        assertThat(lines).hasSize(2);
        assertThat(lines)
                .extracting(OrderLine::variantId)
                .as("the components, in the order the combo lists them -- never the container")
                .containsExactly(burgerVariant, colaVariant);
        assertThat(lines).extracting(OrderLine::quantity).containsExactly(2, 2);
        assertThat(lines).extracting(OrderLine::unitAmountMinor).containsExactly(BURGER_IN_LUNCH, COLA_IN_LUNCH);
        assertThat(lines)
                .extracting(OrderLine::finalAmountMinor)
                .containsExactly(2 * BURGER_IN_LUNCH, 2 * COLA_IN_LUNCH);
        UUID selection = Objects.requireNonNull(lines.get(0).selectionId());
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.selectionId()).isEqualTo(selection);
            assertThat(line.containerVariantId()).isEqualTo(lunchVariant);
            assertThat(line.comboName()).as("the name the combo was sold under").isEqualTo("Lunch box");
            assertThat(line.comboQuantity()).isEqualTo(2);
            assertThat(line.pickQuantity()).isEqualTo(1);
        });
        assertThat(lines).extracting(OrderLine::componentId).containsExactly(burgerInLunch.id(), colaInLunch.id());
        assertThat(jdbc.sql("SELECT total_minor FROM ordering.orders WHERE id = :id")
                        .param("id", orderId)
                        .query(Long.class)
                        .single())
                .as("the order total is the components' sum, which ck_order_total_reconciles accepted unchanged")
                .isEqualTo(2 * (BURGER_IN_LUNCH + COLA_IN_LUNCH));

        // -- the operator's read of the order names the combo on each component.
        JsonNode detail = orderDetail(orderId);
        JsonNode firstLine = detail.get("lines").get(0);
        assertThat(firstLine.get("combo").get("name").asText()).isEqualTo("Lunch box");
        assertThat(firstLine.get("combo").get("quantity").asInt()).isEqualTo(2);
        assertThat(firstLine.get("combo").get("selectionId").asText()).isEqualTo(selection.toString());
        assertThat(detail.get("lines").get(1).get("combo").get("selectionId").asText())
                .isEqualTo(selection.toString());

        // -- inventory: the components were held, the container was not.
        assertThat(reservedUnitsByVariant(orderId))
                .as("ADR 0017: stock is held on what the kitchen makes, in the units ordered")
                .containsOnly(Map.entry(burgerVariant, 2), Map.entry(colaVariant, 2));

        // -- kitchen: the real OrderConfirmed listener opened the ticket; each component was routed
        // by its own variant and carries the combo it belongs to.
        var ticket = tickets.byOrder(TENANT, orderId).orElseThrow();
        List<TicketItemRow> items = tickets.items(TENANT, ticket.id());
        assertThat(items).hasSize(2);
        TicketItemRow burgerItem = itemFor(items, lines.get(0));
        TicketItemRow colaItem = itemFor(items, lines.get(1));
        assertThat(burgerItem.stationId()).as("the burger goes to the grill").isEqualTo(grill);
        assertThat(colaItem.stationId()).as("the cola goes to the bar").isEqualTo(bar);
        assertThat(items).extracting(TicketItemRow::comboSelectionId).containsOnly(selection);
        assertThat(items).extracting(TicketItemRow::comboContainerVariantId).containsOnly(lunchVariant);
        assertThat(burgerItem.quantity()).isEqualByComparingTo("2");

        // -- the kitchen screens read the grouping key over HTTP, and never a name (ADR 0041).
        String kitchen = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/kitchen";
        JsonNode board = kitchenRead(kitchen + "/tickets/" + ticket.id());
        assertThat(board.get("items")).hasSize(2);
        board.get("items").forEach(item -> {
            assertThat(item.get("comboSelectionId").asText()).isEqualTo(selection.toString());
            assertThat(item.get("comboContainerVariantId").asText()).isEqualTo(lunchVariant.toString());
            assertThat(item.has("name"))
                    .as("a kitchen row carries no dish name")
                    .isFalse();
        });
        JsonNode wall = kitchenRead(kitchen + "/vdu");
        assertThat(wall.get("tickets").get(0).get("items"))
                .extracting(item -> item.get("comboSelectionId").asText())
                .containsOnly(selection.toString());

        // -- the till: two ordinary lines, each its own dish, one grouping key; the combo is not a line.
        PosAdapter.OrderExport exported = exportToTheTill(orderId, Set.of(lunchVariant));
        assertThat(exported.lines()).hasSize(2);
        assertThat(exported.lines())
                .extracting(PosAdapter.OrderExport.Line::externalProductId)
                .containsExactly("ext-" + burgerVariant, "ext-" + colaVariant);
        assertThat(exported.lines())
                .extracting(PosAdapter.OrderExport.Line::unitAmountMinor)
                .containsExactly(BURGER_IN_LUNCH, COLA_IN_LUNCH);
        assertThat(exported.lines())
                .extracting(PosAdapter.OrderExport.Line::packageCode)
                .as("ADR 0038: each component carries its own classification, resolved through its own variant")
                .containsExactly("PKG-BURGER", "PKG-COLA");
        assertThat(exported.lines())
                .extracting(PosAdapter.OrderExport.Line::comboId)
                .containsOnly(selection.toString());
        assertThat(exported.lines())
                .extracting(PosAdapter.OrderExport.Line::comboName)
                .containsOnly("Lunch box");
        assertThat(exported.lines())
                .extracting(PosAdapter.OrderExport.Line::comboExternalProductId)
                .as("the combo was not mapped on the till, and that refuses nothing: it is not a line")
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("a mapped combo is named to the till; an unmapped component refuses the export as LINE_UNMAPPED")
    void theTillMapsTheComponentsAndOptionallyTheCombo() throws Exception {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "lunch", lunchVariant, 1, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);

        PosAdapter.OrderExport mapped = exportToTheTill(orderId, Set.of());
        assertThat(mapped.lines())
                .extracting(PosAdapter.OrderExport.Line::comboExternalProductId)
                .as("a combo the till knows is named by the till's own id")
                .containsOnly("ext-" + lunchVariant);

        // Another order, with the drink never mapped on the till.
        UUID otherCart = openCart(FulfillmentMode.PICKUP);
        put(otherCart, "lunch", lunchVariant, 1, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, otherCart, cartVersion(otherCart)));
        UUID otherOrder = checkOut(otherCart);
        var refused = openAndSend(otherOrder, new Mappings(Set.of(colaVariant)), spy(new FakePosAdapter()));
        assertThat(refused.error())
                .as("an unmapped component is an unmapped line, the path ADR 0012 already has")
                .isEqualTo("LINE_UNMAPPED");
    }

    // ============================================================= the HTTP call sites

    @Test
    @DisplayName(
            "a customer puts a combo over the storefront API and reads the order and the repeat back with the combo")
    void aCustomerDrivesAComboOverTheStorefrontApi() throws Exception {
        linkCustomerPrincipal();
        UUID cart = openCart(FulfillmentMode.PICKUP);
        String base = "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND;

        // A group left unanswered is refused by name, with the cart untouched.
        MvcResult incomplete = mvc.perform(MockMvcRequestBuilders.put(base + "/carts/" + cart + "/lines/lunch")
                        .with(customerToken())
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "put-incomplete")
                        .header("If-Match", "\"" + cartVersion(cart) + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantId":"%s","quantity":1,"comboPicks":[{"componentId":"%s"}]}""".formatted(lunchVariant, burgerInLunch.id())))
                .andReturn();
        assertThat(incomplete.getResponse().getStatus())
                .as(incomplete.getResponse().getContentAsString())
                .isBetween(400, 499);
        assertThat(incomplete.getResponse().getContentAsString()).contains("COMBO_GROUP_MINIMUM_NOT_MET");

        // The console's JSON: the pick quantity omitted, the nested list omitted.
        MvcResult put = mvc.perform(MockMvcRequestBuilders.put(base + "/carts/" + cart + "/lines/lunch")
                        .with(customerToken())
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "put-complete")
                        .header("If-Match", "\"" + cartVersion(cart) + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantId":"%s","quantity":2,"comboPicks":[{"componentId":"%s"},{"componentId":"%s"}]}""".formatted(lunchVariant, burgerInLunch.id(), colaInLunch.id())))
                .andReturn();
        assertThat(put.getResponse().getStatus())
                .as(put.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode line = JSON.readTree(put.getResponse().getContentAsString())
                .get("lines")
                .get(0);
        assertThat(line.get("variantId").asText()).isEqualTo(lunchVariant.toString());
        assertThat(line.get("comboPicks")).hasSize(2);
        assertThat(line.get("comboPicks").get(0).get("quantity").asInt()).isEqualTo(1);

        MvcResult priced = mvc.perform(post(base + "/carts/" + cart + "/pricing")
                        .with(customerToken())
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "price-complete")
                        .header("If-Match", "\"" + cartVersion(cart) + "\""))
                .andReturn();
        assertThat(priced.getResponse().getStatus())
                .as(priced.getResponse().getContentAsString())
                .isEqualTo(200);

        UUID orderId = checkOut(cart);

        MvcResult read = mvc.perform(get(base + "/orders/" + orderId).with(customerToken()))
                .andReturn();
        assertThat(read.getResponse().getStatus())
                .as(read.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode order = JSON.readTree(read.getResponse().getContentAsString());
        JsonNode lines = order.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("comboName").asText()).isEqualTo("Lunch box");
        assertThat(lines.get(0).get("comboSelectionId").asText())
                .isEqualTo(lines.get(1).get("comboSelectionId").asText());

        offer(lunchVariant);
        offer(burgerVariant);
        offer(colaVariant);
        MvcResult repeat = mvc.perform(
                        get(base + "/orders/" + orderId + "/reorder").with(customerToken()))
                .andReturn();
        assertThat(repeat.getResponse().getStatus())
                .as(repeat.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode planned =
                JSON.readTree(repeat.getResponse().getContentAsString()).get("lines");
        assertThat(planned).as("one combo to repeat, not its components").hasSize(1);
        assertThat(planned.get(0).get("variantId").asText()).isEqualTo(lunchVariant.toString());
        assertThat(planned.get(0).get("comboPicks")).hasSize(2);
    }

    @Test
    @DisplayName(
            "an operator takes a combo order by phone: each component is a line, and an incomplete combo is refused")
    void anOperatorTakesAComboOrderByPhone() throws Exception {
        String ordersPath = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders";
        String body = """
                {"customerAccountId":"%s","channelCode":"STOREFRONT","fulfillmentMode":"PICKUP",
                 "paymentMethodCode":"CASH",
                 "lines":[{"variantId":"%s","quantity":3,"comboPicks":[%s]}]}""";
        String burgerOnly = "{\"componentId\":\"" + burgerInLunch.id() + "\"}";
        String burgerAndCola = burgerOnly + ",{\"componentId\":\"" + colaInLunch.id() + "\"}";

        MvcResult incomplete = mvc.perform(post(ordersPath)
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "phone-incomplete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(CUSTOMER, lunchVariant, burgerOnly)))
                .andReturn();
        assertThat(incomplete.getResponse().getStatus())
                .as(incomplete.getResponse().getContentAsString())
                .isBetween(400, 499);
        assertThat(incomplete.getResponse().getContentAsString()).contains("COMBO_GROUP_MINIMUM_NOT_MET");

        MvcResult placed = mvc.perform(post(ordersPath)
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "phone-complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(CUSTOMER, lunchVariant, burgerAndCola)))
                .andReturn();
        assertThat(placed.getResponse().getStatus())
                .as(placed.getResponse().getContentAsString())
                .isEqualTo(201);
        UUID orderId = UUID.fromString(JSON.readTree(placed.getResponse().getContentAsString())
                .get("orderId")
                .asText());

        List<OrderLine> lines = orderLines(orderId);
        assertThat(lines).extracting(OrderLine::variantId).containsExactly(burgerVariant, colaVariant);
        assertThat(lines).extracting(OrderLine::quantity).containsExactly(3, 3);
        assertThat(lines)
                .extracting(OrderLine::selectionId)
                .doesNotContainNull()
                .containsOnly(lines.get(0).selectionId());
        assertThat(totalOf(orderId)).isEqualTo(3 * (BURGER_IN_LUNCH + COLA_IN_LUNCH));
        assertThat(lines.get(0).comboName()).isEqualTo("Lunch box");
        assertThat(lines.get(0).comboQuantity()).isEqualTo(3);
    }

    // ===================================================================== the cart

    @Test
    @DisplayName("a combo line is added, changed and removed with its picks; an incomplete one is refused by name")
    void theCartTakesAComboLineAndRefusesAnIncompleteOne() {
        UUID cart = openCart(FulfillmentMode.PICKUP);

        put(cart, "lunch", lunchVariant, 1, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));
        var priced = tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        assertThat(priced.quote().totalMinor()).isEqualTo(BURGER_IN_LUNCH + COLA_IN_LUNCH);
        assertThat(cartStore.find(TENANT, BRAND, cart).orElseThrow().pricingQuoteId())
                .as("priced")
                .isNotNull();

        // Changing the picks is putting the line again; the price is cleared, as for any edit.
        put(cart, "lunch", lunchVariant, 1, List.of(pick(wrapInLunch, 1), pick(colaInLunch, 1)));
        assertThat(cartStore.find(TENANT, BRAND, cart).orElseThrow().pricingQuoteId())
                .as("an edit clears the attached price")
                .isNull();
        assertThat(carts.view(TENANT, BRAND, CUSTOMER, cart)
                        .orElseThrow()
                        .selectionsOf("lunch")
                        .comboPicks())
                .extracting(CartService.ComboPick::componentId)
                .containsExactlyInAnyOrder(wrapInLunch.id(), colaInLunch.id());
        assertThat(tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)))
                        .quote()
                        .totalMinor())
                .isEqualTo(WRAP_IN_LUNCH + COLA_IN_LUNCH);

        // Refusals, each with the rule's own code.
        assertThat(refusalOf(cart, "x", lunchVariant, List.of(pick(colaInLunch, 1))))
                .as("the main group needs a pick")
                .isEqualTo("COMBO_GROUP_MINIMUM_NOT_MET");
        assertThat(refusalOf(
                        cart,
                        "x",
                        lunchVariant,
                        List.of(pick(burgerInLunch, 1), pick(wrapInLunch, 1), pick(colaInLunch, 1))))
                .as("the main group takes one")
                .isEqualTo("COMBO_GROUP_MAXIMUM_EXCEEDED");
        assertThat(refusalOf(cart, "x", lunchVariant, List.of()))
                .as("the container is never sold on its own")
                .isEqualTo("COMBO_SELECTION_REQUIRED");
        assertThat(refusalOf(
                        cart,
                        "x",
                        lunchVariant,
                        List.of(pick(burgerInLunch, 1), pick(burgerInLunch, 1), pick(colaInLunch, 1))))
                .as("the same pick twice is one pick of two, and the main group does not repeat")
                .isEqualTo("COMBO_COMPONENT_NOT_REPEATABLE");
        assertThat(refusalOf(cart, "x", lunchVariant, List.of(pick(UUID.randomUUID(), 1))))
                .isEqualTo("COMBO_COMPONENT_NOT_OFFERED");
        assertThat(refusalOf(cart, "x", saladVariant, List.of(pick(burgerInLunch, 1))))
                .as("picks on a dish that is not a combo")
                .isEqualTo("COMBO_NOT_CONFIGURED");
        assertThat(refusalOf(cart, "a~b", saladVariant, List.of()))
                .as("the separator is reserved for a combo's component lines")
                .isEqualTo("LINE_KEY_INVALID");

        // A drink that is sold out sells no combo containing it.
        setAvailable(colaVariant, false);
        assertThat(refusalOf(cart, "lunch", lunchVariant, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1))))
                .as("the cart checks the variants the combo would put on the order")
                .isNotNull();
        setAvailable(colaVariant, true);

        tx(() -> carts.removeLine(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), "lunch"));
        assertThat(carts.view(TENANT, BRAND, CUSTOMER, cart).orElseThrow().lines())
                .isEmpty();
    }

    @Test
    @DisplayName("a component that leaves its sale window refuses the combo, at the cart and at checkout")
    void aComponentOutsideItsSaleWindowIsRefused() {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "lunch", lunchVariant, 1, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));

        // The burger stops being sold between the pick and the checkout: a window that is never now.
        closeSaleWindow(burgerVariant);

        CheckoutService.CheckoutResult refused = tryCheckOut(cart);
        assertThat(refused.created()).isFalse();
        assertThat(refused.rejectionCode())
                .as("the container's own window is open; it is the component's that closed")
                .isEqualTo("ITEM_OUT_OF_SALE_WINDOW");

        UUID other = openCart(FulfillmentMode.PICKUP);
        assertThat(refusalOf(other, "lunch", lunchVariant, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1))))
                .as("and the cart will not take it in the first place")
                .isNotNull();
        assertThat(refusalOf(other, "lunch", lunchVariant, List.of(pick(wrapInLunch, 1), pick(colaInLunch, 1))))
                .as("a combo made of other components is unaffected")
                .isNull();
    }

    @Test
    @DisplayName("a console's JSON for a combo line, with every optional field omitted, parses")
    void aConsolesJsonParses() throws Exception {
        String storefront = """
                {"variantId":"%s","quantity":1,"comboPicks":[{"componentId":"%s"},{"componentId":"%s","quantity":2}]}""".formatted(lunchVariant, burgerInLunch.id(), colaInLunch.id());
        var line = objectMapper.readValue(storefront, StorefrontOrderingController.PutLineRequest.class);
        var picks = Objects.requireNonNull(line.comboPicks());
        assertThat(picks).hasSize(2);
        assertThat(picks.get(0).toPick().quantity())
                .as("an omitted pick quantity is one pick, not a 400 for a missing primitive")
                .isEqualTo(1);
        assertThat(picks.get(1).toPick().quantity()).isEqualTo(2);
        assertThat(line.nestedModifiers()).isNull();

        String nested = """
                {"variantId":"%s","quantity":1,"modifierOptionIds":["%s"],"nestedModifiers":[{"parentOptionId":"%s","optionId":"%s"}]}""".formatted(saladVariant, chiliOption, chiliOption, hotOption);
        var nestedLine = objectMapper.readValue(nested, StorefrontOrderingController.PutLineRequest.class);
        assertThat(nestedLine.nestedModifiers()).hasSize(1);

        String operator = """
                {"variantId":"%s","quantity":1,"comboPicks":[{"componentId":"%s"}]}""".formatted(lunchVariant, burgerInLunch.id());
        var operatorLine = objectMapper.readValue(operator, OperationsOrderController.OrderLineRequest.class);
        assertThat(operatorLine.toLine().comboPicks()).hasSize(1);
    }

    // ============================================================ hidden and nested modifiers

    @Test
    @DisplayName(
            "a hidden delivery box reaches a DELIVERY order and never a PICKUP one, and is never the customer's choice")
    void aHiddenBoxIsAppliedByOrderType() throws Exception {
        // PICKUP: the same dish, no box.
        UUID pickupCart = openCart(FulfillmentMode.PICKUP);
        put(pickupCart, "salad", saladVariant, 1, List.of());
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, pickupCart, cartVersion(pickupCart)));
        linkCustomerPrincipal();
        MvcResult pickupPriced = mvc.perform(post("/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND
                                + "/carts/" + pickupCart + "/pricing")
                        .with(customerToken())
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "price-no-box")
                        .header("If-Match", "\"" + cartVersion(pickupCart) + "\""))
                .andReturn();
        assertThat(JSON.readTree(pickupPriced.getResponse().getContentAsString())
                        .get("hiddenCharges"))
                .as("collected: nothing was added, and the field is there to say so")
                .isEmpty();
        UUID pickupOrder = checkOut(pickupCart);
        assertThat(modifierRows(pickupOrder)).as("collected: no box").isEmpty();
        assertThat(totalOf(pickupOrder)).isEqualTo(SALAD);

        // DELIVERY: the box is applied by the server.
        UUID deliveryCart = openCart(FulfillmentMode.DELIVERY);
        put(deliveryCart, "salad", saladVariant, 1, List.of());
        tx(() -> carts.setDestination(
                TENANT,
                BRAND,
                CUSTOMER,
                deliveryCart,
                cartVersion(deliveryCart),
                new CartService.DestinationCommand(addressId, "Dilnoza", "+998901112233", null)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, deliveryCart, cartVersion(deliveryCart)));

        // What the storefront is told before the customer confirms: the box itemised, so the
        // total can be read against the lines. It is already inside the subtotal, never on top.
        String base = "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND;
        MvcResult pricedOverHttp = mvc.perform(post(base + "/carts/" + deliveryCart + "/pricing")
                        .with(customerToken())
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "price-hidden-box")
                        .header("If-Match", "\"" + cartVersion(deliveryCart) + "\""))
                .andReturn();
        assertThat(pricedOverHttp.getResponse().getStatus())
                .as(pricedOverHttp.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode itemised = JSON.readTree(pricedOverHttp.getResponse().getContentAsString());
        assertThat(itemised.get("hiddenCharges")).hasSize(1);
        assertThat(itemised.get("hiddenCharges").get(0).get("lineKey").asText()).isEqualTo("salad");
        assertThat(itemised.get("hiddenCharges").get(0).get("optionId").asText())
                .isEqualTo(boxOption.toString());
        assertThat(itemised.get("hiddenCharges").get(0).get("amountMinor").asLong())
                .isEqualTo(BOX);
        assertThat(itemised.get("totalMinor").asLong())
                .as("the box is inside the total that includes it, not an addition to it")
                .isEqualTo(SALAD + BOX);

        UUID deliveryOrder = checkOut(deliveryCart);

        assertThat(totalOf(deliveryOrder))
                .as("the box is charged: 20,000 + 2,000")
                .isEqualTo(SALAD + BOX);
        List<ModifierRow> rows = modifierRows(deliveryOrder);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).optionId()).isEqualTo(boxOption);
        assertThat(rows.get(0).name()).isEqualTo("Delivery box");
        assertThat(rows.get(0).autoSelected())
                .as("applied by the server for the order's mode; the customer chose nothing")
                .isTrue();
        assertThat(rows.get(0).parentId()).isNull();
        assertThat(jdbc.sql("""
                                SELECT amount_minor FROM ordering.order_adjustments
                                WHERE order_id = :id AND description_code = 'HIDDEN_MODIFIER'
                                """).param("id", deliveryOrder).query(Long.class).single())
                .as("itemised: the charge is on the order's adjustments against the line")
                .isEqualTo(BOX);

        JsonNode detail = orderDetail(deliveryOrder);
        assertThat(detail.get("lines")
                        .get(0)
                        .get("autoSelectedModifiers")
                        .get(0)
                        .asText())
                .isEqualTo("Delivery box");
        JsonNode operatorCharge =
                detail.get("lines").get(0).get("autoSelectedCharges").get(0);
        assertThat(operatorCharge.get("name").asText()).isEqualTo("Delivery box");
        assertThat(operatorCharge.get("amountMinor").asLong())
                .as("the console can itemise a charge the customer never chose")
                .isEqualTo(BOX);

        MvcResult customerRead = mvc.perform(
                        get(base + "/orders/" + deliveryOrder).with(customerToken()))
                .andReturn();
        assertThat(customerRead.getResponse().getStatus())
                .as(customerRead.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode customerCharge = JSON.readTree(customerRead.getResponse().getContentAsString())
                .get("lines")
                .get(0)
                .get("autoSelectedCharges")
                .get(0);
        assertThat(customerCharge.get("name").asText()).isEqualTo("Delivery box");
        assertThat(customerCharge.get("amountMinor").asLong()).isEqualTo(BOX);

        // It is never offered as a choice, and a customer cannot pick it either (on a cart that is
        // still open: the pickup cart above has been checked out).
        UUID openPickupCart = openCart(FulfillmentMode.PICKUP);
        assertThat(refusalOf(openPickupCart, "x", saladVariant, List.of(), List.of(boxOption)))
                .isEqualTo("MODIFIER_NOT_OFFERED");

        // A repeat is built from what the customer chose; the server applies the box again by itself.
        var plan = reorderPlans.planFor(TENANT, deliveryOrder, CUSTOMER).orElseThrow();
        assertThat(plan.lines()).hasSize(1);
        assertThat(plan.lines().get(0).modifierOptionIds())
                .as("the hidden box is not carried into the repeat as if it were a choice")
                .isEmpty();
    }

    @Test
    @DisplayName("a second-level choice is stored under the first-level option that offered it")
    void aNestedChoiceIsStoredUnderItsParent() throws Exception {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                "salad",
                saladVariant,
                1,
                List.of(chiliOption),
                null,
                List.of(),
                List.of(new CartService.NestedModifier(chiliOption, hotOption)),
                null));
        var priced = tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        assertThat(priced.quote().totalMinor()).as("salad + chili + hot").isEqualTo(SALAD + CHILI + HOT);

        // The linked variant's required group has to be answered.
        Throwable unanswered = catchThrowable(() -> tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                "salad2",
                saladVariant,
                1,
                List.of(chiliOption),
                null,
                List.of(),
                List.of(),
                null)));
        assertThat(unanswered).isInstanceOf(CartService.CartRefusedException.class);
        assertThat(((CartService.CartRefusedException) unanswered).code()).isEqualTo("MODIFIER_GROUP_MINIMUM_NOT_MET");

        UUID orderId = checkOut(cart);
        List<ModifierRow> rows = modifierRows(orderId);
        assertThat(rows).extracting(ModifierRow::optionId).containsExactlyInAnyOrder(chiliOption, hotOption);
        ModifierRow chili = rows.stream()
                .filter(row -> row.optionId().equals(chiliOption))
                .findFirst()
                .orElseThrow();
        ModifierRow hot = rows.stream()
                .filter(row -> row.optionId().equals(hotOption))
                .findFirst()
                .orElseThrow();
        assertThat(chili.parentId()).as("a first-level selection has no parent").isNull();
        assertThat(hot.parentId()).as("hot hangs off chili").isEqualTo(chili.id());

        // One level and no more: the schema refuses a third.
        Throwable third = catchThrowable(() -> jdbc.sql("""
                        INSERT INTO ordering.order_line_modifiers (id, tenant_id, order_line_id, source_option_id,
                            option_name_snapshot, unit_amount_minor, final_amount_minor,
                            parent_order_line_modifier_id)
                        SELECT :id, tenant_id, order_line_id, :option, 'too deep', 0, 0, :parent
                        FROM ordering.order_line_modifiers WHERE id = :parent
                        """)
                .param("id", UUID.randomUUID())
                .param("option", mildOption)
                .param("parent", hot.id())
                .update());
        assertThat(third)
                .as("the trigger refuses a selection whose parent is itself nested")
                .isNotNull();

        // A repeat carries the first level and the nested choice, in the shape the cart takes them.
        var plan = reorderPlans.planFor(TENANT, orderId, CUSTOMER).orElseThrow();
        assertThat(plan.lines().get(0).modifierOptionIds()).containsExactly(chiliOption);
        assertThat(plan.lines().get(0).nestedModifiers())
                .containsExactly(new CartService.NestedModifier(chiliOption, hotOption));
    }

    @Test
    @DisplayName(
            "a variant's own override of a shared group is published, and the cart holds the customer to it for that size only")
    void aVariantLevelOverrideIsEnforcedByTheCart() throws Exception {
        var fries = authoring.createProduct(
                TENANT, BRAND, catalogId, "FRIES", "Fries", null, LOCALE, "SKU-FRIES", "PIECE", UNCLASSIFIED, ACTOR);
        UUID smallFries = fries.defaultVariantId();
        UUID largeFries = authoring.addVariant(
                TENANT, BRAND, fries.productId(), "SKU-FRIES-L", "PIECE", "Large", LOCALE, 1, UNCLASSIFIED, ACTOR);
        UUID dips = authoring.createModifierGroup(TENANT, BRAND, "DIPS", "Dips", LOCALE, false, 0, 2, false);
        UUID ketchup = authoring.addModifierOption(
                TENANT, BRAND, dips, "KETCHUP", "Ketchup", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        UUID mayo = authoring.addModifierOption(
                TENANT, BRAND, dips, "MAYO", "Mayo", LOCALE, null, 1, 1, UNCLASSIFIED, ACTOR);
        // Every size of the fries offers the dips, optionally and up to two...
        authoring.attachModifierGroup(TENANT, BRAND, fries.productId(), dips, 0);
        // A group only the large size has (a variant-level attachment and nothing at the product).
        UUID extras = authoring.createModifierGroup(TENANT, BRAND, "EXTRAS", "Extras", LOCALE, false, 0, 1, false);
        UUID cheese = authoring.addModifierOption(
                TENANT, BRAND, extras, "CHEESE", "Cheese", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        composites.attachModifierGroupToVariant(TENANT, BRAND, largeFries, extras, 1, TESTER);
        // ...and the large one says its own rule for the same group: one dip, required.
        composites.attachModifierGroupToVariant(TENANT, BRAND, largeFries, dips, 0, TESTER);
        composites.setAttachmentPolicy(
                TENANT,
                BRAND,
                AttachmentOwnerType.VARIANT,
                largeFries,
                dips,
                1,
                new AttachmentPolicy(Visibility.VISIBLE, null, true, 1, 1),
                TESTER);
        price("VARIANT", smallFries, 12_000L);
        price("VARIANT", largeFries, 18_000L);
        price("MODIFIER_OPTION", ketchup, 500L);
        price("MODIFIER_OPTION", mayo, 700L);
        price("MODIFIER_OPTION", cheese, 1_500L);
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, smallFries, TrackingMode.BINARY);
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, largeFries, TrackingMode.BINARY);
        publishTheMenu();
        UUID cart = openCart(FulfillmentMode.PICKUP);

        assertThat(refusalOf(cart, "small-plain", smallFries, List.of(), List.of()))
                .as("the product's own rule is optional, and the small size has no rule of its own")
                .isNull();
        assertThat(refusalOf(cart, "small-two", smallFries, List.of(), List.of(ketchup, mayo)))
                .as("two different dips are within the product's range for the small size")
                .isNull();
        assertThat(refusalOf(cart, "large-plain", largeFries, List.of(), List.of()))
                .as("the large size requires a dip, and the cart was told so by the publication")
                .isEqualTo("MODIFIER_GROUP_MINIMUM_NOT_MET");
        assertThat(refusalOf(cart, "large-dip", largeFries, List.of(), List.of(ketchup)))
                .isNull();
        assertThat(refusalOf(cart, "large-two", largeFries, List.of(), List.of(ketchup, mayo)))
                .as("the large size allows one dip: its maximum replaced the product's two")
                .isEqualTo("MODIFIER_GROUP_MAXIMUM_EXCEEDED");
        assertThat(refusalOf(cart, "small-cheese", smallFries, List.of(), List.of(cheese)))
                .as("the extras are the large size's own group; the small size is not offered them")
                .isNotNull();
        assertThat(refusalOf(cart, "large-cheese", largeFries, List.of(), List.of(ketchup, cheese)))
                .as("a group only the variant carries is offered, and an option of it is accepted")
                .isNull();

        // What the customer bought is what a repeat offers again: the options of the variant's own
        // groups are on the menu the reorder is checked against.
        UUID second = openCart(FulfillmentMode.PICKUP);
        tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                second,
                cartVersion(second),
                "fries",
                largeFries,
                1,
                List.of(ketchup, cheese),
                null,
                List.of(),
                List.of(),
                null));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, second, cartVersion(second)));
        UUID orderId = checkOut(second);
        offer(largeFries);

        var plan = reorderPlans.planFor(TENANT, orderId, CUSTOMER).orElseThrow();

        assertThat(plan.lines()).singleElement().satisfies(line -> {
            assertThat(line.status()).isEqualTo(ReorderPlanService.LineStatus.AVAILABLE);
            assertThat(line.modifierOptionIds()).containsExactlyInAnyOrder(ketchup, cheese);
        });
    }

    @Test
    @DisplayName("the schema refuses an order, cart or ticket row that half-describes a combo or a nested choice")
    void theSchemaRefusesIncoherentComboRows() {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "lunch", lunchVariant, 1, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        OrderLine line = orderLines(orderId).get(0);

        assertThat(catchThrowable(() -> jdbc.sql("""
                                INSERT INTO ordering.order_lines (id, tenant_id, order_id, line_number,
                                    source_variant_id, product_name_snapshot, quantity, unit_amount_minor,
                                    base_amount_minor, final_amount_minor, tax_amount_minor, combo_selection_id)
                                SELECT :id, tenant_id, order_id, 99, source_variant_id, product_name_snapshot,
                                    quantity, unit_amount_minor, base_amount_minor, final_amount_minor,
                                    tax_amount_minor, :selection
                                FROM ordering.order_lines WHERE id = :line
                                """)
                        .param("id", UUID.randomUUID())
                        .param("selection", UUID.randomUUID())
                        .param("line", line.lineId())
                        .update()))
                .as("a selection with no container is not a combo line")
                .hasMessageContaining("ck_order_line_combo_pair");

        assertThat(catchThrowable(() -> jdbc.sql("""
                                INSERT INTO ordering.order_lines (id, tenant_id, order_id, line_number,
                                    source_variant_id, product_name_snapshot, quantity, unit_amount_minor,
                                    base_amount_minor, final_amount_minor, tax_amount_minor,
                                    combo_selection_id, combo_container_variant_id)
                                SELECT :id, tenant_id, order_id, 99, source_variant_id, product_name_snapshot,
                                    quantity, unit_amount_minor, base_amount_minor, final_amount_minor,
                                    tax_amount_minor, :selection, :container
                                FROM ordering.order_lines WHERE id = :line
                                """)
                        .param("id", UUID.randomUUID())
                        .param("selection", UUID.randomUUID())
                        .param("container", lunchVariant)
                        .param("line", line.lineId())
                        .update()))
                .as("a combo line an amendment could not price again: no name, no pairing, no quantities")
                .hasMessageContaining("ck_order_line_combo_provenance");

        assertThat(catchThrowable(() -> jdbc.sql("""
                                UPDATE ordering.cart_lines SET combo_picks = '{}'::jsonb WHERE cart_id = :cart
                                """).param("cart", cart).update()))
                .hasMessageContaining("ck_cart_line_combo_picks_array");

        assertThat(catchThrowable(() -> jdbc.sql("""
                                UPDATE kitchen.ticket_items SET combo_selection_id = NULL
                                WHERE combo_container_variant_id IS NOT NULL
                                """).update()))
                .as("a ticket item names the combo it belongs to or does not")
                .hasMessageContaining("ck_ticket_item_combo_pair");
    }

    // ===================================================================== amendments

    @Test
    @DisplayName("amending an order that holds a combo reprices the combo at its combo price, not its components'")
    void anAmendmentKeepsTheComboAtItsComboPrice() throws Exception {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "lunch", lunchVariant, 1, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        long before = BURGER_IN_LUNCH + COLA_IN_LUNCH;
        assertThat(totalOf(orderId)).isEqualTo(before);

        // The operator adds a salad. A basket that priced the combo's lines as ordinary lines
        // would charge the burger 30,000 and the cola 5,000 and move the total by 7,000 more.
        JsonNode applied = amend(orderId, "amend-add-salad", """
                {"type":"ADD_LINES","lines":[{"variantId":"%s","quantity":1}]}""".formatted(saladVariant));

        assertThat(applied.get("status").asText()).isEqualTo("APPLIED");
        assertThat(totalOf(orderId))
                .as("the combo is unchanged at 28,000; only the salad was added")
                .isEqualTo(before + SALAD);
        List<OrderLine> lines = orderLines(orderId);
        assertThat(lines).hasSize(3);
        assertThat(lines.stream().filter(line -> line.selectionId() != null))
                .as("the combo's two lines are the same rows they were, selection and all")
                .hasSize(2);
        assertThat(reconciles(orderId)).isTrue();
    }

    @Test
    @DisplayName("an operator adds a combo to an order: each component is priced, stocked and stored as a line")
    void anAmendmentCanAddACombo() throws Exception {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "salad", saladVariant, 1, List.of());
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);

        JsonNode applied =
                amend(orderId, "amend-add-combo", """
                {"type":"ADD_LINES","lines":[{"variantId":"%s","quantity":2,"comboPicks":[
                  {"componentId":"%s","quantity":1},{"componentId":"%s","quantity":1}]}]}""".formatted(lunchVariant, wrapInLunch.id(), colaInLunch.id()));

        assertThat(applied.get("status").asText()).isEqualTo("APPLIED");
        assertThat(totalOf(orderId)).isEqualTo(SALAD + 2 * (WRAP_IN_LUNCH + COLA_IN_LUNCH));
        List<OrderLine> combo = orderLines(orderId).stream()
                .filter(line -> line.selectionId() != null)
                .toList();
        assertThat(combo).extracting(OrderLine::variantId).containsExactly(wrapVariant, colaVariant);
        assertThat(combo).extracting(OrderLine::comboQuantity).containsOnly(2);
        assertThat(combo).extracting(OrderLine::comboName).containsOnly("Lunch box");
        assertThat(combo.stream().map(OrderLine::selectionId).distinct()).hasSize(1);
        assertThat(reservedUnitsByVariant(orderId))
                .as("the amendment held the added combo's components, and never the container")
                .containsEntry(wrapVariant, 2)
                .containsEntry(colaVariant, 2)
                .doesNotContainKey(lunchVariant);
        assertThat(reconciles(orderId)).isTrue();
    }

    @Test
    @DisplayName(
            "a combo added to an order whose ticket is already open reaches its stations, each component by its own variant")
    void aComboAddedAfterTheTicketOpenedReachesItsStations() throws Exception {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "salad", saladVariant, 1, List.of());
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        var ticket = tickets.byOrder(TENANT, orderId).orElseThrow();
        assertThat(tickets.items(TENANT, ticket.id()))
                .as("the ticket was built from the one salad the order held when it was confirmed")
                .hasSize(1);

        amend(orderId, "amend-add-combo-to-open-ticket", """
                {"type":"ADD_LINES","lines":[{"variantId":"%s","quantity":2,"comboPicks":[
                  {"componentId":"%s","quantity":1},{"componentId":"%s","quantity":1}]}]}""".formatted(
                        lunchVariant, burgerInLunch.id(), colaInLunch.id()));

        List<OrderLine> combo = orderLines(orderId).stream()
                .filter(line -> line.selectionId() != null)
                .toList();
        List<TicketItemRow> items = tickets.items(TENANT, ticket.id());
        assertThat(items)
                .as("the salad's item and one item per added component")
                .hasSize(3);
        TicketItemRow burgerItem = itemFor(items, combo.get(0));
        TicketItemRow colaItem = itemFor(items, combo.get(1));
        assertThat(burgerItem.stationId())
                .as("the added burger goes to the grill")
                .isEqualTo(grill);
        assertThat(colaItem.stationId()).as("the added cola goes to the bar").isEqualTo(bar);
        assertThat(burgerItem.quantity()).isEqualByComparingTo("2");
        assertThat(colaItem.quantity()).isEqualByComparingTo("2");
        assertThat(items.stream()
                        .filter(item -> item.comboSelectionId() != null)
                        .map(TicketItemRow::comboSelectionId)
                        .distinct())
                .as("they are one combo on the board, under the heading the order carries")
                .containsExactly(combo.get(0).selectionId());
        assertThat(items).extracting(TicketItemRow::status).containsOnly(TicketItemStatus.QUEUED);
        assertThat(tickets.events(TENANT, ticket.id()))
                .as("the board can say why these two appeared: the amendment, on the ticket's own timeline")
                .filteredOn(event -> event.trigger().equals("ORDER_AMENDED"))
                .extracting(JdbcKitchenStore.TicketEventRow::ticketItemId)
                .containsExactlyInAnyOrder(burgerItem.id(), colaItem.id());
    }

    @Test
    @DisplayName("growing a combo strikes what the kitchen has not made and credits what is already on the pass")
    void growingAComboAfterTheTicketOpenedReplacesItsItems() throws Exception {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "lunch", lunchVariant, 1, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        var ticket = tickets.byOrder(TENANT, orderId).orElseThrow();
        List<OrderLine> before = orderLines(orderId);
        TicketItemRow burgerBefore = itemFor(tickets.items(TENANT, ticket.id()), before.get(0));
        TicketItemRow colaBefore = itemFor(tickets.items(TENANT, ticket.id()), before.get(1));
        // The grill has already made the one burger the order held; the cola has not been started.
        tickets.start(TENANT, burgerBefore.id(), "cook", null);
        tickets.ready(TENANT, burgerBefore.id(), "cook", null);

        amend(orderId, "amend-grow-combo-on-open-ticket", """
                {"type":"CHANGE_LINE_QUANTITY","orderLineId":"%s","quantity":3}""".formatted(
                        before.get(0).lineId()));

        List<OrderLine> after = orderLines(orderId);
        List<TicketItemRow> items = tickets.items(TENANT, ticket.id());
        TicketItemRow burgerKept = items.stream()
                .filter(item -> item.id().equals(burgerBefore.id()))
                .findFirst()
                .orElseThrow();
        TicketItemRow colaStruck = items.stream()
                .filter(item -> item.id().equals(colaBefore.id()))
                .findFirst()
                .orElseThrow();
        assertThat(burgerKept.status())
                .as("a burger already on the pass is not unmade because the order grew")
                .isEqualTo(TicketItemStatus.READY);
        assertThat(colaStruck.status())
                .as("the cola nobody started is replaced by the larger one")
                .isEqualTo(TicketItemStatus.CANCELLED);
        TicketItemRow burgerMore = itemFor(items, after.get(0));
        TicketItemRow colaNew = itemFor(items, after.get(1));
        assertThat(burgerMore.quantity())
                .as("three burgers asked for, one already made: two more to cook")
                .isEqualByComparingTo("2");
        assertThat(burgerMore.stationId()).isEqualTo(grill);
        assertThat(burgerMore.status()).isEqualTo(TicketItemStatus.QUEUED);
        assertThat(colaNew.quantity()).isEqualByComparingTo("3");
        assertThat(colaNew.stationId()).isEqualTo(bar);
        assertThat(tickets.require(TENANT, ticket.id()).status())
                .as("the ticket that was waiting only on the cola is not ready any more")
                .isNotEqualTo(uz.horecaos.platform.kitchen.domain.TicketStatus.READY);
    }

    @Test
    @DisplayName("an amendment that touches no line leaves the ticket as it was, and a replay adds nothing")
    void anAmendmentThatChangesNoLineLeavesTheTicketAlone() throws Exception {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "lunch", lunchVariant, 1, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        var ticket = tickets.byOrder(TENANT, orderId).orElseThrow();
        int itemsBefore = tickets.items(TENANT, ticket.id()).size();
        int eventsBefore = tickets.events(TENANT, ticket.id()).size();

        tx(() -> tickets.syncAmendedLines(TENANT, orderId));
        tx(() -> tickets.syncAmendedLines(TENANT, orderId));

        assertThat(tickets.items(TENANT, ticket.id())).hasSize(itemsBefore);
        assertThat(tickets.events(TENANT, ticket.id())).hasSize(eventsBefore);
    }

    @Test
    @DisplayName("growing one component line by whole combos grows the combo; a fraction of a combo is refused")
    void anAmendmentGrowsTheComboByWholeCombos() throws Exception {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "lunch", lunchVariant, 1, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        UUID selection = orderLines(orderId).get(0).selectionId();
        UUID burgerLine = orderLines(orderId).get(0).lineId();

        JsonNode applied = amend(orderId, "amend-grow-combo", """
                {"type":"CHANGE_LINE_QUANTITY","orderLineId":"%s","quantity":3}""".formatted(burgerLine));

        assertThat(applied.get("status").asText()).isEqualTo("APPLIED");
        List<OrderLine> lines = orderLines(orderId);
        assertThat(lines).hasSize(2);
        assertThat(lines)
                .extracting(OrderLine::quantity)
                .as("three burgers and, with them, three colas")
                .containsExactly(3, 3);
        assertThat(lines).extracting(OrderLine::comboQuantity).containsOnly(3);
        assertThat(lines)
                .extracting(OrderLine::selectionId)
                .as("the same purchase, only bigger")
                .containsOnly(selection);
        assertThat(totalOf(orderId)).isEqualTo(3 * (BURGER_IN_LUNCH + COLA_IN_LUNCH));
        assertThat(jdbc.sql("""
                                SELECT count(*) FROM ordering.order_lines
                                WHERE order_id = :id AND revision_to IS NOT NULL
                                """).param("id", orderId).query(Integer.class).single())
                .as("the old rows were closed, never edited")
                .isEqualTo(2);
        assertThat(reservedUnitsByVariant(orderId))
                .containsEntry(burgerVariant, 3)
                .containsEntry(colaVariant, 3);
        assertThat(tickets.byOrder(TENANT, orderId)).isPresent();
        assertThat(reconciles(orderId)).isTrue();
    }

    @Test
    @DisplayName("an amended combo is sent to the till as its live component lines, not also as the rows it closed")
    void anAmendedComboIsExportedAsItsLiveLines() throws Exception {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "lunch", lunchVariant, 1, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        amend(orderId, "amend-grow-combo-before-export", """
                {"type":"CHANGE_LINE_QUANTITY","orderLineId":"%s","quantity":3}""".formatted(
                        orderLines(orderId).get(0).lineId()));

        PosAdapter.OrderExport exported = exportToTheTill(orderId, Set.of());

        assertThat(exported.lines())
                .as("two components, three of each; the two rows the amendment closed are history")
                .extracting(PosAdapter.OrderExport.Line::quantity)
                .containsExactly(java.math.BigDecimal.valueOf(3), java.math.BigDecimal.valueOf(3));
        assertThat(exported.lines())
                .extracting(PosAdapter.OrderExport.Line::externalProductId)
                .containsExactly("ext-" + burgerVariant, "ext-" + colaVariant);
        assertThat(exported.lines())
                .extracting(PosAdapter.OrderExport.Line::comboId)
                .containsOnly(Objects.requireNonNull(orderLines(orderId).get(0).selectionId())
                        .toString());
    }

    @Test
    @DisplayName("a rewritten line keeps its hidden box on a DELIVERY order and its choices on any order")
    void aRewrittenLineKeepsWhatWasAttachedToIt() throws Exception {
        UUID cart = openCart(FulfillmentMode.DELIVERY);
        tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                "salad",
                saladVariant,
                1,
                List.of(chiliOption),
                null,
                List.of(),
                List.of(new CartService.NestedModifier(chiliOption, hotOption)),
                null));
        tx(() -> carts.setDestination(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                new CartService.DestinationCommand(addressId, "Dilnoza", "+998901112233", null)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        long unit = SALAD + CHILI + HOT + BOX;
        assertThat(totalOf(orderId)).isEqualTo(unit);

        JsonNode applied = amend(orderId, "amend-grow-salad", """
                {"type":"CHANGE_LINE_QUANTITY","orderLineId":"%s","quantity":2}""".formatted(
                        orderLines(orderId).get(0).lineId()));

        assertThat(applied.get("status").asText()).isEqualTo("APPLIED");
        assertThat(totalOf(orderId))
                .as("repriced from the rows that were stored: chili, hot and the server's box")
                .isEqualTo(2 * unit);
        List<ModifierRow> rows = modifierRows(orderId, true);
        assertThat(rows)
                .extracting(ModifierRow::optionId)
                .as("the replacement line carries the customer's choices and the server's box, once each")
                .containsExactlyInAnyOrder(chiliOption, hotOption, boxOption);
        assertThat(rows.stream().filter(ModifierRow::autoSelected))
                .extracting(ModifierRow::optionId)
                .containsExactly(boxOption);
        ModifierRow chili = rows.stream()
                .filter(row -> row.optionId().equals(chiliOption))
                .findFirst()
                .orElseThrow();
        ModifierRow hot = rows.stream()
                .filter(row -> row.optionId().equals(hotOption))
                .findFirst()
                .orElseThrow();
        assertThat(hot.parentId()).isEqualTo(chili.id());

        // A second amendment prices the same basket again, which only works if the first kept it.
        amend(orderId, "amend-grow-salad-again", """
                {"type":"CHANGE_LINE_QUANTITY","orderLineId":"%s","quantity":3}""".formatted(
                        orderLines(orderId).get(0).lineId()));
        assertThat(totalOf(orderId)).isEqualTo(3 * unit);
    }

    @Test
    @DisplayName("an amendment that rewrites or adds a line keeps the line's hidden box itemised, not shown as free")
    void anAmendedLineStillItemisesItsHiddenBox() throws Exception {
        UUID cart = openCart(FulfillmentMode.DELIVERY);
        put(cart, "salad", saladVariant, 1, List.of());
        tx(() -> carts.setDestination(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                new CartService.DestinationCommand(addressId, "Dilnoza", "+998901112233", null)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);

        amend(orderId, "amend-grow-salad-box", """
                {"type":"CHANGE_LINE_QUANTITY","orderLineId":"%s","quantity":2}""".formatted(
                        orderLines(orderId).get(0).lineId()));
        amend(orderId, "amend-add-salad-box", """
                {"type":"ADD_LINES","lines":[{"variantId":"%s","quantity":1}]}""".formatted(saladVariant));

        assertThat(totalOf(orderId))
                .as("the box is in the total: two salads and one more")
                .isEqualTo(3 * (SALAD + BOX));
        JsonNode lines = orderDetail(orderId).get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)
                        .get("autoSelectedCharges")
                        .get(0)
                        .get("amountMinor")
                        .asLong())
                .as("the rewritten line's box is two boxes, itemised -- not 'Delivery box 0'")
                .isEqualTo(2 * BOX);
        assertThat(lines.get(1)
                        .get("autoSelectedCharges")
                        .get(0)
                        .get("amountMinor")
                        .asLong())
                .as("and so is the box of the line the amendment added")
                .isEqualTo(BOX);

        linkCustomerPrincipal();
        MvcResult customerRead = mvc.perform(
                        get("/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND + "/orders/" + orderId)
                                .with(customerToken()))
                .andReturn();
        assertThat(customerRead.getResponse().getStatus())
                .as(customerRead.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode customerLines =
                JSON.readTree(customerRead.getResponse().getContentAsString()).get("lines");
        assertThat(customerLines
                        .get(0)
                        .get("autoSelectedCharges")
                        .get(0)
                        .get("amountMinor")
                        .asLong())
                .as("the customer reads the same figure")
                .isEqualTo(2 * BOX);
        assertThat(customerLines
                        .get(1)
                        .get("autoSelectedCharges")
                        .get(0)
                        .get("amountMinor")
                        .asLong())
                .isEqualTo(BOX);
    }

    // ===================================================================== reorder

    @Test
    @DisplayName("a repeat of a combo order is the combo and its picks, not its components as plain lines")
    void aRepeatIsTheComboAndItsPicks() {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "lunch", lunchVariant, 2, List.of(pick(wrapInLunch, 1), pick(colaInLunch, 1)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        offer(lunchVariant);
        offer(wrapVariant);
        offer(colaVariant);

        var plan = reorderPlans.planFor(TENANT, orderId, CUSTOMER).orElseThrow();

        assertThat(plan.lines()).as("one thing to repeat, not two").hasSize(1);
        var line = plan.lines().get(0);
        assertThat(line.variantId()).isEqualTo(lunchVariant);
        assertThat(line.productName()).isEqualTo("Lunch box");
        assertThat(line.quantity()).as("combos").isEqualByComparingTo("2");
        assertThat(line.comboPicks())
                .containsExactlyInAnyOrder(
                        new CartService.ComboPick(wrapInLunch.id(), 1), new CartService.ComboPick(colaInLunch.id(), 1));
        assertThat(line.originalUnitAmountMinor()).as("what one box cost").isEqualTo(WRAP_IN_LUNCH + COLA_IN_LUNCH);
        assertThat(line.status()).isEqualTo(ReorderPlanService.LineStatus.AVAILABLE);

        // The plan is something the cart accepts as it stands.
        UUID repeat = openCart(FulfillmentMode.PICKUP);
        tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                repeat,
                cartVersion(repeat),
                "r1",
                line.variantId(),
                line.quantity(),
                line.modifierOptionIds(),
                null,
                line.comboPicks(),
                line.nestedModifiers(),
                null));
        assertThat(tx(() -> carts.price(TENANT, BRAND, CUSTOMER, repeat, cartVersion(repeat)))
                        .quote()
                        .totalMinor())
                .isEqualTo(2 * (WRAP_IN_LUNCH + COLA_IN_LUNCH));

        // A drink sold out since makes the whole combo unavailable.
        setAvailable(colaVariant, false);
        var blocked = reorderPlans.planFor(TENANT, orderId, CUSTOMER).orElseThrow();
        assertThat(blocked.lines().get(0).status()).isEqualTo(ReorderPlanService.LineStatus.SOLD_OUT);
        setAvailable(colaVariant, true);
    }

    @Test
    @DisplayName(
            "the chat bot repeats a combo order as a combo cart line with its picks, and prices it at the combo price")
    void theBotRepeatsAComboAsAComboLine() {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "lunch", lunchVariant, 2, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        offer(lunchVariant);
        offer(burgerVariant);
        offer(colaVariant);

        var repeat = tx(() -> bot.repeat(TENANT, BRAND, CUSTOMER, orderId));

        assertThat(repeat.result()).isEqualTo(CustomerBotOrderingPort.Repeat.Result.BUILT);
        assertThat(repeat.lineCount()).as("one combo, not its two components").isEqualTo(1);
        UUID repeatCart = Objects.requireNonNull(repeat.cartId());
        CartService.CartView view =
                carts.view(TENANT, BRAND, CUSTOMER, repeatCart).orElseThrow();
        assertThat(view.lines()).hasSize(1);
        assertThat(view.lines().get(0).variantId())
                .as("the container the customer added, never a component")
                .isEqualTo(lunchVariant);
        assertThat(view.lines().get(0).quantity()).isEqualByComparingTo("2");
        assertThat(view.selectionsOf(view.lines().get(0).lineKey()).comboPicks())
                .containsExactlyInAnyOrder(
                        new CartService.ComboPick(burgerInLunch.id(), 1),
                        new CartService.ComboPick(colaInLunch.id(), 1));
        assertThat(tx(() -> carts.price(
                                TENANT, BRAND, CUSTOMER, repeatCart, view.cart().version()))
                        .quote()
                        .totalMinor())
                .as("priced as a combo again, not as burgers and colas on their own")
                .isEqualTo(2 * (BURGER_IN_LUNCH + COLA_IN_LUNCH));
    }

    // ===================================================================== till edge cases

    @Test
    @DisplayName("a hidden box nobody mapped on the till refuses the export as MODIFIER_UNMAPPED")
    void anUnmappedHiddenOptionRefusesTheExport() throws Exception {
        UUID cart = openCart(FulfillmentMode.DELIVERY);
        put(cart, "salad", saladVariant, 1, List.of());
        tx(() -> carts.setDestination(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                new CartService.DestinationCommand(addressId, "Dilnoza", "+998901112233", null)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);

        var refused = openAndSend(orderId, new Mappings(Set.of(boxOption)), spy(new FakePosAdapter()));

        assertThat(refused.error())
                .as("the box is a modifier on the line, so it travels and is mapped like one")
                .isEqualTo("MODIFIER_UNMAPPED");
    }

    @Test
    @DisplayName("a ticket opened after a combo was amended is made of the live lines, not also the rows it closed")
    void aTicketOpenedAfterAnAmendmentHoldsOnlyTheLiveLines() throws Exception {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "lunch", lunchVariant, 1, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        amend(orderId, "amend-grow-combo-before-ticket", """
                {"type":"CHANGE_LINE_QUANTITY","orderLineId":"%s","quantity":3}""".formatted(
                        orderLines(orderId).get(0).lineId()));
        List<OrderLine> live = orderLines(orderId);
        assertThat(live).as("two components, rewritten whole").hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.order_lines WHERE order_id = :id")
                        .param("id", orderId)
                        .query(Integer.class)
                        .single())
                .as("the amendment closed two rows and appended two: the order has four rows in all")
                .isEqualTo(4);

        // Whatever opened the first ticket is not under test; the opener running after an amendment
        // is (an order accepted after it was amended, or the async opener arriving late). Take the
        // ticket away and let the opener build it again from the order as it stands now.
        removeTicketOf(orderId);
        var ticket = tickets.open(TENANT, orderId, ReleaseMode.AUTO_ON_CONFIRM);

        List<TicketItemRow> items = tickets.items(TENANT, ticket.id());
        assertThat(items)
                .as("one item per live component; the closed rows are history, not food to cook")
                .extracting(TicketItemRow::orderLineId)
                .containsExactlyInAnyOrderElementsOf(
                        live.stream().map(OrderLine::lineId).toList());
        assertThat(items)
                .extracting(item -> item.quantity().intValue())
                .as("three burgers and three colas, not also the one each of the closed rows")
                .containsExactly(3, 3);
    }

    @Test
    @DisplayName(
            "the day close reports an amended combo once: the live lines carry the combo, the rows the amendment closed are not facts")
    void anAmendedComboIsReportedOnceByTheDayClose() throws Exception {
        jdbc.sql("TRUNCATE TABLE reporting.fact_order_line, reporting.fact_order")
                .update();
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "lunch", lunchVariant, 1, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        amend(orderId, "amend-grow-combo-before-report", """
                {"type":"CHANGE_LINE_QUANTITY","orderLineId":"%s","quantity":3}""".formatted(
                        orderLines(orderId).get(0).lineId()));
        List<OrderLine> live = orderLines(orderId);
        UUID selection = Objects.requireNonNull(live.get(0).selectionId());
        for (OrderStatus target : List.of(OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.COMPLETED)) {
            int version = jdbc.sql("SELECT version FROM ordering.orders WHERE id = :id")
                    .param("id", orderId)
                    .query(Integer.class)
                    .single();
            tx(() -> orderStates.advance(TENANT, orderId, target, version, "TEST", "USER", OPERATOR, null));
        }
        LocalDate day = jdbc.sql(
                        "SELECT (created_at AT TIME ZONE 'Asia/Tashkent')::date FROM ordering.orders WHERE id = :id")
                .param("id", orderId)
                .query(LocalDate.class)
                .single();

        dayClose.close(TENANT, day);

        List<Map<String, Object>> facts =
                jdbc.sql("""
                        SELECT variant_id, quantity, net_som, combo_selection_id, combo_container_variant_id,
                               combo_quantity, combo_name_snapshot
                        FROM reporting.fact_order_line WHERE order_id = :id ORDER BY variant_id
                        """).param("id", orderId).query().listOfRows();
        assertThat(facts)
                .as("two components; the two rows the amendment closed (four in all on the order) are history")
                .hasSize(2);
        assertThat(facts).allSatisfy(fact -> {
            assertThat(fact.get("combo_selection_id")).isEqualTo(selection);
            assertThat(fact.get("combo_container_variant_id")).isEqualTo(lunchVariant);
            assertThat(fact.get("combo_quantity")).isEqualTo(3);
            assertThat(fact.get("combo_name_snapshot")).isEqualTo("Lunch box");
            assertThat(Objects.requireNonNull((Number) fact.get("quantity")).intValue())
                    .isEqualTo(3);
        });
        assertThat(jdbc.sql("SELECT line_count FROM reporting.fact_order WHERE order_id = :id")
                        .param("id", orderId)
                        .query(Integer.class)
                        .single())
                .as("the order has two lines, not four")
                .isEqualTo(2);

        var report = reportQueries.comboSales(TENANT, day, day, List.of(), List.of(), 100);

        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.comboContainerVariantId()).isEqualTo(lunchVariant);
            assertThat(row.comboName()).isEqualTo("Lunch box");
            assertThat(row.combosSold())
                    .as("three combos, once, not once per line or per revision")
                    .isEqualTo(3L);
            assertThat(row.purchases()).isEqualTo(1L);
            assertThat(row.orders()).isEqualTo(1L);
            assertThat(row.totalNetSom()).isEqualTo(3 * (BURGER_IN_LUNCH + COLA_IN_LUNCH));
        });
    }

    // ============================================================ weighing at the pass (ADR 0137)

    @Test
    @DisplayName("weighing a fish leaves a combo on the same order at its combo price, and records the weight")
    void weighingALineLeavesAComboAtItsComboPrice() throws Exception {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "lunch", lunchVariant, 1, List.of(pick(burgerInLunch, 1), pick(colaInLunch, 1)));
        put(cart, "fish", fishVariant, 1, List.of());
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        long combo = BURGER_IN_LUNCH + COLA_IN_LUNCH;
        assertThat(totalOf(orderId))
                .as("the combo at its combo price, and the fish at its nominal 1,000 g with its packing")
                .isEqualTo(combo + 10 * FISH_PER_QUANTUM + PACK);
        UUID fishLine = lineOf(orderId, fishVariant);

        MvcResult weighed = weigh(orderId, fishLine, 1_300);

        assertThat(weighed.getResponse().getStatus())
                .as(
                        "a combo's components are priced as a combo's, so only the fish moves: %s",
                        weighed.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(JSON.readTree(weighed.getResponse().getContentAsString())
                        .get("totalMinor")
                        .asLong())
                .isEqualTo(combo + 13 * FISH_PER_QUANTUM + PACK);
        assertThat(totalOf(orderId)).isEqualTo(combo + 13 * FISH_PER_QUANTUM + PACK);
        assertThat(orderLines(orderId).stream()
                        .filter(line -> line.selectionId() != null)
                        .toList())
                .extracting(OrderLine::variantId, OrderLine::unitAmountMinor, OrderLine::finalAmountMinor)
                .as("the combo's two components keep the amounts they were checked out at")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(burgerVariant, BURGER_IN_LUNCH, BURGER_IN_LUNCH),
                        org.assertj.core.groups.Tuple.tuple(colaVariant, COLA_IN_LUNCH, COLA_IN_LUNCH));
        assertThat(jdbc.sql("SELECT actual_weight_grams FROM ordering.order_lines WHERE id = :id")
                        .param("id", fishLine)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1_300);
        assertThat(reconciles(orderId)).isTrue();
    }

    @Test
    @DisplayName("weighing a line charges its server-applied packing once, as it was before it was weighed")
    void weighingALineDoesNotChargeItsHiddenPackingTwice() throws Exception {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        put(cart, "fish", fishVariant, 1, List.of());
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        assertThat(totalOf(orderId)).isEqualTo(10 * FISH_PER_QUANTUM + PACK);
        UUID fishLine = lineOf(orderId, fishVariant);

        MvcResult weighed = weigh(orderId, fishLine, 1_300);

        assertThat(weighed.getResponse().getStatus())
                .as(weighed.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode result = JSON.readTree(weighed.getResponse().getContentAsString());
        assertThat(result.get("lineFinalAmountMinor").asLong())
                .as("13 quanta of 100 g, and one packing -- the stored packing row is the server's, not a choice")
                .isEqualTo(13 * FISH_PER_QUANTUM + PACK);
        assertThat(result.get("totalMinor").asLong()).isEqualTo(13 * FISH_PER_QUANTUM + PACK);
        assertThat(totalOf(orderId)).isEqualTo(13 * FISH_PER_QUANTUM + PACK);
        assertThat(modifierRows(orderId, true))
                .extracting(ModifierRow::optionId, ModifierRow::autoSelected)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(packOption, true));
    }

    @Test
    @DisplayName("weighing a fish on a DELIVERY order keeps the delivery box the order was checked out with")
    void weighingALineKeepsTheBoxOfADeliveryOrder() throws Exception {
        UUID cart = openCart(FulfillmentMode.DELIVERY);
        put(cart, "fish", fishVariant, 1, List.of());
        tx(() -> carts.setDestination(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                new CartService.DestinationCommand(addressId, "Dilnoza", "+998901112233", null)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        assertThat(totalOf(orderId))
                .as("the fish at its nominal weight, with the packing and, because it is delivered, the box")
                .isEqualTo(10 * FISH_PER_QUANTUM + PACK + BOX);
        UUID fishLine = lineOf(orderId, fishVariant);

        MvcResult weighed = weigh(orderId, fishLine, 1_300);

        assertThat(weighed.getResponse().getStatus())
                .as(weighed.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(totalOf(orderId))
                .as("the weight moves the fish and nothing else: still one packing and one box")
                .isEqualTo(13 * FISH_PER_QUANTUM + PACK + BOX);
        assertThat(modifierRows(orderId, true))
                .extracting(ModifierRow::optionId)
                .containsExactlyInAnyOrder(packOption, boxOption);
    }

    @Test
    @DisplayName("weighing a fish leaves a dish on the same order with a second-level choice as it was priced")
    void weighingALineKeepsANestedChoiceOnAnotherLine() throws Exception {
        UUID cart = openCart(FulfillmentMode.PICKUP);
        tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                "salad",
                saladVariant,
                1,
                List.of(chiliOption),
                null,
                List.of(),
                List.of(new CartService.NestedModifier(chiliOption, hotOption)),
                null));
        put(cart, "fish", fishVariant, 1, List.of());
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        UUID orderId = checkOut(cart);
        long salad = SALAD + CHILI + HOT;
        assertThat(totalOf(orderId)).isEqualTo(salad + 10 * FISH_PER_QUANTUM + PACK);
        UUID fishLine = lineOf(orderId, fishVariant);

        MvcResult weighed = weigh(orderId, fishLine, 1_300);

        assertThat(weighed.getResponse().getStatus())
                .as(
                        "the salad's hot chili is re-sent as a chili with its hot, not as two first-level choices: %s",
                        weighed.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(totalOf(orderId)).isEqualTo(salad + 13 * FISH_PER_QUANTUM + PACK);
    }

    // ================================================================== fixtures: acting

    private UUID openCart(FulfillmentMode mode) {
        return tx(() -> carts.create(TENANT, BRAND, LOCATION, "STOREFRONT", mode, CUSTOMER, null))
                .cartId();
    }

    private void put(UUID cart, String key, UUID variant, int quantity, List<CartService.ComboPick> picks) {
        tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                key,
                variant,
                quantity,
                List.of(),
                null,
                picks,
                List.of(),
                null));
    }

    private static CartService.ComboPick pick(ComboComponent component, int quantity) {
        return new CartService.ComboPick(component.id(), quantity);
    }

    private static CartService.ComboPick pick(UUID componentId, int quantity) {
        return new CartService.ComboPick(componentId, quantity);
    }

    /** The code a cart refuses a line with, or null when it took it. */
    private @Nullable String refusalOf(
            UUID cart, String key, UUID variant, List<CartService.ComboPick> picks, List<UUID> options) {
        Throwable thrown = catchThrowable(() -> tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                key,
                variant,
                1,
                options,
                null,
                picks,
                List.of(),
                null)));
        if (thrown == null) {
            return null;
        }
        assertThat(thrown).isInstanceOf(CartService.CartRefusedException.class);
        return ((CartService.CartRefusedException) thrown).code();
    }

    private @Nullable String refusalOf(UUID cart, String key, UUID variant, List<CartService.ComboPick> picks) {
        return refusalOf(cart, key, variant, picks, List.of());
    }

    private int cartVersion(UUID cartId) {
        return cartStore.find(TENANT, BRAND, cartId).orElseThrow().version();
    }

    private CheckoutService.CheckoutResult tryCheckOut(UUID cart) {
        var row = cartStore.find(TENANT, BRAND, cart).orElseThrow();
        return tx(() -> checkout.checkout(new CheckoutService.CheckoutCommand(
                TENANT,
                BRAND,
                cart,
                row.version(),
                Objects.requireNonNull(row.pricingQuoteId(), "the cart was priced first"),
                Objects.requireNonNull(row.pricingContextHash(), "the cart was priced first"),
                "idem-" + UUID.randomUUID(),
                "CASH",
                0L,
                "CUSTOMER",
                CUSTOMER.toString(),
                null,
                null,
                false)));
    }

    private UUID checkOut(UUID cart) {
        var result = tryCheckOut(cart);
        assertThat(result.created())
                .as("the checkout was refused: %s %s", result.rejectionCode(), result.rejectionDetail())
                .isTrue();
        return Objects.requireNonNull(result.orderId());
    }

    private JsonNode amend(UUID orderId, String key, String commandJson) throws Exception {
        JsonNode placed = orderDetail(orderId);
        MvcResult proposed = mvc.perform(post(orderPath(orderId) + "/amendments")
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key)
                        .header(
                                "If-Match",
                                "\"" + placed.get("summary").get("version").asInt() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commands\":[" + commandJson + "],\"applyImmediately\":false,"
                                + "\"reasonCode\":\"OPERATOR_EDIT\"}"))
                .andReturn();
        assertThat(proposed.getResponse().getStatus())
                .as(proposed.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode preview = JSON.readTree(proposed.getResponse().getContentAsString());
        assertThat(preview.get("status").asText()).isEqualTo("PRICED");

        MvcResult confirmed = mvc.perform(post(orderPath(orderId) + "/amendments/"
                                + preview.get("amendmentId").asText() + "/confirmation")
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key + "-confirm")
                        .header(
                                "If-Match",
                                "\"" + preview.get("amendmentVersion").asInt() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"PHONE\"}"))
                .andReturn();
        assertThat(confirmed.getResponse().getStatus())
                .as(confirmed.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(confirmed.getResponse().getContentAsString());
    }

    private MvcResult weigh(UUID orderId, UUID lineId, int grams) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put(orderPath(orderId) + "/lines/" + lineId + "/actual-weight")
                        .with(tokenFor(OPERATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "weigh-" + UUID.randomUUID())
                        .header(
                                "If-Match",
                                "\""
                                        + orderDetail(orderId)
                                                .get("summary")
                                                .get("version")
                                                .asInt() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actualWeightGrams\":" + grams + "}"))
                .andReturn();
    }

    /** Deletes the kitchen ticket an order has, so the opener can build it again. */
    private void removeTicketOf(UUID orderId) {
        for (String table : List.of("kitchen.ticket_events", "kitchen.ticket_items")) {
            jdbc.sql("DELETE FROM " + table
                            + " WHERE ticket_id IN (SELECT id FROM kitchen.tickets WHERE order_id = :id)")
                    .param("id", orderId)
                    .update();
        }
        jdbc.sql("DELETE FROM kitchen.tickets WHERE order_id = :id")
                .param("id", orderId)
                .update();
    }

    private UUID lineOf(UUID orderId, UUID variantId) {
        return orderLines(orderId).stream()
                .filter(line -> line.variantId().equals(variantId))
                .findFirst()
                .orElseThrow()
                .lineId();
    }

    private String orderPath(UUID orderId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/orders/" + orderId;
    }

    private JsonNode kitchenRead(String path) throws Exception {
        MvcResult result = mvc.perform(get(path).with(tokenFor(OPERATOR))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode orderDetail(UUID orderId) throws Exception {
        MvcResult result =
                mvc.perform(get(orderPath(orderId)).with(tokenFor(OPERATOR))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    // ================================================================= fixtures: reading

    private record OrderLine(
            UUID lineId,
            UUID variantId,
            int quantity,
            long unitAmountMinor,
            long finalAmountMinor,
            @Nullable UUID selectionId,
            @Nullable UUID containerVariantId,
            @Nullable String comboName,
            @Nullable UUID componentId,
            @Nullable Integer comboQuantity,
            @Nullable Integer pickQuantity) {}

    private List<OrderLine> orderLines(UUID orderId) {
        return jdbc.sql("""
                        SELECT id, source_variant_id, quantity, unit_amount_minor, final_amount_minor,
                               combo_selection_id, combo_container_variant_id, combo_name_snapshot,
                               combo_component_id, combo_quantity, combo_pick_quantity
                        FROM ordering.order_lines
                        WHERE order_id = :id AND revision_to IS NULL
                        ORDER BY line_number
                        """)
                .param("id", orderId)
                .query((row, number) -> new OrderLine(
                        row.getObject("id", UUID.class),
                        row.getObject("source_variant_id", UUID.class),
                        row.getInt("quantity"),
                        row.getLong("unit_amount_minor"),
                        row.getLong("final_amount_minor"),
                        row.getObject("combo_selection_id", UUID.class),
                        row.getObject("combo_container_variant_id", UUID.class),
                        row.getString("combo_name_snapshot"),
                        row.getObject("combo_component_id", UUID.class),
                        row.getObject("combo_quantity", Integer.class),
                        row.getObject("combo_pick_quantity", Integer.class)))
                .list();
    }

    private record ModifierRow(
            UUID id, UUID optionId, String name, @Nullable UUID parentId, boolean autoSelected) {}

    private List<ModifierRow> modifierRows(UUID orderId) {
        return modifierRows(orderId, false);
    }

    /** The modifier rows of the order's live lines (or, with {@code all}, of every line it ever had). */
    private List<ModifierRow> modifierRows(UUID orderId, boolean liveOnly) {
        return jdbc.sql("""
                        SELECT m.id, m.source_option_id, m.option_name_snapshot,
                               m.parent_order_line_modifier_id, m.auto_selected
                        FROM ordering.order_line_modifiers m
                        JOIN ordering.order_lines l ON l.id = m.order_line_id AND l.tenant_id = m.tenant_id
                        WHERE l.order_id = :id AND (:live = false OR l.revision_to IS NULL)
                        ORDER BY l.line_number, m.option_name_snapshot
                        """)
                .param("id", orderId)
                .param("live", liveOnly)
                .query((row, number) -> new ModifierRow(
                        row.getObject("id", UUID.class),
                        row.getObject("source_option_id", UUID.class),
                        row.getString("option_name_snapshot"),
                        row.getObject("parent_order_line_modifier_id", UUID.class),
                        row.getBoolean("auto_selected")))
                .list();
    }

    private long totalOf(UUID orderId) {
        return jdbc.sql("SELECT total_minor FROM ordering.orders WHERE id = :id")
                .param("id", orderId)
                .query(Long.class)
                .single();
    }

    private boolean reconciles(UUID orderId) {
        return jdbc.sql("""
                        SELECT subtotal_minor + tax_minor + fee_minor - discount_minor = total_minor
                        FROM ordering.orders WHERE id = :id
                        """).param("id", orderId).query(Boolean.class).single();
    }

    /** Stock held for the order's quotes, by variant, in units: every quote the order was priced under. */
    private Map<UUID, Integer> reservedUnitsByVariant(UUID orderId) {
        Map<UUID, Integer> held = new java.util.HashMap<>();
        jdbc.sql("""
                        SELECT si.variant_id, sum(rl.quantity)::integer AS units
                        FROM inventory.reservation_lines rl
                        JOIN inventory.stock_items si ON si.id = rl.stock_item_id AND si.tenant_id = rl.tenant_id
                        JOIN inventory.reservations r ON r.id = rl.reservation_id AND r.tenant_id = rl.tenant_id
                        WHERE r.tenant_id = :tenantId
                        GROUP BY si.variant_id
                        """)
                .param("tenantId", TENANT)
                .query((row, number) -> Map.entry(row.getObject("variant_id", UUID.class), row.getInt("units")))
                .list()
                .forEach(entry -> held.put(entry.getKey(), entry.getValue()));
        return held;
    }

    private static TicketItemRow itemFor(List<TicketItemRow> items, OrderLine line) {
        return items.stream()
                .filter(item -> item.orderLineId().equals(line.lineId()))
                .findFirst()
                .orElseThrow();
    }

    // ================================================================= the till

    /** What the till was sent for this order, with the given variants not mapped on it. */
    private PosAdapter.OrderExport exportToTheTill(UUID orderId, Set<UUID> unmapped) {
        FakePosAdapter adapter = spy(new FakePosAdapter());
        var result = openAndSend(orderId, new Mappings(unmapped), adapter);
        assertThat(result.error()).as("the export was refused").isNull();
        ArgumentCaptor<PosAdapter.OrderExport> sent = ArgumentCaptor.forClass(PosAdapter.OrderExport.class);
        verify(adapter).exportOrder(any(), sent.capture());
        return sent.getValue();
    }

    private record SendResult(@Nullable String error) {}

    private SendResult openAndSend(UUID orderId, Mappings mappings, FakePosAdapter adapter) {
        seedTill();
        TransactionTemplate unitOfWork = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        var service = new PosOrderExportService(
                new PosAdapterRegistry(List.of(adapter)),
                new SingleBinding(),
                mappings,
                posBindingConfiguration,
                posExportStore,
                posCapabilityStore,
                posOrderSource,
                packageCodes,
                event -> {},
                new RecordingProviderActivityRecorder(),
                Clock.systemUTC(),
                unitOfWork,
                uz.horecaos.platform.support.StaffDirectories.none());
        UUID exportId = service.open(TENANT, orderId).orElseThrow();
        service.send(TENANT, exportId);
        var detail = posExportStore.findDetailByOrder(TENANT, orderId).orElseThrow();
        assertThat(detail.exportId()).isEqualTo(exportId);
        return new SendResult(detail.lastErrorCode());
    }

    /** Maps every id except the ones it is told not to, with the till's own ids derived from ours. */
    private final class Mappings implements ProviderEntityMappingLookup {

        private final Set<UUID> unmapped;

        Mappings(Set<UUID> unmapped) {
            this.unmapped = new HashSet<>(unmapped);
        }

        @Override
        public Optional<String> externalIdFor(UUID bindingId, String entityType, UUID horecaosEntityId) {
            if (!"VARIANT".equals(entityType) && !"MODIFIER".equals(entityType)) {
                return Optional.empty();
            }
            return unmapped.contains(horecaosEntityId) ? Optional.empty() : Optional.of("ext-" + horecaosEntityId);
        }

        @Override
        public Optional<UUID> horecaosIdFor(UUID bindingId, String entityType, String externalId) {
            return Optional.empty();
        }
    }

    /** The one POS binding of the fixture branch, whichever capability asks. */
    private static final class SingleBinding implements ProviderInstallationLookup {

        private static final BindingRef REF = new BindingRef(
                POS_BINDING,
                POS_INSTALLATION,
                TENANT,
                ProviderCategory.POS,
                FakePosAdapter.PROVIDER_TYPE,
                BRAND,
                LOCATION);

        @Override
        public Optional<BindingRef> primaryBinding(
                UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
            return TENANT.equals(tenantId) && PosCapability.ORDER_EXPORT.code().equals(capabilityCode)
                    ? Optional.of(REF)
                    : Optional.empty();
        }

        @Override
        public Optional<BindingRef> binding(UUID tenantId, UUID bindingId) {
            return TENANT.equals(tenantId) && POS_BINDING.equals(bindingId) ? Optional.of(REF) : Optional.empty();
        }

        @Override
        public List<BindingRef> candidateBindings(
                UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
            return primaryBinding(tenantId, brandId, locationId, capabilityCode)
                    .map(List::of)
                    .orElse(List.of());
        }

        @Override
        public Optional<InstallationSnapshot> installation(UUID tenantId, UUID installationId) {
            return Optional.empty();
        }
    }

    // ================================================================= fixtures: seeding

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'combo-flow', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Branch', 'Asia/Tashkent',
                    'ACTIVE', 0, 41.311081, 69.240562, 'MERCHANT_PIN')
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

        UUID channel = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE', false)
                """).param("id", channel).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                VALUES (:tenantId, :channelId, :locationId, 'ACTIVE')
                """)
                .param("tenantId", TENANT)
                .param("channelId", channel)
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

        addressId = insertAddress();
    }

    /**
     * The till the export goes to: an installation and a binding, so the export row has something
     * to reference. Seeded when a test exports and not before, so the application's own
     * {@code OrderConfirmed} export trigger finds no till when the order is confirmed and opens
     * nothing that would hold an amendment back.
     */
    private void seedTill() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('combo-flow-pos-env', 'POS', :type, 'https://provider.example', false, 'provider.example')
                ON CONFLICT DO NOTHING
                """).param("type", FakePosAdapter.PROVIDER_TYPE).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status,
                     secret_reference, non_sensitive_config)
                VALUES (:id, :tenantId, 'POS', :type, 'combo-flow-pos-env', 'Fake till', 'ACTIVE',
                        'horecaos:local:pos:fake:key', '{"venueId": "3"}'::jsonb)
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", POS_INSTALLATION)
                .param("tenantId", TENANT)
                .param("type", FakePosAdapter.PROVIDER_TYPE)
                .update();
        jdbc.sql("""
                INSERT INTO integration.bindings
                    (id, tenant_id, installation_id, brand_id, location_id, status, priority, effective_from)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE', 100, :from)
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", POS_BINDING)
                .param("tenantId", TENANT)
                .param("installationId", POS_INSTALLATION)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("from", Instant.now().minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedDeliveryZone() {
        CurrentActor currentActor = () -> new AuthenticatedActor("combo-flow-zone-author", Set.of(), Map.of());
        UUID actor = UUID.randomUUID();
        var zoneStore = new JdbcServiceZoneStore(jdbc);
        var tariffStore = new JdbcDeliveryTariffStore(jdbc);
        var zones = new ServiceZoneService(
                zoneStore, JsonMapper.builder().build(), Clock.systemUTC(), fact -> {}, currentActor);
        var tariffs = new DeliveryTariffService(tariffStore, Clock.systemUTC(), fact -> {}, currentActor);

        UUID tariffId = tariffs.createTariff(TENANT, BRAND, "FLAT-ZERO", "FLAT-ZERO", false);
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
                        List.of(new TariffBand(0, 0, 10_000, 0L, 0L)),
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

    /** The lunch box, its three components, a salad with a hidden delivery box, and a chili sauce with a heat choice. */
    private void seedMenu() {
        catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);

        var lunch = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "LUNCH",
                "Lunch box",
                null,
                LOCALE,
                "SKU-LUNCH",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);
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
                classified("PKG-BURGER", "Burger"),
                ACTOR);
        var wrap = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "WRAP",
                "Wrap",
                null,
                LOCALE,
                "SKU-WRAP",
                "PIECE",
                classified("PKG-WRAP", "Wrap"),
                ACTOR);
        var cola = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "COLA",
                "Cola",
                null,
                LOCALE,
                "SKU-COLA",
                "PIECE",
                classified("PKG-COLA", "Cola"),
                ACTOR);
        var salad = authoring.createProduct(
                TENANT, BRAND, catalogId, "SALAD", "Salad", null, LOCALE, "SKU-SALAD", "PIECE", UNCLASSIFIED, ACTOR);
        var sauce = authoring.createProduct(
                TENANT, BRAND, catalogId, "SAUCE", "Sauce", null, LOCALE, "SKU-SAUCE", "PIECE", UNCLASSIFIED, ACTOR);
        var fish = authoring.createProduct(
                TENANT, BRAND, catalogId, "FISH", "Fish", null, LOCALE, "SKU-FISH", "PIECE", UNCLASSIFIED, ACTOR);
        fishVariant = fish.defaultVariantId();
        lunchVariant = lunch.defaultVariantId();
        burgerVariant = burger.defaultVariantId();
        wrapVariant = wrap.defaultVariantId();
        colaVariant = cola.defaultVariantId();
        saladVariant = salad.defaultVariantId();
        saladProduct = salad.productId();

        mainGroup = composites.createComboGroup(
                new NewComboGroup(TENANT, BRAND, lunchVariant, "MAIN", "Choose a main", LOCALE, 1, 1, false, 0),
                TESTER);
        drinkGroup = composites.createComboGroup(
                new NewComboGroup(TENANT, BRAND, lunchVariant, "DRINK", "Choose a drink", LOCALE, 1, 1, false, 1),
                TESTER);
        burgerInLunch = composites.addComponent(TENANT, BRAND, mainGroup.id(), burgerVariant, 1, 0, TESTER);
        wrapInLunch = composites.addComponent(TENANT, BRAND, mainGroup.id(), wrapVariant, 1, 1, TESTER);
        colaInLunch = composites.addComponent(TENANT, BRAND, drinkGroup.id(), colaVariant, 1, 0, TESTER);

        // The delivery box: one required option in a group attached to the salad as HIDDEN_AUTO_SELECT, delivery only.
        UUID boxGroup = authoring.createModifierGroup(TENANT, BRAND, "BOX", "Delivery box", LOCALE, true, 1, 1, false);
        boxOption = authoring.addModifierOption(
                TENANT, BRAND, boxGroup, "BOX-OPT", "Delivery box", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, saladProduct, boxGroup, 0);
        composites.setAttachmentPolicy(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                saladProduct,
                boxGroup,
                1,
                new AttachmentPolicy(Visibility.HIDDEN_AUTO_SELECT, Set.of(FulfillmentMode.DELIVERY), null, null, null),
                TESTER);

        // The sauce option links a variant that carries a required heat choice of its own: one level of nesting.
        UUID heat = authoring.createModifierGroup(TENANT, BRAND, "HEAT", "Heat", LOCALE, true, 1, 1, false);
        mildOption = authoring.addModifierOption(
                TENANT, BRAND, heat, "MILD", "Mild", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        hotOption =
                authoring.addModifierOption(TENANT, BRAND, heat, "HOT", "Hot", LOCALE, null, 1, 1, UNCLASSIFIED, ACTOR);
        composites.attachModifierGroupToVariant(TENANT, BRAND, sauce.defaultVariantId(), heat, 0, TESTER);
        UUID sauces = authoring.createModifierGroup(TENANT, BRAND, "SAUCES", "Sauces", LOCALE, false, 0, 1, false);
        chiliOption = authoring.addModifierOption(
                TENANT, BRAND, sauces, "CHILI", "Chili", LOCALE, sauce.defaultVariantId(), 1, 0, UNCLASSIFIED, ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, saladProduct, sauces, 1);

        // The fish is weighed at the pass (ADR 0137): priced per 100 g, quoted at 1,000 g. It carries a
        // packing charge the server applies in every mode, and the delivery box besides on a DELIVERY order.
        physical.replace(
                TENANT,
                BRAND,
                fishVariant,
                new PhysicalAttributes(null, null, true, 100, 1_000, false, null, null, null, null, null),
                0,
                TESTER);
        UUID packGroup = authoring.createModifierGroup(TENANT, BRAND, "PACK", "Packing", LOCALE, true, 1, 1, false);
        packOption = authoring.addModifierOption(
                TENANT, BRAND, packGroup, "PACK-OPT", "Packing", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, fish.productId(), packGroup, 0);
        composites.setAttachmentPolicy(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                fish.productId(),
                packGroup,
                1,
                new AttachmentPolicy(Visibility.HIDDEN_AUTO_SELECT, null, null, null, null),
                TESTER);
        authoring.attachModifierGroup(TENANT, BRAND, fish.productId(), boxGroup, 1);
        composites.setAttachmentPolicy(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                fish.productId(),
                boxGroup,
                1,
                new AttachmentPolicy(Visibility.HIDDEN_AUTO_SELECT, Set.of(FulfillmentMode.DELIVERY), null, null, null),
                TESTER);
    }

    private static FiscalClassification classified(String packageCode, String name) {
        return new FiscalClassification(
                "10001001001000000",
                packageCode,
                1,
                name,
                null,
                false,
                FiscalClassification.MarkingScheme.NONE,
                false,
                null,
                null);
    }

    private void seedPricing() {
        priceBook = UUID.randomUUID();
        var from = java.time.OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC);
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

        price("VARIANT", burgerVariant, BURGER);
        price("VARIANT", wrapVariant, 28_000L);
        price("VARIANT", colaVariant, COLA);
        price("VARIANT", saladVariant, SALAD);
        price("VARIANT", fishVariant, FISH_PER_QUANTUM);
        price("VARIANT", sauceVariant(), 1_000L);
        price("COMBO_COMPONENT", burgerInLunch.id(), BURGER_IN_LUNCH);
        price("COMBO_COMPONENT", wrapInLunch.id(), WRAP_IN_LUNCH);
        price("COMBO_COMPONENT", colaInLunch.id(), COLA_IN_LUNCH);
        price("MODIFIER_OPTION", boxOption, BOX);
        price("MODIFIER_OPTION", packOption, PACK);
        price("MODIFIER_OPTION", chiliOption, CHILI);
        price("MODIFIER_OPTION", mildOption, 0L);
        price("MODIFIER_OPTION", hotOption, HOT);
    }

    private UUID sauceVariant() {
        return jdbc.sql("""
                        SELECT v.id FROM catalog.variants v JOIN catalog.products p ON p.id = v.product_id
                        WHERE p.tenant_id = :tenantId AND p.code = 'SAUCE'
                        """).param("tenantId", TENANT).query(UUID.class).single();
    }

    private void price(String type, UUID priceableId, long amountMinor) {
        jdbc.sql("""
                INSERT INTO pricing.prices (id, tenant_id, brand_id, price_book_id, priceable_type,
                    priceable_id, amount_minor, valid_from)
                VALUES (:id, :tenantId, :brandId, :book, :type, :priceableId, :amount, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("book", priceBook)
                .param("type", type)
                .param("priceableId", priceableId)
                .param("amount", amountMinor)
                .param(
                        "from",
                        java.time.OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC))
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

    private void seedStock() {
        for (UUID variant : List.of(burgerVariant, wrapVariant, colaVariant, saladVariant, fishVariant)) {
            inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, variant, TrackingMode.BINARY);
        }
    }

    private void setAvailable(UUID variantId, boolean available) {
        tx(() -> inventory.setAvailability(TENANT, LOCATION, variantId, available, "combo-flow-test", ACTOR));
    }

    /** A weekly window that excludes every moment but three minutes at night, so it is never now in a test. */
    private void closeSaleWindow(UUID variantId) {
        for (int day = 1; day <= 7; day++) {
            jdbc.sql("""
                    INSERT INTO catalog.item_sale_windows (
                        id, tenant_id, brand_id, location_id, variant_id, day_of_week, opens_at, closes_at)
                    VALUES (:id, :tenantId, :brandId, :locationId, :variantId, :day, '03:00', '03:03')
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("locationId", LOCATION)
                    .param("variantId", variantId)
                    .param("day", day)
                    .update();
        }
    }

    private void offer(UUID variantId) {
        jdbc.sql("""
                INSERT INTO catalog.location_offerings (id, tenant_id, brand_id, location_id, variant_id, status)
                VALUES (:id, :tenantId, :brandId, :locationId, :variantId, 'AVAILABLE')
                ON CONFLICT (location_id, variant_id) DO UPDATE SET status = EXCLUDED.status
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("variantId", variantId)
                .update();
    }

    private void seedKitchen() {
        grill = stations.create(new NewStation(
                        TENANT, BRAND, LOCATION, "GRILL", StationRole.GRILL, "Гриль", "Gril", "Grill", 0, false))
                .id();
        bar = stations.create(
                        new NewStation(TENANT, BRAND, LOCATION, "BAR", StationRole.BAR, "Бар", "Bar", "Bar", 1, false))
                .id();
        stations.create(new NewStation(
                TENANT, BRAND, LOCATION, "PASS", StationRole.EXPO, "Раздача", "Tarqatish", "Pass", 2, true));
        stations.route(new NewRoutingRule(TENANT, BRAND, LOCATION, burgerVariant, null, null, null, grill));
        stations.route(new NewRoutingRule(TENANT, BRAND, LOCATION, colaVariant, null, null, null, bar));
    }

    // ================================================================= plumbing

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
                        'ACTIVE', 'test-fixture', 'combo order flow test', :validFrom)
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

    private static final String CUSTOMER_SUBJECT = "combo-flow-customer";

    /** The issuer the platform trusts for customer sign-ins, so a link under it is found. */
    @org.springframework.beans.factory.annotation.Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String customerIssuer;

    /** The customer's own sign-in, linked to the account the cart belongs to. */
    private void linkCustomerPrincipal() {
        jdbc.sql("""
                INSERT INTO customer.principal_links (
                    id, tenant_id, customer_account_id, issuer, subject, status, linked_at)
                VALUES (:id, :tenantId, :accountId, :issuer, :subject, 'ACTIVE', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("accountId", CUSTOMER)
                .param("issuer", customerIssuer)
                .param("subject", CUSTOMER_SUBJECT)
                .update();
    }

    private RequestPostProcessor customerToken() {
        return jwt().jwt(builder -> builder.issuer(customerIssuer).subject(CUSTOMER_SUBJECT));
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }
}
