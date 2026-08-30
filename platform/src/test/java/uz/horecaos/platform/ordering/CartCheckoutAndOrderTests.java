package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.migration.application.MigrationOwnershipService;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcMigrationScopeStore;
import uz.horecaos.platform.ordering.api.OrderAwaitingApproval;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.ordering.api.OrderReceived;
import uz.horecaos.platform.ordering.api.OrderingEvent;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.ordering.application.CheckoutService;
import uz.horecaos.platform.ordering.application.OrderAcceptancePolicyService;
import uz.horecaos.platform.ordering.application.OrderInventoryProcess;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.application.OrderStateService;
import uz.horecaos.platform.ordering.domain.AcceptanceMode;
import uz.horecaos.platform.ordering.domain.ApprovalChannel;
import uz.horecaos.platform.ordering.domain.ApprovalTimeoutAction;
import uz.horecaos.platform.ordering.domain.CartStatus;
import uz.horecaos.platform.ordering.domain.OrderAcceptancePolicy;
import uz.horecaos.platform.ordering.domain.OrderPromise;
import uz.horecaos.platform.ordering.domain.OrderStateMachine;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.domain.PromiseBasis;
import uz.horecaos.platform.ordering.infrastructure.catalog.JdbcOrderCatalogSnapshot;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCheckoutAttemptStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderDirectory;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.tenancy.JdbcOrderingTenantContext;
import uz.horecaos.platform.payments.application.PaymentBindingResolver;
import uz.horecaos.platform.payments.application.PaymentBusinessCalendar;
import uz.horecaos.platform.payments.application.PaymentFiscalService;
import uz.horecaos.platform.payments.application.PaymentIntentService;
import uz.horecaos.platform.payments.application.PaymentLegalEntityResolver;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.PaymentIntentStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PaymentTender;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.payments.settlement.CheckoutSettlementPlanner;
import uz.horecaos.platform.payments.settlement.ExecutionChannel;
import uz.horecaos.platform.payments.settlement.JdbcRemedyStore;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore;
import uz.horecaos.platform.payments.settlement.OrderConfirmedSettlementTrigger;
import uz.horecaos.platform.payments.settlement.OrderRemedyService;
import uz.horecaos.platform.payments.settlement.OrderSettlementService;
import uz.horecaos.platform.payments.settlement.SettlementStatus;
import uz.horecaos.platform.payments.settlement.TenderStatus;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.application.ServiceabilityService;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcPolicyResolver;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore;

/**
 * Stage four of the cutover: an order can be taken and approved (ADR 0019).
 *
 * <p>Runs against a real database because every property worth asserting here is
 * one that only exists there: which of two concurrent checkouts owns a quote,
 * whether an expired reservation can still be committed, whether two operators
 * deciding at once settle at one outcome, and whether a menu republish can reach
 * back into an order that has already been placed.
 *
 * <p>The services are wired by hand rather than through a Spring context, so each
 * test states exactly which collaborators are real. Every one of them is: the
 * pricing engine, the inventory ledger, the serviceability resolver and the
 * envelope encryption are all the production implementations over the production
 * schema. The only stand-in is the unwired payments port, which is a stand-in in
 * production too.
 */
class CartCheckoutAndOrderTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID OTHER_LOCATION = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID OTHER_CUSTOMER = UUID.randomUUID();

    /** A Friday at noon Tashkent time, comfortably inside every seeded schedule. */
    private static final Instant NOW = Instant.parse("2026-08-21T07:00:00Z");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private MutableClock clock;
    private RecordingEventPublisher published;

    private CartService carts;
    private CheckoutService checkout;
    private OrderStateService orderState;
    private OrderQueryService orderQuery;
    private OrderInventoryProcess inventoryProcess;
    private InventoryService inventory;
    private QuoteService quotes;
    private JdbcOrderStore orderStore;
    private JdbcCartStore cartStore;
    private FieldProtection protection;
    private ObjectMapper objectMapper;
    private uz.horecaos.platform.ordering.infrastructure.JdbcDeliveryOrderPort deliveryOrders;
    private java.util.function.Function<PaymentIntentPort, CheckoutService> checkoutWith;
    private JdbcPaymentIntentStore intentStore;
    private JdbcPaymentAttemptStore paymentAttemptStore;
    private JdbcFiscalDocumentStore fiscalStore;
    private JdbcSettlementStore settlementStore;
    private OrderSettlementService settlements;
    private CheckoutSettlementPlanner settlementPlanner;
    private OrderConfirmedSettlementTrigger confirmationSettles;
    private OrderRemedyService remedies;
    private uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService loyaltyAdjustments;
    private uz.horecaos.platform.loyalty.application.LoyaltyMaintenanceService loyaltySweep;
    private uz.horecaos.platform.loyalty.application.LoyaltyQueryService loyaltyBalances;
    private uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore loyaltyStore;

    /**
     * What ADR 0037's fee resolution would say this order's delivery fee was.
     *
     * <p>A field rather than a constant because the carts in this suite are priced
     * as collections and carry a zero fee, and the reimbursement ceiling is worth
     * asserting against a fee that exists.
     */
    private java.util.OptionalLong deliveryFeeBasis = java.util.OptionalLong.empty();

    private UUID burgerVariant;
    private UUID pizzaVariant;
    private UUID catalogId;
    private UUID storefrontChannel;
    private UUID publicationId;
    private UUID sizeGroup;
    private UUID sizeSmall;
    private UUID sizeMedium;
    private UUID sizeLarge;
    private UUID extrasBacon;
    private final Map<String, UUID> productIdByCode = new java.util.HashMap<>();

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for cart, checkout and order tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        PendingCartFulfillmentSchema.apply(jdbc);

        jdbc.sql("""
                TRUNCATE TABLE ordering.order_process_states, ordering.order_timers,
                    ordering.approval_decisions, ordering.order_state_history,
                    ordering.order_customer_snapshots, ordering.order_adjustments,
                    ordering.order_line_modifiers, ordering.order_lines, ordering.orders,
                    ordering.order_number_counters, ordering.checkout_attempts,
                    ordering.cart_lines, ordering.carts CASCADE
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
                    catalog.location_offerings, catalog.translations, catalog.catalog_products,
                    catalog.variants, catalog.products, catalog.catalogs CASCADE
                """).update();
        jdbc.sql("""
                TRUNCATE TABLE fulfillment.delivery_sourcing_jobs,
                    fulfillment.delivery_plans CASCADE
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
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.outbox_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();

        clock = new MutableClock(NOW);
        published = new RecordingEventPublisher();
        objectMapper = JsonMapper.builder().build();

        // ADR 0046's settlement stack, production classes end to end: the real
        // loyalty ledger behind the balance tender, the real settlement service,
        // and the real planner ordering calls. Nothing here is a stand-in, because
        // a stand-in is how this seam stayed broken — the settlement tests planned
        // settlements themselves and so never asked whether anything else did.
        loyaltyStore = new uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore(jdbc);
        var redemption = new uz.horecaos.platform.loyalty.application.PointsRedemptionService(
                loyaltyStore, new uz.horecaos.platform.loyalty.application.LoyaltyPolicyService(loyaltyStore), clock);
        loyaltyAdjustments = new uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService(
                loyaltyStore, ALWAYS_APPROVES, new JdbcAuditRecorder(jdbc, objectMapper), clock, 100_000L);
        settlementStore = new JdbcSettlementStore(jdbc);
        intentStore = new JdbcPaymentIntentStore(jdbc);
        paymentAttemptStore = new JdbcPaymentAttemptStore(jdbc);
        settlements = new OrderSettlementService(settlementStore, redemption, clock);
        settlementPlanner = new CheckoutSettlementPlanner(settlementStore, settlements, clock);
        loyaltyBalances = new uz.horecaos.platform.loyalty.application.LoyaltyQueryService(loyaltyStore, clock);
        // The production sweep, over the production store. The hold this suite
        // takes at checkout is the hold this sweep decides about, and the two
        // were never in the same test before.
        loyaltySweep = new uz.horecaos.platform.loyalty.application.LoyaltyMaintenanceService(
                loyaltyStore,
                redemption,
                new uz.horecaos.platform.payments.settlement.HeldTenderProgress(
                        settlementStore,
                        new JdbcOrderDirectory(new JdbcOrderStore(jdbc)),
                        intentStore,
                        paymentAttemptStore,
                        clock),
                clock);
        confirmationSettles = new OrderConfirmedSettlementTrigger(settlementPlanner);
        // A BEFORE_COMMIT listener in production; invoked here at publication,
        // which is the same transaction and the same order relative to the writes
        // that matter.
        published.onConfirmed = confirmationSettles::onOrderConfirmed;

        var pricingStore = new JdbcPricingStore(jdbc, objectMapper);
        var inventoryStore = new JdbcInventoryStore(jdbc);
        var channelStore = new JdbcSalesChannelStore(jdbc);
        var serviceabilityStore = new JdbcServiceabilityStore(jdbc);

        inventory = new InventoryService(inventoryStore, clock);
        // ADR 0037. The real resolver, so a cart travels the production path rather
        // than a stand-in that could not refuse anything. It is not reached by the
        // carts below even where they are deliveries: CartPricingPort still carries
        // no destination to pricing, so a cart priced through it is priced as a
        // collection and never enters fee resolution. That is the remaining gap
        // between a delivery order and a delivery order with a fee on it.
        var deliveryFees = new uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver(
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore(
                        jdbc, objectMapper),
                (origin, destination, installationId) -> java.util.Optional.empty(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        quotes = new QuoteService(
                pricingStore,
                new PricingEngine(),
                new JdbcCatalogPricingContext(jdbc, "uz"),
                channelStore,
                deliveryFees,
                clock);
        var serviceability = new ServiceabilityService(serviceabilityStore, clock);

        cartStore = new JdbcCartStore(jdbc);
        orderStore = new JdbcOrderStore(jdbc);
        fiscalStore = new JdbcFiscalDocumentStore(jdbc);
        var attemptStore = new JdbcCheckoutAttemptStore(jdbc);
        var processStore = new JdbcOrderProcessStore(jdbc);

        // Real envelope encryption over a throwaway key, so the ADR 0029 row
        // binding is genuinely exercised when a cart note is carried onto an order
        // rather than stubbed into agreeing with itself.
        protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-kek")::get, clock),
                "local"));

        var tenantContext = new JdbcOrderingTenantContext(jdbc);
        var catalogSnapshot = new JdbcOrderCatalogSnapshot(jdbc, "uz");
        var policies = new OrderAcceptancePolicyService(new JdbcPolicyResolver(jdbc, objectMapper));

        carts = new CartService(
                cartStore,
                channelStore,
                new uz.horecaos.platform.ordering.infrastructure.catalog.JdbcCartMenuRules(jdbc, objectMapper),
                serviceability,
                tenantContext,
                quotes,
                new uz.horecaos.platform.ordering.infrastructure.customer.JdbcCustomerAddressBook(
                        jdbc, protection, objectMapper),
                protection,
                objectMapper,
                clock);
        inventoryProcess = new OrderInventoryProcess(processStore, inventory, objectMapper, clock);
        orderState = new OrderStateService(
                orderStore,
                serviceability,
                inventoryProcess,
                policies,
                settlementPlanner,
                new JdbcAuditRecorder(jdbc, objectMapper),
                published,
                clock);
        remedies = new OrderRemedyService(
                new JdbcRemedyStore(jdbc),
                settlements,
                new JdbcOrderDirectory(orderStore),
                (tenantId, orderId) -> deliveryFeeBasis,
                ALWAYS_APPROVES,
                new JdbcAuditRecorder(jdbc, objectMapper),
                clock,
                200_000L);
        orderQuery = new OrderQueryService(orderStore, processStore, UNWIRED_PAYMENTS, protection);
        // The real ADR 0024 gate over the real scope table, not a stub that agrees
        // with itself. No scope row is seeded, so every checkout below runs through
        // the "no scope registered" branch — which is the branch that decides
        // whether an unmigrated platform can still take an order at all.
        var migrationOwnership = new MigrationOwnershipService(
                new JdbcMigrationScopeStore(jdbc, objectMapper), new SimpleMeterRegistry());

        // One factory, so the wired-payments tests below assemble a checkout that
        // differs from the one every other test uses in exactly one collaborator.
        checkoutWith = port -> new CheckoutService(
                cartStore,
                orderStore,
                attemptStore,
                carts,
                channelStore,
                serviceability,
                serviceability,
                quotes,
                inventory,
                catalogSnapshot,
                tenantContext,
                policies,
                inventoryProcess,
                migrationOwnership,
                port,
                settlementPlanner,
                protection,
                objectMapper,
                published,
                clock);

        checkout = checkoutWith.apply(UNWIRED_PAYMENTS);
        deliveryOrders =
                new uz.horecaos.platform.ordering.infrastructure.JdbcDeliveryOrderPort(jdbc, protection, objectMapper);

        seedTenancyAndCatalog();
        seedPricingAndStock();
    }

    // ------------------------------------------------------------------- cart

    @Test
    @DisplayName("editing a line clears the attached price, so a stale quote cannot be checked out")
    void anEditInvalidatesThePrice() {
        var cart = openCart();
        putLine(cart, "a", burgerVariant, 1);

        var priced = tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        assertThat(readCart(cart).pricingQuoteId()).isEqualTo(priced.quote().quoteId());

        putLine(cart, "a", burgerVariant, 3);

        // The premise, not only the conclusion: the cart really was priced a
        // moment ago, and the edit is what removed the binding. Without this, a
        // checkout could accept a fifteen-minute-old total for a basket that has
        // since tripled.
        assertThat(readCart(cart).pricingQuoteId())
                .as("a changed basket is not the basket that was priced")
                .isNull();
    }

    @Test
    @DisplayName("a cart is rebuilt at a new location, never moved")
    void movingLocationRebuildsTheCart() {
        var cart = openCart();
        putLine(cart, "a", burgerVariant, 2);
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));

        var rebuilt =
                tx(() -> carts.rebuildAtLocation(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), OTHER_LOCATION));

        assertThat(rebuilt.cart().cartId()).isNotEqualTo(cart);
        assertThat(rebuilt.cart().locationId()).isEqualTo(OTHER_LOCATION);
        assertThat(rebuilt.lines()).hasSize(1);
        assertThat(rebuilt.cart().pricingQuoteId())
                .as("catalog, availability, tax and fee all change with the branch, so the "
                        + "old price cannot come across")
                .isNull();
        assertThat(readCart(cart).status()).isEqualTo(CartStatus.ABANDONED);
    }

    @Test
    @DisplayName("the database refuses to move a cart between locations even by hand")
    void aCartCannotBeRepointedBySql() {
        var cart = openCart();

        // The application always rebuilds. This asserts the rule survives a
        // maintainer who implements the rebuild as an UPDATE, which would silently
        // reprice nothing and show prices from the wrong branch.
        Throwable moved = catchThrowable(() -> jdbc.sql("UPDATE ordering.carts SET location_id = :other WHERE id = :id")
                .param("other", OTHER_LOCATION)
                .param("id", cart)
                .update());

        assertThat(moved).isNotNull();
        assertThat(moved.getMessage()).contains("rebuild and reprice");
    }

    // ------------------------------------------------ published modifier rules

    /**
     * The rules are authored, validated and published, and the cart is where a
     * customer's selection meets them. A basket that violates them is a ticket the
     * kitchen cannot make, and the payment step is the worst place to find out.
     */
    @Test
    @DisplayName("a required group with nothing chosen is refused when the line is added")
    void aRequiredGroupMustBeSatisfied() {
        var cart = openCart();

        var refused = catchThrowable(() -> tx(() -> carts.putLine(
                TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), "pizza", pizzaVariant, 1, List.of(), null)));

        assertThat(refused).isInstanceOf(CartService.CartRefusedException.class);
        assertThat(((CartService.CartRefusedException) refused).code()).isEqualTo("MODIFIER_GROUP_MINIMUM_NOT_MET");
        assertThat(carts.view(TENANT, BRAND, CUSTOMER, cart).orElseThrow().lines())
                .as("and the line is not left behind by a rolled-back edit")
                .isEmpty();
    }

    @Test
    @DisplayName("more selections than the group's maximum are refused")
    void aGroupsMaximumIsEnforced() {
        var cart = openCart();

        var refused = catchThrowable(() -> tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                "pizza",
                pizzaVariant,
                1,
                List.of(sizeSmall, sizeMedium, sizeLarge),
                null)));

        assertThat(((CartService.CartRefusedException) refused).code()).isEqualTo("MODIFIER_GROUP_MAXIMUM_EXCEEDED");
    }

    /**
     * The published product-to-group link is what makes this answerable. Without
     * it the cart would accept any option id the brand has ever published, priced
     * against a dish it does not belong to.
     */
    @Test
    @DisplayName("an option from a group the product does not offer is refused")
    void optionsFromAnotherProductsGroupAreRefused() {
        var cart = openCart();

        var refused = catchThrowable(() -> tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                "pizza",
                pizzaVariant,
                1,
                List.of(sizeSmall, extrasBacon),
                null)));

        assertThat(((CartService.CartRefusedException) refused).code()).isEqualTo("MODIFIER_NOT_OFFERED");
    }

    @Test
    @DisplayName("the same option twice is refused where the group forbids repeats")
    void repeatsAreRefusedWhereTheGroupForbidsThem() {
        var cart = openCart();

        var refused = catchThrowable(() -> tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                "pizza",
                pizzaVariant,
                1,
                List.of(sizeSmall, sizeSmall),
                null)));

        assertThat(((CartService.CartRefusedException) refused).code()).isEqualTo("MODIFIER_OPTION_NOT_REPEATABLE");
    }

    @Test
    @DisplayName("a selection the published menu allows goes into the cart")
    void aValidSelectionIsAccepted() {
        var cart = openCart();

        var view = tx(() -> carts.putLine(
                TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), "pizza", pizzaVariant, 1, List.of(sizeMedium), null));

        assertThat(view.lines()).hasSize(1);
        assertThat(carts.modifierIdsOf(view.lines().getFirst())).containsExactly(sizeMedium);
    }

    // ------------------------------------------------------- cart ownership

    /**
     * A cart id is a UUID in a URL, and the tenant and brand it used to be scoped
     * by are in the same URL. Nothing but the owner check stands between one and a
     * stranger's basket — which is a read of what somebody ordered, an edit of it,
     * and a price bound to it.
     */
    @Test
    @DisplayName("a cart is invisible to a customer it does not belong to")
    void aCartIsScopedToItsOwner() {
        var cart = openCart();
        putLine(cart, "a", burgerVariant, 1);

        assertThat(carts.view(TENANT, BRAND, OTHER_CUSTOMER, cart))
                .as("not a 403 either: an id that answers differently for a stranger is an id "
                        + "a stranger can enumerate")
                .isEmpty();

        var refused = catchThrowable(() -> tx(() -> carts.putLine(
                TENANT, BRAND, OTHER_CUSTOMER, cart, cartVersion(cart), "b", burgerVariant, 5, List.of(), null)));
        assertThat(((CartService.CartRefusedException) refused).code()).isEqualTo("CART_NOT_FOUND");

        var unpriceable =
                catchThrowable(() -> tx(() -> carts.price(TENANT, BRAND, OTHER_CUSTOMER, cart, cartVersion(cart))));
        assertThat(((CartService.CartRefusedException) unpriceable).code()).isEqualTo("CART_NOT_FOUND");

        assertThat(carts.view(TENANT, BRAND, CUSTOMER, cart).orElseThrow().lines())
                .as("and the owner's basket is untouched by any of it")
                .hasSize(1);
    }

    // ---------------------------------------------------------- the promise

    /**
     * No band covers this instant in the base fixture, so the branch falls back
     * rather than promising nothing. A customer always sees a time; the basis is
     * what tells the platform the branch's bands need attention.
     */
    @Test
    @DisplayName("an order with no band covering it falls back to the platform default")
    void promisesThePlatformDefaultWhenNoBandApplies() {
        var result = placeOrder("promise-default");

        var order = orderStore.find(TENANT, result.orderId()).orElseThrow();
        assertThat(order.promise().basis()).isEqualTo(PromiseBasis.PLATFORM_DEFAULT);
        assertThat(order.promise().prepMinutes()).isEqualTo(OrderPromise.DEFAULT_PREP_MINUTES);
        assertThat(order.promise().promisedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(45)));
        // Not modelled, and recorded as not modelled. A zero here would claim the
        // road takes no time and hide the order from ADR 0037's backfill.
        assertThat(order.promise().travelMinutes()).isNull();
    }

    @Test
    @DisplayName("a band covering the checkout instant governs the promise")
    void promisesFromTheBandCoveringTheInstant() {
        insertPreparationBand(LocalTime.of(11, 0), LocalTime.of(14, 0), 30);

        var result = placeOrder("promise-band");

        var order = orderStore.find(TENANT, result.orderId()).orElseThrow();
        assertThat(order.promise().basis()).isEqualTo(PromiseBasis.PREPARATION_BAND);
        assertThat(order.promise().prepMinutes()).isEqualTo(30);
        assertThat(order.promise().promisedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("a dish slower than the band stretches the promise to fit it")
    void aSlowDishStretchesThePromise() {
        insertPreparationBand(LocalTime.of(11, 0), LocalTime.of(14, 0), 30);
        insertPreparationOverride(burgerVariant, Duration.ofMinutes(50));

        var result = placeOrder("promise-override");

        var order = orderStore.find(TENANT, result.orderId()).orElseThrow();
        assertThat(order.promise().basis()).isEqualTo(PromiseBasis.ITEM_OVERRIDE);
        assertThat(order.promise().prepMinutes()).isEqualTo(50);
        assertThat(order.promise().promisedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(50)));
    }

    /**
     * The kitchen's queue does not empty because somebody ordered a salad.
     */
    @Test
    @DisplayName("a dish faster than the band does not shorten the promise")
    void aFastDishDoesNotShortenThePromise() {
        insertPreparationBand(LocalTime.of(11, 0), LocalTime.of(14, 0), 30);
        insertPreparationOverride(burgerVariant, Duration.ofMinutes(5));

        var result = placeOrder("promise-fast-dish");

        var order = orderStore.find(TENANT, result.orderId()).orElseThrow();
        assertThat(order.promise().basis()).isEqualTo(PromiseBasis.PREPARATION_BAND);
        assertThat(order.promise().prepMinutes()).isEqualTo(30);
    }

    /**
     * Lateness is derived, so it moves with the clock and with the status and needs
     * nothing written anywhere. The same stored promise answers differently as the
     * evening goes on, which is the entire reason it is not a column.
     */
    @Test
    @DisplayName("lateness follows the clock and the status without a stored flag")
    void latenessIsDerivedFromTheStoredPromise() {
        insertPreparationBand(LocalTime.of(11, 0), LocalTime.of(14, 0), 30);
        var result = placeOrder("promise-lateness");
        var order = orderStore.find(TENANT, result.orderId()).orElseThrow();

        assertThat(order.promise().lateAt(NOW.plus(Duration.ofMinutes(29)), order.status()))
                .isFalse();
        assertThat(order.promise().lateAt(NOW.plus(Duration.ofMinutes(31)), order.status()))
                .isTrue();

        advance(result.orderId(), OrderStatus.PREPARING);
        advance(result.orderId(), OrderStatus.READY);
        advance(result.orderId(), OrderStatus.COMPLETED);

        var completed = orderStore.find(TENANT, result.orderId()).orElseThrow();
        assertThat(completed.promise().promisedAt())
                .as("the promise is not rewritten as the order progresses")
                .isEqualTo(order.promise().promisedAt());
        assertThat(completed.promise().lateAt(NOW.plus(Duration.ofDays(1)), completed.status()))
                .as("a handed-over order is not sitting there being late")
                .isFalse();
    }

    /**
     * The schema refuses a promise the domain would also refuse. Belt and braces on
     * purpose: the columns are writable by migrations and by hand, and a promise
     * with a basis but no time would make every lateness query silently wrong.
     */
    @Test
    @DisplayName("the database refuses a basis without a time")
    void theSchemaRefusesAnIncoherentPromise() {
        var result = placeOrder("promise-constraint");

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE ordering.orders SET promise_basis = 'PREPARATION_BAND', promised_at = NULL
                WHERE id = :id
                """).param("id", result.orderId()).update())
                .hasMessageContaining("ck_order_promise_pairing");

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE ordering.orders SET promise_prep_minutes = NULL WHERE id = :id
                """).param("id", result.orderId()).update())
                .hasMessageContaining("ck_order_promise_components");
    }

    private void insertPreparationBand(LocalTime from, LocalTime to, int minutes) {
        jdbc.sql("""
                INSERT INTO tenant.preparation_bands (id, tenant_id, brand_id, location_id,
                    starts_at, ends_at, duration_minutes)
                VALUES (:id, :tenantId, :brandId, :locationId, :from, :to, :minutes)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("from", from)
                .param("to", to)
                .param("minutes", minutes)
                .update();
    }

    private void insertPreparationOverride(UUID variantId, Duration override) {
        jdbc.sql("""
                INSERT INTO catalog.location_offerings (id, tenant_id, brand_id, location_id,
                    variant_id, preparation_duration_override)
                VALUES (:id, :tenantId, :brandId, :locationId, :variantId,
                    make_interval(secs => :seconds))
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("variantId", variantId)
                .param("seconds", (double) override.toSeconds())
                .update();
    }

    // --------------------------------------------------------------- checkout

    @Test
    @DisplayName("a priced cart becomes an order with the totals it was quoted at")
    void aPricedCartBecomesAnOrder() {
        var result = placeOrder("idem-1");

        assertThat(result.created()).isTrue();
        assertThat(result.status()).isEqualTo(OrderStatus.CONFIRMED);

        var order = orderStore.find(TENANT, result.orderId()).orElseThrow();
        assertThat(order.totalMinor()).isEqualTo(100_000L);
        assertThat(order.subtotalMinor() + order.taxMinor()).isEqualTo(order.totalMinor());
        assertThat(order.publicOrderNumber()).isEqualTo("0821-001");
        assertThat(readCart(order.cartId()).status()).isEqualTo(CartStatus.CONVERTED);
    }

    @Test
    @DisplayName("the checkout carries the unwired payments port as a visible warning")
    void theUnwiredPaymentPortIsReportedOnEveryOrder() {
        var result = placeOrder("idem-payments");

        // The ADR 0013 gap has to appear where somebody looks, not only in a log
        // line emitted once at startup.
        assertThat(result.warnings()).contains(PaymentIntentPort.NOT_WIRED_WARNING);
        assertThat(orderQuery.detail(TENANT, result.orderId()).orElseThrow().warnings())
                .contains(PaymentIntentPort.NOT_WIRED_WARNING);
        assertThat(orderStore.find(TENANT, result.orderId()).orElseThrow().paymentStatusProjection())
                .as("no order waits on a provider that does not exist")
                .isEqualTo("NOT_REQUIRED");
    }

    // ------------------------------------------------- checkout, payments wired

    @Test
    @DisplayName("cash checks out against the real payments module and is confirmed")
    void cashChecksOutWithPaymentsWired() {
        var wired = checkoutWith.apply(realPayments(NO_SELLER));

        var result = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-cash", "CASH")));

        // The whole point of the cash decision: nothing about wiring payments may
        // stop the majority tender from checking out.
        assertThat(result.created()).isTrue();
        assertThat(result.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(result.warnings()).isEmpty();

        var order = orderStore.find(TENANT, result.orderId()).orElseThrow();
        assertThat(order.paymentStatusProjection())
                .as("nothing will ever capture a cash intent, so it must not read as pending")
                .isEqualTo("NOT_REQUIRED");

        var intent = intentStore.findLiveForOrder(TENANT, result.orderId()).orElseThrow();
        assertThat(intent.tender()).isEqualTo(PaymentTender.CASH);
        assertThat(intent.providerType()).isNull();
        assertThat(intent.amount().value()).isEqualTo(order.totalMinor());

        // ADR 0038's 2026-08-22 decision, recorded as a row rather than as the
        // absence of one.
        assertThat(fiscalStore.listForOrder(TENANT, result.orderId()))
                .singleElement()
                .satisfies(document -> {
                    assertThat(document.status()).isEqualTo(FiscalStatus.NOT_APPLICABLE);
                    assertThat(document.providerType()).isNull();
                });
    }

    @Test
    @DisplayName("a card method is refused at checkout while no seller can be resolved")
    void aCardMethodIsRefusedWithNoMerchantAccount() {
        var wired = checkoutWith.apply(realPayments(NO_SELLER));

        var result = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-click", "CLICK")));

        // ADR 0038's legal entity is unbuilt, so no binding resolves, so no
        // merchant account can take the money. Refused among the read-only
        // validations rather than after a kitchen slot and a quote were spent.
        assertThat(result.rejectionCode()).isEqualTo("PAYMENT_METHOD_UNAVAILABLE");
        assertThat(result.orderId()).isNull();
        assertThat(countOrders()).isZero();
    }

    @Test
    @DisplayName("Click holds the order in PAYMENT_AUTHORIZING instead of confirming it")
    void clickWaitsForTheMoney() {
        assertProviderMethodWaitsForPayment("CLICK", PaymentProviderType.CLICK);
    }

    @Test
    @DisplayName("Payme holds the order in PAYMENT_AUTHORIZING instead of confirming it")
    void paymeWaitsForTheMoney() {
        assertProviderMethodWaitsForPayment("PAYME", PaymentProviderType.PAYME);
    }

    /**
     * The branch ADR 0019 step 7 exists for: an order whose money must arrive
     * before the restaurant is asked is not confirmed by checkout.
     *
     * <p>This location's acceptance policy is {@code AUTO_CONFIRM}, so without the
     * payment branch the same order would leave checkout {@code CONFIRMED} and a
     * kitchen would start cooking against a card nobody has charged.
     */
    private void assertProviderMethodWaitsForPayment(String methodCode, PaymentProviderType provider) {

        var wired = checkoutWith.apply(realPayments(UUID.randomUUID()));

        var result = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-" + methodCode, methodCode)));

        assertThat(result.created()).isTrue();
        assertThat(result.status()).isEqualTo(OrderStatus.PAYMENT_AUTHORIZING);

        var order = orderStore.find(TENANT, result.orderId()).orElseThrow();
        assertThat(order.status()).isEqualTo(OrderStatus.PAYMENT_AUTHORIZING);
        assertThat(order.paymentStatusProjection()).isEqualTo("PENDING");

        var intent = intentStore.findLiveForOrder(TENANT, result.orderId()).orElseThrow();
        assertThat(intent.tender()).isEqualTo(PaymentTender.PROVIDER);
        assertThat(intent.providerType()).isEqualTo(provider);
        assertThat(intent.status()).isEqualTo(PaymentIntentStatus.PENDING);

        // No provider is called from inside the checkout transaction, so there is
        // nothing to fiscalize yet either.
        assertThat(fiscalStore.listForOrder(TENANT, result.orderId())).isEmpty();

        assertThat(published.events)
                .as("an order waiting on a card has neither been confirmed nor sent for approval")
                .noneMatch(event -> event instanceof OrderConfirmed || event instanceof OrderAwaitingApproval);
    }

    /** No ADR 0038 assignment, which is what a real build resolves today. */
    private static final UUID NO_SELLER = null;

    /**
     * The real {@link uz.horecaos.platform.payments.application.PaymentIntentService}
     * over the real tables.
     *
     * <p>Only the two ports ADR 0013 declares unbuilt are stood in for: the legal
     * entity ADR 0038 owns, and the merchant binding that hangs off it. Everything
     * that decides what the checkout does — the capture timing, the intent row, the
     * cash fiscal document — is production code against production SQL.
     */
    private PaymentIntentPort realPayments(UUID sellerId) {
        PaymentLegalEntityResolver sellers = (tenantId, locationId, businessDate) -> Optional.ofNullable(sellerId);

        PaymentBindingResolver bindings = new PaymentBindingResolver() {

            @Override
            public Optional<ProviderBinding> resolve(
                    UUID tenantId,
                    UUID legalEntityId,
                    PaymentProviderType providerType,
                    java.time.LocalDate businessDate) {
                return Optional.of(new ProviderBinding(
                        UUID.randomUUID(),
                        tenantId,
                        legalEntityId,
                        providerType,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "merchant-account",
                        null,
                        null,
                        SecretReference.parse("horecaos:local:provider_payment:tenant:secret"),
                        "callback-segment",
                        true,
                        true,
                        java.time.LocalDate.of(2020, 1, 1),
                        null));
            }

            @Override
            public Optional<ProviderBinding> byCallbackSegment(String callbackPathSegment) {
                return Optional.empty();
            }
        };

        PaymentBusinessCalendar calendar = (tenantId, locationId, at) ->
                at.atZone(ZoneId.of("Asia/Tashkent")).toLocalDate();

        return new PaymentIntentService(
                intentStore,
                new JdbcOrderDirectory(orderStore),
                sellers,
                bindings,
                calendar,
                new PaymentFiscalService(fiscalStore, List.of()),
                clock);
    }

    @Test
    @DisplayName("repeating a checkout with the same key returns the same order")
    void checkoutIsIdempotent() {
        var cart = readyCart();
        var command = checkoutCommand(cart, "idem-repeat");

        var first = tx(() -> checkout.checkout(command));
        var second = tx(() -> checkout.checkout(command));

        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(second.outcome()).isEqualTo(CheckoutService.CheckoutResult.Outcome.REPLAYED);
        assertThat(countOrders()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a different request under one idempotency key is refused, not silently replayed")
    void aReusedKeyWithADifferentRequestIsRefused() {
        var cart = readyCart();
        tx(() -> checkout.checkout(checkoutCommand(cart, "idem-reuse")));

        var other = readyCart();
        var refused = tx(() -> checkout.checkout(checkoutCommand(other, "idem-reuse")));

        // ADR 0019 rejected deriving the key from a request hash. This is the
        // opposite check: a client reusing one key for two genuinely different
        // checkouts is a client bug, and handing it the first order would be worse
        // than telling it so.
        assertThat(refused.rejectionCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(countOrders()).isEqualTo(1L);
    }

    @Test
    @DisplayName("two concurrent checkouts on one cart produce exactly one order")
    void twoConcurrentCheckoutsProduceOneOrder() throws Exception {
        var cart = readyCart();
        CountDownLatch bothReady = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<CheckoutService.CheckoutResult> first = pool.submit(() -> {
                bothReady.countDown();
                awaitQuietly(bothReady);
                return tx(() -> checkout.checkout(checkoutCommand(cart, "race-a")));
            });
            Future<CheckoutService.CheckoutResult> second = pool.submit(() -> {
                bothReady.countDown();
                awaitQuietly(bothReady);
                return tx(() -> checkout.checkout(checkoutCommand(cart, "race-b")));
            });

            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(results)
                    .filteredOn(CheckoutService.CheckoutResult::created)
                    .hasSize(1);
            assertThat(results).filteredOn(result -> !result.created()).hasSize(1);
            assertThat(countOrders())
                    .as("one basket, one order, whichever thread the scheduler favours")
                    .isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a quote that expired between pricing and checkout does not become an order")
    void anExpiredQuoteDoesNotBecomeAnOrder() {
        var cart = readyCart();

        clock.advance(Duration.ofMinutes(16));

        var refused = tx(() -> checkout.checkout(checkoutCommand(cart, "idem-expired")));

        assertThat(refused.rejectionCode()).isEqualTo("QUOTE_EXPIRED");
        assertThat(countOrders()).isZero();
        assertThat(readCart(cart).status())
                .as("a recoverable refusal leaves the customer with their basket")
                .isEqualTo(CartStatus.ACTIVE);
    }

    @Test
    @DisplayName("a reservation that lapsed mid-checkout is refused rather than committed")
    void anExpiredReservationIsNotCommitted() {
        var cart = openCart();
        putLine(cart, "a", burgerVariant, 2);
        var priced = tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));

        // A hold taken while the customer was still entering their address, which
        // then lapsed. The quote is still live because the test moves only the
        // reservation on; the point is that checkout must not treat a dead hold as
        // a live one.
        tx(() -> inventory.reserveForQuote(
                TENANT, BRAND, LOCATION, priced.quote().quoteId(), Map.of(burgerVariant, 2)));
        jdbc.sql("UPDATE inventory.reservations SET status = 'EXPIRED'").update();

        var refused = tx(() -> checkout.checkout(checkoutCommand(cart, "idem-dead-hold")));

        assertThat(refused.rejectionCode()).isEqualTo("ITEMS_UNAVAILABLE");
        assertThat(refused.unavailableItems())
                .allSatisfy(item -> assertThat(item.reason()).isEqualTo("RESERVATION_NO_LONGER_HELD"));
        assertThat(countOrders()).isZero();
    }

    @Test
    @DisplayName("a location that closed while the customer was in the basket refuses at checkout")
    void aClosedLocationRefusesAtCheckout() {
        var cart = readyCart();

        // Serviceability passed when the cart was opened and when it was priced.
        // Checkout re-resolves it from PostgreSQL inside its own transaction,
        // which is the whole reason the resolver is called more than once.
        jdbc.sql("""
                UPDATE tenant.location_service_state SET mode = 'FORCE_CLOSED',
                    reason_code = 'FRYER_FAILED' WHERE location_id = :location
                """).param("location", LOCATION).update();

        var refused = tx(() -> checkout.checkout(checkoutCommand(cart, "idem-closed")));

        assertThat(refused.rejectionCode()).isEqualTo("NOT_SERVICEABLE");
        assertThat(refused.rejectionDetail()).isEqualTo("MANUALLY_CLOSED");
        assertThat(countOrders()).isZero();
    }

    @Test
    @DisplayName("a menu republished between pricing and checkout refuses rather than repricing")
    void aRepublishedMenuRefusesCheckout() {
        var cart = readyCart();

        jdbc.sql("UPDATE catalog.publications SET status = 'RETIRED' WHERE channel = 'STOREFRONT'")
                .update();
        seedPublication("STOREFRONT");

        var refused = tx(() -> checkout.checkout(checkoutCommand(cart, "idem-republished")));

        assertThat(refused.rejectionCode()).isEqualTo("PUBLICATION_CHANGED");
        assertThat(countOrders()).isZero();
    }

    @Test
    @DisplayName("a quote priced for another cart cannot be presented at checkout")
    void aQuoteFromAnotherCartIsRefused() {
        var mine = readyCart();
        var theirs = readyCart();

        var foreignQuote = readCart(theirs).pricingQuoteId();
        var foreignHash = readCart(theirs).pricingContextHash();

        var refused = tx(() -> checkout.checkout(new CheckoutService.CheckoutCommand(
                TENANT,
                BRAND,
                mine,
                cartVersion(mine),
                foreignQuote,
                foreignHash,
                "idem-foreign",
                null,
                0L,
                "CUSTOMER",
                CUSTOMER.toString(),
                null)));

        assertThat(refused.rejectionCode()).isEqualTo("QUOTE_NOT_BOUND_TO_CART");
        assertThat(countOrders()).isZero();
    }

    @Test
    @DisplayName("a refused checkout leaves no order, no accepted quote and no capacity slot")
    void aRefusedCheckoutLeavesNothingBehind() {
        var cart = readyCart();
        jdbc.sql("""
                UPDATE tenant.location_service_state SET mode = 'FORCE_CLOSED',
                    reason_code = 'FRYER_FAILED' WHERE location_id = :location
                """).param("location", LOCATION).update();

        tx(() -> checkout.checkout(checkoutCommand(cart, "idem-nothing")));

        assertThat(countOrders()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pricing.quotes WHERE status = 'ACCEPTED'")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM tenant.location_capacity_holds WHERE released_at IS NULL
                """).query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------ snapshots

    @Test
    @DisplayName("an order's commercial snapshot survives a menu republish unchanged")
    void theOrderSnapshotSurvivesARepublish() {
        var result = placeOrder("idem-snapshot");
        var before = orderQuery.detail(TENANT, result.orderId()).orElseThrow();

        assertThat(before.lines())
                .singleElement()
                .satisfies(line -> assertThat(line.line().productName()).isEqualTo("Qo'y burger"));

        // Everything a republish could touch: the name, the price, and the
        // publication itself.
        jdbc.sql("UPDATE catalog.translations SET name = 'Renamed burger' " + "WHERE entity_type = 'PRODUCT'")
                .update();
        jdbc.sql("UPDATE pricing.prices SET amount_minor = 90000").update();
        jdbc.sql("UPDATE catalog.publications SET status = 'RETIRED'").update();
        seedPublication("STOREFRONT");

        var after = orderQuery.detail(TENANT, result.orderId()).orElseThrow();

        assertThat(after.order().totalMinor()).isEqualTo(before.order().totalMinor());
        assertThat(after.lines().getFirst().line().productName())
                .as("a receipt is a historical fact, not a live view of the menu")
                .isEqualTo("Qo'y burger");
    }

    @Test
    @DisplayName("the order line amounts cannot be edited by the application role")
    void orderLinesAreNotUpdatable() {
        var result = placeOrder("idem-immutable");

        assertThat(jdbc.sql("""
                SELECT has_table_privilege('horecaos_application', 'ordering.order_lines', 'UPDATE')
                """).query(Boolean.class).single())
                .as("a correction is a new order, never an edit to financial history")
                .isFalse();
        assertThat(result.orderId()).isNotNull();
    }

    @Test
    @DisplayName("a customer note is carried onto the order and stays readable there")
    void aCustomerNoteIsReEncryptedOntoTheOrder() {
        var cart = openCart();
        tx(() -> carts.putLine(
                TENANT,
                BRAND,
                CUSTOMER,
                cart,
                cartVersion(cart),
                "a",
                burgerVariant,
                2,
                List.of(),
                "No onions, ring the top bell"));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));

        var result = tx(() -> checkout.checkout(checkoutCommand(cart, "idem-note")));
        var detail = orderQuery.detail(TENANT, result.orderId()).orElseThrow();
        UUID lineId = detail.lines().getFirst().line().lineId();

        // The ciphertext is bound to its row by the ADR 0029 associated data, so a
        // note merely copied across would be unreadable here. Reading it back
        // proves it was re-encrypted for the order line rather than pasted.
        assertThat(detail.lines().getFirst().line().hasNote()).isTrue();
        assertThat(orderQuery.revealLineNote(TENANT, result.orderId(), lineId, "KITCHEN_TICKET"))
                .contains("No onions, ring the top bell");
    }

    @Test
    @DisplayName("a customer cannot read another customer's order")
    void anOrderIsScopedToItsCustomer() {
        var result = placeOrder("idem-scoped");

        assertThat(orderQuery.detailForCustomer(TENANT, result.orderId(), CUSTOMER, null))
                .isPresent();
        assertThat(orderQuery.detailForCustomer(TENANT, result.orderId(), OTHER_CUSTOMER, null))
                .as("knowing an order id must not be enough to read it")
                .isEmpty();
    }

    // ------------------------------------------------------------- approval

    @Test
    @DisplayName("a restaurant-approval order waits, with a durable deadline")
    void restaurantApprovalWaitsForADecision() {
        requireApproval();
        var result = placeOrder("idem-approval");

        assertThat(result.status()).isEqualTo(OrderStatus.AWAITING_APPROVAL);
        assertThat(orderStore.find(TENANT, result.orderId()).orElseThrow().approvalDeadlineAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM ordering.order_timers
                WHERE status = 'PENDING' AND timer_type = 'APPROVAL_DEADLINE'
                """).query(Long.class).single())
                .as("an in-memory timer is lost on every deployment")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("two operators deciding at once settle at exactly one outcome")
    void twoSimultaneousDecisionsSettleAtOne() throws Exception {
        requireApproval();
        var order = placeOrder("idem-race-approval").orderId();

        CountDownLatch bothReady = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OrderStateService.DecisionResult> approve = pool.submit(() -> {
                bothReady.countDown();
                awaitQuietly(bothReady);
                return tx(() -> orderState.decide(
                        TENANT, order, decision("d-approve", OrderStateService.DecisionAction.APPROVE)));
            });
            Future<OrderStateService.DecisionResult> reject = pool.submit(() -> {
                bothReady.countDown();
                awaitQuietly(bothReady);
                return tx(() -> orderState.decide(
                        TENANT, order, decision("d-reject", OrderStateService.DecisionAction.REJECT)));
            });

            var results = List.of(approve.get(20, TimeUnit.SECONDS), reject.get(20, TimeUnit.SECONDS));

            assertThat(results)
                    .filteredOn(OrderStateService.DecisionResult::applied)
                    .hasSize(1);

            assertThat(jdbc.sql("SELECT count(*) FROM ordering.approval_decisions")
                            .query(Long.class)
                            .single())
                    .as("both commands are on record; only one is effective")
                    .isEqualTo(2L);
            assertThat(jdbc.sql("SELECT count(*) FROM ordering.approval_decisions WHERE effective")
                            .query(Long.class)
                            .single())
                    .isEqualTo(1L);

            var settled = orderStore.find(TENANT, order).orElseThrow();
            assertThat(settled.status()).isIn(OrderStatus.CONFIRMED, OrderStatus.REJECTED);

            // Both callers are told the same thing, so a second click gives the
            // same answer as the first rather than an error.
            assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(settled.status()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a repeated decision id does not re-run the decision")
    void aRepeatedDecisionIsIdempotent() {
        requireApproval();
        var order = placeOrder("idem-repeat-decision").orderId();

        var first =
                tx(() -> orderState.decide(TENANT, order, decision("d-1", OrderStateService.DecisionAction.APPROVE)));
        var second =
                tx(() -> orderState.decide(TENANT, order, decision("d-1", OrderStateService.DecisionAction.APPROVE)));

        assertThat(first.applied()).isTrue();
        assertThat(second.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(second.orderVersion()).isEqualTo(first.orderVersion());
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.approval_decisions")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a lapsed approval deadline expires the order and releases the hold")
    void aLapsedDeadlineExpiresTheOrder() {
        requireApproval();
        var order = placeOrder("idem-timeout").orderId();

        clock.advance(Duration.ofMinutes(6));
        var due = tx(() -> orderStore.claimDueTimers(clock.instant(), 10));
        assertThat(due).hasSize(1);

        tx(() -> orderState.approvalDeadlineReached(TENANT, order));

        var settled = orderStore.find(TENANT, order).orElseThrow();
        // EXPIRED, not REJECTED: "the restaurant declined" and "the restaurant
        // never looked" are different facts with different customer wording.
        assertThat(settled.status()).isEqualTo(OrderStatus.EXPIRED);

        tx(() -> inventoryProcess.runOnce(10));
        assertThat(reservationStatus()).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("a decision that arrives after the deadline fired does not reopen the order")
    void aLateDecisionCannotReopenAnExpiredOrder() {
        requireApproval();
        var order = placeOrder("idem-late").orderId();

        clock.advance(Duration.ofMinutes(6));
        tx(() -> orderState.approvalDeadlineReached(TENANT, order));

        var late = tx(
                () -> orderState.decide(TENANT, order, decision("d-late", OrderStateService.DecisionAction.APPROVE)));

        assertThat(late.applied()).isFalse();
        assertThat(late.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.approval_decisions")
                        .query(Long.class)
                        .single())
                .as("the command is recorded even though it changed nothing")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("both the winning and the losing decision leave an audit fact")
    void everyDecisionIsAudited() {
        requireApproval();
        var order = placeOrder("idem-audited").orderId();

        tx(() -> orderState.decide(TENANT, order, decision("d-win", OrderStateService.DecisionAction.APPROVE)));
        tx(() -> orderState.decide(TENANT, order, decision("d-lose", OrderStateService.DecisionAction.REJECT)));

        // "Who tried to reject this order, and when" is asked after every dispute.
        // An audit trail that recorded only the winner could not answer it.
        assertThat(jdbc.sql("""
                SELECT outcome FROM audit.audit_events
                WHERE action_code = 'ordering.order.approval-decision'
                ORDER BY occurred_at, outcome
                """).query(String.class).list()).containsExactlyInAnyOrder("SUCCEEDED", "REJECTED");
    }

    // ------------------------------------------------- inventory process

    @Test
    @DisplayName("confirmation commits the hold through the process manager, not inline")
    void confirmationCommitsTheHold() {
        var result = placeOrder("idem-commit");

        assertThat(reservationStatus())
                .as("the consequence is recorded in the checkout transaction and carried out after")
                .isEqualTo("HELD");
        assertThat(jdbc.sql("""
                SELECT status FROM ordering.order_process_states WHERE process_name = 'ORDER_INVENTORY'
                """).query(String.class).single()).isEqualTo("WAITING");

        tx(() -> inventoryProcess.runOnce(10));

        assertThat(reservationStatus()).isEqualTo("COMMITTED");
        assertThat(jdbc.sql("""
                SELECT status FROM ordering.order_process_states WHERE order_id = :order
                """)
                        .param("order", result.orderId())
                        .query(String.class)
                        .single())
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("rerunning the inventory process does not commit twice")
    void theInventoryProcessIsIdempotentUnderReplay() {
        placeOrder("idem-replay");
        tx(() -> inventoryProcess.runOnce(10));

        long movementsAfterFirst = movementCount();
        tx(() -> inventoryProcess.runOnce(10));

        assertThat(movementCount())
                .as("rebuilding a process from history must not repeat its effect")
                .isEqualTo(movementsAfterFirst);
    }

    @Test
    @DisplayName("a cancellation before confirmation releases the hold and the kitchen slot")
    void cancellationReleasesEverything() {
        requireApproval();
        var order = placeOrder("idem-cancel").orderId();
        int version = orderStore.find(TENANT, order).orElseThrow().version();

        tx(() -> orderState.cancel(
                TENANT, order, version, "CUSTOMER_CHANGED_MIND", "CUSTOMER", CUSTOMER.toString(), null));
        tx(() -> inventoryProcess.runOnce(10));

        assertThat(orderStore.find(TENANT, order).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reservationStatus()).isEqualTo("RELEASED");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM tenant.location_capacity_holds WHERE released_at IS NULL
                """).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a confirmed order cannot be cancelled in this release")
    void aConfirmedOrderCannotBeCancelled() {
        var result = placeOrder("idem-no-cancel");
        int version = orderStore.find(TENANT, result.orderId()).orElseThrow().version();

        // ADR 0039 owns amendment. Half-performing the payment, fiscal, POS and
        // fulfilment consequences here would leave state nobody could reconstruct.
        assertThat(catchThrowable(() -> tx(() -> orderState.cancel(
                        TENANT, result.orderId(), version, "OPERATOR_ERROR", "USER", "someone", null))))
                .isInstanceOf(OrderStateService.CancellationNotPermittedException.class);
    }

    // -------------------------------------------------------- state machine

    @Test
    @DisplayName("the kitchen path runs confirmed to completed for a pickup order")
    void theKitchenPathAdvances() {
        var order = placeOrder("idem-kitchen").orderId();

        advance(order, OrderStatus.PREPARING);
        advance(order, OrderStatus.READY);
        advance(order, OrderStatus.COMPLETED);

        assertThat(orderStore.find(TENANT, order).orElseThrow().status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(orderQuery.timeline(TENANT, order))
                .extracting(row -> row.toStatus())
                .containsExactly("RECEIVED", "CONFIRMED", "PREPARING", "READY", "COMPLETED");
    }

    @Test
    @DisplayName("a pickup order cannot enter the courier state")
    void aPickupOrderCannotBecomeFulfilling() {
        var order = placeOrder("idem-pickup").orderId();
        advance(order, OrderStatus.PREPARING);
        advance(order, OrderStatus.READY);

        int version = orderStore.find(TENANT, order).orElseThrow().version();

        // FULFILLING is delivery only. A pickup order allowed in would wait for a
        // courier that does not exist, which is one of the ways an order becomes
        // permanently stuck.
        assertThat(catchThrowable(() -> tx(() -> orderState.advance(
                        TENANT, order, OrderStatus.FULFILLING, version, "DISPATCHED", "USER", "someone", null))))
                .isInstanceOf(OrderStateMachine.IllegalTransitionException.class);
    }

    @Test
    @DisplayName("the state machine is code-owned and closed")
    void theStateMachineIsCodeOwned() {
        // ADR 0036's omission list is explicit that tenants may not reorder the
        // order lifecycle. There is no table to override and no policy key that
        // reaches it; the only enforcement is that this set is a constant.
        assertThat(OrderStateMachine.transitionsFrom(OrderStatus.AWAITING_APPROVAL))
                .containsExactlyInAnyOrder(
                        OrderStatus.CONFIRMED, OrderStatus.REJECTED, OrderStatus.EXPIRED, OrderStatus.CANCELLED);
        assertThat(OrderStateMachine.transitionsFrom(OrderStatus.COMPLETED))
                .as("no transition leaves a terminal status; a correction is a new order")
                .isEmpty();
        assertThat(OrderStateMachine.permits(OrderStatus.CONFIRMED, OrderStatus.RECEIVED))
                .isFalse();
    }

    @Test
    @DisplayName("the database refuses a status the code-owned machine does not know")
    void theDatabaseRefusesAnUnknownStatus() {
        var order = placeOrder("idem-status").orderId();

        // The CHECK constraint and OrderStatus have to agree. If a later change
        // adds a status in one place only, this is what fails.
        assertThat(catchThrowable(() -> jdbc.sql("UPDATE ordering.orders SET status = 'ON_HOLD' WHERE id = :id")
                        .param("id", order)
                        .update()))
                .isNotNull();
    }

    // ------------------------------------------------------------- events

    @Test
    @DisplayName("an order emits its facts, and they carry no personal data")
    void ordersEmitFactsWithoutPersonalData() {
        var result = placeOrder("idem-events");

        assertThat(published.events)
                .extracting(OrderingEvent::eventType)
                .containsExactly("OrderReceived", "OrderConfirmed");

        var received = (OrderReceived) published.events.getFirst();
        assertThat(received.orderId()).isEqualTo(result.orderId());
        assertThat(received.payload()).isInstanceOf(OrderReceived.Payload.class);
        assertThat(((OrderReceived.Payload) received.payload()).lineCount())
                .as("a count, not the basket: a consumer that needs the lines calls the API")
                .isEqualTo(1);

        var confirmed = (OrderConfirmed) published.events.getLast();
        assertThat(confirmed.totalMinor()).isEqualTo(100_000L);
    }

    // ------------------------------------------------------ tenant isolation

    @Test
    @DisplayName("another tenant cannot read or check out this tenant's cart")
    void crossTenantAccessFails() {
        var cart = readyCart();
        UUID otherTenant = UUID.randomUUID();

        assertThat(carts.view(otherTenant, BRAND, CUSTOMER, cart))
                .as("the tenant predicate is in the query, not checked after loading")
                .isEmpty();

        var refused = tx(() -> checkout.checkout(new CheckoutService.CheckoutCommand(
                otherTenant,
                BRAND,
                cart,
                1,
                UUID.randomUUID(),
                "hash",
                "idem-cross",
                null,
                0L,
                "CUSTOMER",
                CUSTOMER.toString(),
                null)));
        assertThat(refused.rejectionCode()).isEqualTo("CART_NOT_FOUND");
    }

    // ------------------------------------------------------ delivery destination

    /**
     * The gap this section closes: {@code fulfillment.api.DeliveryOrderPort} had no
     * implementation, so a delivery order could be taken and no courier could ever
     * be sourced for it. Everything below runs against the real envelope encryption
     * and the real schema, because every property worth asserting here is one that
     * only exists there — that a ciphertext does not travel between rows, that an
     * address survives being archived, and that a delivery with nowhere to go is
     * refused before anybody pays.
     */
    @Test
    @DisplayName("a delivery cart names one of the customer's own saved addresses")
    void aDeliveryCartCanBeGivenADestination() {
        UUID address = insertAddress(CUSTOMER, "Home", 41.311081, 69.240562);
        UUID cart = openDeliveryCart();
        putLine(cart, "a", burgerVariant, 1);

        tx(() -> carts.setDestination(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), destinationCommand(address)));

        assertThat(carts.destinationAddressId(TENANT, BRAND, CUSTOMER, cart))
                .as("the cart reports the address id it was given and never the address")
                .contains(address);
        assertThat(carts.destinationAddressId(TENANT, BRAND, OTHER_CUSTOMER, cart))
                .as("and reports nothing at all to anybody else")
                .isEmpty();
    }

    @Test
    @DisplayName("naming a destination clears the price, because the fee depends on the address")
    void aDestinationInvalidatesThePrice() {
        UUID address = insertAddress(CUSTOMER, "Home", 41.311081, 69.240562);
        UUID cart = openDeliveryCart();
        putLine(cart, "a", burgerVariant, 1);
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        assertThat(readCart(cart).pricingQuoteId()).isNotNull();

        tx(() -> carts.setDestination(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), destinationCommand(address)));

        assertThat(readCart(cart).pricingQuoteId())
                .as("ADR 0037 prices delivery from the destination, so a basket priced to one "
                        + "door is not priced to another")
                .isNull();
    }

    @Test
    @DisplayName("another customer's address cannot be chosen, and is not found rather than refused")
    void aStrangersAddressIsNotFound() {
        UUID theirs = insertAddress(OTHER_CUSTOMER, "Their home", 41.5, 69.5);
        UUID cart = openDeliveryCart();

        var refused = catchThrowable(() -> tx(() ->
                carts.setDestination(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), destinationCommand(theirs))));

        assertThat(((CartService.CartRefusedException) refused).code())
                .as("an address id must not be probeable by trying it on a cart")
                .isEqualTo("ADDRESS_NOT_FOUND");
    }

    @Test
    @DisplayName("an address with no coordinate is refused where the customer can still fix it")
    void aLandmarkOnlyAddressCannotBeDeliveredTo() {
        UUID address = insertAddress(CUSTOMER, "Mahalla house", null, null);
        UUID cart = openDeliveryCart();

        var refused = catchThrowable(() -> tx(() ->
                carts.setDestination(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), destinationCommand(address))));

        assertThat(((CartService.CartRefusedException) refused).code())
                .as("a partner booking carries primitive coordinates, and 0,0 is the Gulf of " + "Guinea")
                .isEqualTo("DESTINATION_NOT_LOCATED");
    }

    @Test
    @DisplayName("a collected cart has nowhere to be delivered")
    void aPickupCartRefusesADestination() {
        UUID address = insertAddress(CUSTOMER, "Home", 41.311081, 69.240562);
        UUID cart = openCart();

        var refused = catchThrowable(() -> tx(() ->
                carts.setDestination(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), destinationCommand(address))));

        assertThat(((CartService.CartRefusedException) refused).code()).isEqualTo("DESTINATION_NOT_APPLICABLE");
    }

    /**
     * The rule the whole change exists for. Discovering a missing address while a
     * courier is being sourced means the customer has paid and the kitchen has
     * cooked; discovering it here costs them one screen.
     */
    @Test
    @DisplayName("a delivery checkout with no destination is refused, and creates no order")
    void aDeliveryOrderMustSayWhereItIsGoing() {
        UUID cart = openDeliveryCart();
        putLine(cart, "a", burgerVariant, 2);
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));

        var result = tx(() -> checkout.checkout(checkoutCommand(cart, "no-destination")));

        assertThat(result.rejectionCode()).isEqualTo("DELIVERY_DESTINATION_REQUIRED");
        assertThat(countOrders())
                .as("refused among the read-only validations, before a hold or a slot is taken")
                .isZero();
        assertThat(reservationCount())
                .as("and no stock is held for an order that was never created")
                .isZero();
    }

    @Test
    @DisplayName("a collected order is unaffected: it has no destination and needs none")
    void aPickupCheckoutStillNeedsNoDestination() {
        var result = placeOrder("pickup-unaffected");

        assertThat(result.created()).isTrue();
        assertThat(deliveryOrders.deliveryOrder(TENANT, result.orderId()))
                .as("and there is nothing for sourcing to plan")
                .isEmpty();
    }

    @Test
    @DisplayName("the port hands sourcing the order, the fee and the doorstep")
    void aConfirmedDeliveryOrderIsSourceable() {
        UUID orderId = placeDeliveryOrder("sourceable").orderId();

        var delivery = deliveryOrders.deliveryOrder(TENANT, orderId).orElseThrow();

        assertThat(delivery.orderReference())
                .isEqualTo(orderStore.find(TENANT, orderId).orElseThrow().publicOrderNumber());
        assertThat(delivery.currency()).isEqualTo("UZS");
        assertThat(delivery.preparation())
                .isEqualTo(Duration.ofMinutes(
                        orderStore.find(TENANT, orderId).orElseThrow().promise().prepMinutes()));
        assertThat(delivery.prepaid())
                .as("nothing has been captured, so the courier collects at the door")
                .isFalse();
        assertThat(delivery.itemValueMinor())
                .as("the goods the courier carries, never the fee for carrying them")
                .isEqualTo(orderStore.find(TENANT, orderId).orElseThrow().totalMinor()
                        - orderStore.find(TENANT, orderId).orElseThrow().feeMinor());

        var dropoff = delivery.dropoff();
        assertThat(dropoff.latitude()).isEqualTo(41.311081);
        assertThat(dropoff.longitude()).isEqualTo(69.240562);
        assertThat(dropoff.address())
                .contains("Amir Temur 12")
                .contains("Tashkent")
                .as("the landmark locates the building and must reach the courier")
                .contains("blue gate");
        assertThat(dropoff.entrance()).isEqualTo("2");
        assertThat(dropoff.floor()).isEqualTo("5");
        assertThat(dropoff.apartment()).isEqualTo("41");
        assertThat(dropoff.contactName()).isEqualTo("Dilnoza");
        assertThat(dropoff.contactPhone()).isEqualTo("+998901112233");
        assertThat(dropoff.comment()).isEqualTo("Ring the top bell");
    }

    @Test
    @DisplayName("neither the waypoint nor the delivery order prints the doorstep")
    void nothingHandedToSourcingPrintsPersonalData() {
        UUID orderId = placeDeliveryOrder("redacted").orderId();
        var delivery = deliveryOrders.deliveryOrder(TENANT, orderId).orElseThrow();

        assertThat(delivery.dropoff().toString()).isEqualTo("Waypoint[REDACTED]");
        assertThat(delivery.toString())
                .doesNotContain("Amir Temur")
                .doesNotContain("+998901112233")
                .doesNotContain("Dilnoza");
    }

    /**
     * The reason V0022 makes the order carry a copy rather than a reference, and
     * the answer to "what happens when the customer deletes that address": nothing.
     * ADR 0015 archives rather than deletes, and the order was never pointing at
     * the row in the first place.
     */
    @Test
    @DisplayName("archiving the address afterwards does not change where the order went")
    void anArchivedAddressLeavesThePlacedOrderIntact() {
        var placed = placeDeliveryOrder("archived");

        jdbc.sql("UPDATE customer.addresses SET status = 'ARCHIVED' WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .update();

        var delivery = deliveryOrders.deliveryOrder(TENANT, placed.orderId()).orElseThrow();
        assertThat(delivery.dropoff().address()).contains("Amir Temur 12");
        assertThat(delivery.dropoff().contactPhone()).isEqualTo("+998901112233");
    }

    @Test
    @DisplayName("a cart that already has a destination keeps it when it is rebuilt elsewhere")
    void aRebuiltCartKeepsItsDestination() {
        UUID address = insertAddress(CUSTOMER, "Home", 41.311081, 69.240562);
        UUID cart = openDeliveryCart();
        putLine(cart, "a", burgerVariant, 1);
        tx(() -> carts.setDestination(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), destinationCommand(address)));

        var rebuilt =
                tx(() -> carts.rebuildAtLocation(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), OTHER_LOCATION));

        assertThat(carts.destinationAddressId(
                        TENANT, BRAND, CUSTOMER, rebuilt.cart().cartId()))
                .as("a customer who moved branch did not move house")
                .contains(address);
        // Re-encrypted under the new cart's own associated data rather than copied:
        // a ciphertext moved between rows is one nobody can open again.
        var captured = tx(() -> carts.destination(TENANT, rebuilt.cart().cartId(), "TEST"))
                .orElseThrow();
        assertThat(captured.destination().latitude()).isEqualTo(41.311081);
        assertThat(captured.recipientPhone()).isEqualTo("+998901112233");
    }

    @Test
    @DisplayName("an order the restaurant has not confirmed is not sourced")
    void anUnconfirmedDeliveryOrderIsNotSourceable() {
        requireApproval();
        var placed = placeDeliveryOrder("awaiting");

        assertThat(orderStore.find(TENANT, placed.orderId()).orElseThrow().status())
                .isEqualTo(OrderStatus.AWAITING_APPROVAL);
        assertThat(deliveryOrders.deliveryOrder(TENANT, placed.orderId()))
                .as("sourcing a courier for food nobody has agreed to cook is how a courier "
                        + "waits unpaid outside a kitchen that never started")
                .isEmpty();
    }

    @Test
    @DisplayName("an order id is not proof of ownership: another tenant sees nothing")
    void anotherTenantCannotReadTheDropoff() {
        UUID orderId = placeDeliveryOrder("tenant-scoped").orderId();

        assertThat(deliveryOrders.deliveryOrder(UUID.randomUUID(), orderId)).isEmpty();
    }

    /**
     * ADR 0029's associated data, asserted rather than assumed. The order's
     * ciphertext is bound to the order row, so the copy that was made at checkout
     * cannot be read as though it belonged to the cart it came from — which is what
     * makes copying a ciphertext between rows fail loudly instead of quietly
     * revealing the wrong person's address.
     */
    @Test
    @DisplayName("the order's address ciphertext does not decrypt as any other row's")
    void theSnapshotCiphertextIsBoundToItsOwnOrder() {
        var placed = placeDeliveryOrder("bound");
        String ciphertext = jdbc.sql("""
                SELECT address_encrypted FROM ordering.order_customer_snapshots
                WHERE tenant_id = :tenantId AND order_id = :orderId
                """)
                .param("tenantId", TENANT)
                .param("orderId", placed.orderId())
                .query(String.class)
                .single();

        Throwable misread = catchThrowable(() -> protection.reveal(
                TENANT,
                uz.horecaos.platform.iam.api.protection.ProtectedValue.deserialize(ciphertext),
                new FieldProtection.RecordRef(
                        "ordering.order_customer_snapshots", "address_encrypted", UUID.randomUUID()),
                "TEST"));

        assertThat(misread).isNotNull();
    }

    @Test
    @DisplayName("the delivery destination is never written to an ordering event")
    void noOrderingEventCarriesTheAddress() {
        placeDeliveryOrder("no-pii-in-events");

        assertThat(published.events)
                .isNotEmpty()
                .allSatisfy(
                        event -> assertThat(event.toString() + event.payload().toString())
                                .doesNotContain("Amir Temur")
                                .doesNotContain("+998901112233")
                                .doesNotContain("Dilnoza"));
    }

    /**
     * The whole chain, end to end and against real tables: a delivery cart is given
     * a destination, checkout snapshots it onto the order, ADR 0014's planner asks
     * ordering for the order through the port implemented by this change, and a
     * plan and a sourcing job exist for a courier to be found by.
     *
     * <p>Wired here rather than left to {@code DeliveryPlanTrigger}, which is the
     * thing that calls this in production: this suite publishes events into a
     * recorder rather than a Spring context, so the listener never fires. What is
     * asserted is the part the listener delegates to, which is the part that could
     * be wrong.
     */
    @Test
    @DisplayName("a delivery order produces a plan and a job for a courier to be sourced by")
    void aDeliveryOrderBecomesAPlan() {
        placeBranchOnTheMap();
        var placed = placeDeliveryOrder("planned");
        var order = orderStore.find(TENANT, placed.orderId()).orElseThrow();

        var plan = deliveryPlanning()
                .open(TENANT, BRAND, LOCATION, placed.orderId(), order.confirmedAt())
                .orElseThrow();

        assertThat(plan.orderId()).isEqualTo(placed.orderId());
        assertThat(plan.currency()).isEqualTo("UZS");
        assertThat(plan.customerDeliveryFeeMinor())
                .as("what the customer paid for delivery, carried so nobody re-runs ADR 0037 "
                        + "against today's zones")
                .isEqualTo(order.feeMinor());
        assertThat(plan.distanceMeters())
                .as("measured between the branch pin and the customer's own coordinate")
                .isPositive();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM fulfillment.delivery_sourcing_jobs
                WHERE tenant_id = :tenantId AND delivery_plan_id = :planId
                """)
                        .param("tenantId", TENANT)
                        .param("planId", plan.id())
                        .query(Long.class)
                        .single())
                .as("and the durable alarm clock that wakes sourcing")
                .isEqualTo(1L);
    }

    /**
     * ADR 0014 plans nothing for a branch nobody has placed on a map, and this
     * asserts the whole chain still refuses rather than sending a courier to a
     * kitchen at 0,0.
     */
    @Test
    @DisplayName("an unplaced branch produces no plan, and does not fail the order")
    void anUnplacedBranchPlansNothing() {
        var placed = placeDeliveryOrder("unplaced-branch");
        var order = orderStore.find(TENANT, placed.orderId()).orElseThrow();

        assertThat(deliveryPlanning().open(TENANT, BRAND, LOCATION, placed.orderId(), order.confirmedAt()))
                .isEmpty();
        assertThat(orderStore.find(TENANT, placed.orderId()).orElseThrow().status())
                .as("the order stands; a configuration gap is not a checkout outage")
                .isEqualTo(OrderStatus.CONFIRMED);
    }

    // ------------------------------------------- settlement, and what it lets

    /**
     * The regression test, and the one that mattered.
     *
     * <p>Before this change {@code payments.order_settlements} had exactly one
     * writer, {@code OrderSettlementService.plan}, and exactly no production
     * caller: {@code grep -rn OrderSettlementService src/main/java} found the
     * class, its constructor and two Javadoc mentions. Every remedy recorded
     * through {@code OperationsRemedyController} therefore threw
     * {@code RESOURCE_NOT_FOUND — "The order has no settlement"} for any order a
     * customer had actually placed, and the payments suite was green because its
     * setup planned the settlement itself.
     *
     * <p>So this test places the order the way production does — a cart, a quote,
     * a checkout, a confirmation, a handover — and touches the settlement service
     * nowhere. If the seam is ever cut again, this is what says so.
     */
    @Test
    @DisplayName("an order placed the way a customer places one can be refunded")
    void anOrderPlacedTheRealWayCanBeRefunded() {
        var wired = checkoutWith.apply(realPayments(NO_SELLER));
        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-refund", "CASH")));
        handOver(placed.orderId());

        var outcome = tx(() -> remedies.recordRefund(refundOf(placed.orderId(), 100_000L)));

        assertThat(outcome.recorded()).isTrue();
        assertThat(outcome.remedy().amountMinor()).isEqualTo(100_000L);
        assertThat(outcome.remedy().attestedMoneyMinor())
                .as("cash out of the drawer is money this platform never held and cannot prove "
                        + "moved, so all of it is an attestation")
                .isEqualTo(100_000L);
        assertThat(tenderStatuses(placed.orderId())).containsExactly(TenderStatus.REVERSED);
    }

    @Test
    @DisplayName("a partial refund on a real order leaves the rest refundable")
    void aPartialRefundLeavesTheRestRefundable() {
        var wired = checkoutWith.apply(realPayments(NO_SELLER));
        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-partial", "CASH")));
        handOver(placed.orderId());

        tx(() -> remedies.recordRefund(refundOf(placed.orderId(), 40_000L)));
        var second = tx(() -> remedies.recordRefund(refundOf(placed.orderId(), 60_000L)));

        assertThat(second.recorded()).isTrue();
        assertThat(refundedMinor(placed.orderId())).isEqualTo(100_000L);
        // V0048's headroom, over a real order rather than a hand-planned one: a
        // third refund has nothing left to take.
        assertThatThrownBy(() -> tx(() -> remedies.recordRefund(refundOf(placed.orderId(), 1_000L))))
                .hasMessageContaining("cannot exceed what the tenders settled");
    }

    @Test
    @DisplayName("a delivery-fee reimbursement is recorded against a real order's tenders")
    void aDeliveryFeeReimbursementIsRecordedAgainstARealOrder() {
        var wired = checkoutWith.apply(realPayments(NO_SELLER));
        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-fee", "CASH")));
        handOver(placed.orderId());
        deliveryFeeBasis = java.util.OptionalLong.of(12_000L);

        var outcome = tx(() -> remedies.recordDeliveryFeeReimbursement(refundOf(placed.orderId(), 12_000L)));

        assertThat(outcome.recorded()).isTrue();
        assertThat(outcome.remedy().deliveryFeeBasisMinor()).isEqualTo(12_000L);
        assertThat(refundedMinor(placed.orderId()))
                .as("the fee was part of the total and was settled by the same tenders, so it "
                        + "comes back through them")
                .isEqualTo(12_000L);
    }

    /**
     * The one remedy that never touches the settlement — and the reason it is
     * still here. It costs the tenant nothing today, so it is not bounded by the
     * tender cap; asserting that it works on a real order is what keeps a later
     * change from routing it through the cap by accident.
     */
    @Test
    @DisplayName("a future discount is granted on a real order without touching its tenders")
    void aFutureDiscountIsGrantedOnARealOrder() {
        var wired = checkoutWith.apply(realPayments(NO_SELLER));
        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-grant", "CASH")));
        handOver(placed.orderId());

        var outcome = tx(() -> remedies.grantFutureDiscount(new OrderRemedyService.FutureDiscountCommand(
                TENANT,
                placed.orderId(),
                uz.horecaos.platform.payments.api.EntitlementScope.DELIVERY_FEE,
                uz.horecaos.platform.payments.api.EntitlementBenefit.FIXED_AMOUNT,
                null,
                12_000L,
                null,
                3,
                Duration.ofDays(30),
                "SERVICE_FAILURE",
                "Ninety minutes late",
                uz.horecaos.platform.audit.api.ActorRef.user("support-1", null),
                "k-grant",
                null)));

        assertThat(outcome.recorded()).isTrue();
        assertThat(refundedMinor(placed.orderId())).isZero();
    }

    @Test
    @DisplayName("a guest order gets a settlement and can be refunded")
    void aGuestOrderIsSettledToo() {
        allowGuestOrders();
        var wired = checkoutWith.apply(realPayments(NO_SELLER));
        var placed = tx(() -> wired.checkout(guestCheckoutCommand(readyGuestCart(), "idem-guest")));
        handOver(placed.orderId());

        assertThat(settlementStore.findSettlement(TENANT, placed.orderId())).isPresent();

        var outcome = tx(() -> remedies.recordRefund(refundOf(placed.orderId(), 100_000L)));

        assertThat(outcome.recorded())
                .as("nobody to grant points to is not the same as nobody to give money back to")
                .isTrue();
    }

    @Test
    @DisplayName("a cash order's tender is not money until the order is handed over")
    void cashIsNotMoneyUntilHandover() {
        var wired = checkoutWith.apply(realPayments(NO_SELLER));
        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-cash-hold", "CASH")));

        // Confirmed at checkout, with a courier yet to collect anything.
        assertThat(settlementStatus(placed.orderId())).isEqualTo(SettlementStatus.PLANNED);
        assertThatThrownBy(() -> tx(() -> remedies.recordRefund(refundOf(placed.orderId(), 10_000L))))
                .as("refunding cash the tenant has never held is the failure this ordering " + "prevents")
                .hasMessageContaining("cannot exceed what the tenders settled");

        handOver(placed.orderId());

        assertThat(settlementStatus(placed.orderId())).isEqualTo(SettlementStatus.SETTLED);
    }

    /**
     * The only test covering {@link uz.horecaos.platform.payments.domain.CaptureTiming#BEFORE_CONFIRMATION},
     * and for a long time it checked out and confirmed at the same instant on the
     * {@link MutableClock}.
     *
     * <p>That is a sequence production cannot produce. A card order sits in
     * {@code PAYMENT_AUTHORIZING} for as long as the customer takes on the
     * provider's page, which is the whole point of the status — and with no
     * {@code clock.advance} anywhere and no redemption, the test could not see
     * what happens when that wait outlives the thirty-minute points hold. Had it
     * advanced thirty-one minutes with a redemption it would have caught the
     * defect this change repairs. So it does both now.
     */
    @Test
    @DisplayName("a card order settles on its confirmation, not on its handover, however long "
            + "the customer takes to pay")
    void aCardOrderSettlesOnConfirmation() {
        seedRedemptionPolicy();
        seedBalance(20_000L);
        var wired = checkoutWith.apply(realPayments(UUID.randomUUID()));
        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-card", "CLICK", 20_000L)));
        UUID account =
                loyaltyStore.findAccount(TENANT, BRAND, CUSTOMER).orElseThrow().id();

        // ADR 0013's BEFORE_CONFIRMATION timing: the order waits on the money, so
        // nothing has settled while it waits.
        assertThat(placed.status()).isEqualTo(OrderStatus.PAYMENT_AUTHORIZING);
        assertThat(settlementStatus(placed.orderId())).isEqualTo(SettlementStatus.PLANNED);

        // The customer is on Click's page: the provider has a reservation against
        // this order, which is what makes the wait a live payment rather than an
        // abandoned tab.
        reservedAttempt(placed.orderId(), PaymentProviderType.CLICK, Duration.ofHours(12));

        // Thirty-one minutes of it, so the points hold is past its lifetime and
        // the sweep reaches it on its ordinary cadence.
        clock.advance(Duration.ofMinutes(31));
        tx(() -> loyaltySweep.releaseStaleHolds());

        assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor())
                .as("a customer part-way through a provider redirect has not abandoned anything")
                .isZero();

        advance(placed.orderId(), OrderStatus.CONFIRMED);

        assertThat(settlementStatus(placed.orderId())).isEqualTo(SettlementStatus.SETTLED);
        assertThat(tenderStatuses(placed.orderId())).containsExactly(TenderStatus.SETTLED, TenderStatus.SETTLED);
        var outcome = tx(() -> remedies.recordRefund(refundOf(placed.orderId(), 100_000L)));
        assertThat(outcome.recorded()).isTrue();
    }

    /**
     * The money bug this change exists to close, end to end.
     *
     * <p>{@code HeldTenderProgress.STILL_WORKING} deliberately excluded
     * {@code PAYMENT_AUTHORIZING} and called the cost "recoverable". Nothing
     * recovered it. Payme's transaction window is twelve hours and the points hold
     * lasted thirty minutes, so a customer who paid at minute forty — well inside
     * everything Payme documents — met a confirmation that reached
     * {@code points.settle}, found the reservation released, threw
     * {@code RESOURCE_CONFLICT} out of a {@code BEFORE_COMMIT} listener and rolled
     * the confirmation back. {@code planSettlement} is idempotent on the order and
     * never re-reserves, so every retry of that callback failed identically: the
     * order was stuck in {@code PAYMENT_AUTHORIZING} for ever, Payme had 80 000
     * som, and the customer had their 20 000 points back.
     */
    @Test
    @DisplayName("points and Payme: a redirect that pays at minute forty still settles whole")
    void pointsAndPaymeSurviveTheRedirect() {
        seedRedemptionPolicy();
        seedBalance(20_000L);
        var wired = checkoutWith.apply(realPayments(UUID.randomUUID()));

        // t=0. 20 000 from points, 80 000 from Payme.
        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-payme-split", "PAYME", 20_000L)));
        UUID account =
                loyaltyStore.findAccount(TENANT, BRAND, CUSTOMER).orElseThrow().id();

        assertThat(placed.status()).isEqualTo(OrderStatus.PAYMENT_AUTHORIZING);
        assertThat(tenderAmounts(placed.orderId())).containsExactly(20_000L, 80_000L);
        assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor())
                .as("the hold is the debit: the points left the balance at checkout")
                .isZero();

        UUID attempt = reservedAttempt(
                placed.orderId(),
                PaymentProviderType.PAYME,
                uz.horecaos.platform.payments.application.PaymentAttemptService.PAYME_TRANSACTION_TIMEOUT);

        // t=32min. The hold is past its thirty-minute lifetime and the sweep runs
        // on its two-minute cadence. It used to release here.
        clock.advance(Duration.ofMinutes(32));
        int released = tx(() -> loyaltySweep.releaseStaleHolds());

        assertThat(released).isZero();
        assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor())
                .as("Payme is holding a transaction against this order; the customer has not "
                        + "abandoned anything and their points are not theirs to spend again")
                .isZero();
        assertThat(tenderStatuses(placed.orderId())).containsExactly(TenderStatus.RESERVED, TenderStatus.PLANNED);

        // t=40min. Well inside Payme's twelve hours, the customer pays.
        clock.advance(Duration.ofMinutes(8));
        captureAttempt(attempt);
        advance(placed.orderId(), OrderStatus.CONFIRMED);

        assertThat(orderStore.find(TENANT, placed.orderId()).orElseThrow().status())
                .as("the confirmation committed rather than rolling back for ever")
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(settlementStatus(placed.orderId())).isEqualTo(SettlementStatus.SETTLED);
        assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor())
                .as("the tenant collected 80 000 through Payme and 20 000 in points, once")
                .isZero();

        var outcome = tx(() -> remedies.recordRefund(refundOf(placed.orderId(), 100_000L)));
        assertThat(outcome.recorded()).isTrue();
    }

    /**
     * The requirement the thirty minutes was always protecting, and it is still
     * real: the fix must not become a longer constant.
     *
     * <p>A payable link handed to a customer who closed the tab leaves the attempt
     * {@code PRESENTED} for ever — the provider never comes back, because on
     * Payme nothing was ever created and on Click nothing was ever accepted. Those
     * points belong on the balance on the old cadence.
     */
    @Test
    @DisplayName("an abandoned redirect still gives its points back at minute thirty-one")
    void anAbandonedRedirectStillReleasesPromptly() {
        seedRedemptionPolicy();
        seedBalance(20_000L);
        var wired = checkoutWith.apply(realPayments(UUID.randomUUID()));
        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-payme-abandoned", "PAYME", 20_000L)));
        UUID account =
                loyaltyStore.findAccount(TENANT, BRAND, CUSTOMER).orElseThrow().id();

        presentedAttempt(placed.orderId(), PaymentProviderType.PAYME);

        clock.advance(Duration.ofMinutes(31));
        int released = tx(() -> loyaltySweep.releaseStaleHolds());

        assertThat(released).isEqualTo(1);
        assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor()).isEqualTo(20_000L);
        assertThat(tenderStatuses(placed.orderId()))
                .as("the sweep can write the tender now, through the port payments already "
                        + "implements; the two tables used to disagree about whether this leg "
                        + "still held anything, and nothing could see it")
                .containsExactly(TenderStatus.RELEASED, TenderStatus.PLANNED);
    }

    /**
     * A reservation past the provider's own window is not renewed for ever, which
     * is what keeps this from being the thirty minutes written larger.
     *
     * <p>{@code expires_at} is Payme's twelve hours measured from its own
     * {@code params.time}, and it is the same column
     * {@code PaymentAttemptService.expireStaleReservations} sweeps on. Once it has
     * passed, nothing is coming and the points go back.
     */
    @Test
    @DisplayName("a reservation past the provider's own window stops renewing the hold")
    void aStaleReservationStopsRenewingTheHold() {
        seedRedemptionPolicy();
        seedBalance(20_000L);
        var wired = checkoutWith.apply(realPayments(UUID.randomUUID()));
        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-payme-stale", "PAYME", 20_000L)));
        UUID account =
                loyaltyStore.findAccount(TENANT, BRAND, CUSTOMER).orElseThrow().id();

        reservedAttempt(
                placed.orderId(),
                PaymentProviderType.PAYME,
                uz.horecaos.platform.payments.application.PaymentAttemptService.PAYME_TRANSACTION_TIMEOUT);

        clock.advance(Duration.ofHours(11));
        assertThat(tx(() -> loyaltySweep.releaseStaleHolds()))
                .as("eleven hours in, Payme could still perform this transaction")
                .isZero();

        clock.advance(Duration.ofHours(2));
        assertThat(tx(() -> loyaltySweep.releaseStaleHolds()))
                .as("thirteen hours in, it could not")
                .isEqualTo(1);
        assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor()).isEqualTo(20_000L);
    }

    /**
     * What the platform does with the case the fix above cannot remove: a hold
     * that legitimately went back, and money that arrives afterwards anyway.
     *
     * <p>Three options, and two of them are worse than this one. Throwing is what
     * used to happen, inside a {@code BEFORE_COMMIT} listener, and it strands the
     * order in {@code PAYMENT_AUTHORIZING} for ever with the money captured.
     * Settling short in silence closes the settlement {@code SETTLED} for the full
     * order total while a leg never settled, which is indistinguishable from a
     * healthy order in every report and every refund calculation. So the
     * confirmation commits, the money that genuinely arrived settles, the leg that
     * did not is visibly {@code RELEASED} beneath a {@code PARTIALLY_SETTLED}
     * settlement, and the refund ceiling is the money and not the total.
     */
    @Test
    @DisplayName("a payment landing after its points went back settles short, and says so")
    void aLatePaymentAfterAReleasedHoldSettlesShortAndSaysSo() {
        seedRedemptionPolicy();
        seedBalance(20_000L);
        var wired = checkoutWith.apply(realPayments(UUID.randomUUID()));
        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-payme-late", "PAYME", 20_000L)));
        UUID account =
                loyaltyStore.findAccount(TENANT, BRAND, CUSTOMER).orElseThrow().id();

        // The tab looked closed, so the hold went back on the old cadence.
        presentedAttempt(placed.orderId(), PaymentProviderType.PAYME);
        clock.advance(Duration.ofMinutes(31));
        tx(() -> loyaltySweep.releaseStaleHolds());
        assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor()).isEqualTo(20_000L);

        // And then the customer paid from the link they still had.
        clock.advance(Duration.ofMinutes(9));
        advance(placed.orderId(), OrderStatus.CONFIRMED);

        assertThat(orderStore.find(TENANT, placed.orderId()).orElseThrow().status())
                .as("an order whose money a provider captured must not be strandable by a " + "listener that throws")
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(settlementStatus(placed.orderId()))
                .as("half paid is not a healthy order and must not read as one")
                .isEqualTo(SettlementStatus.PARTIALLY_SETTLED);
        assertThat(tenderStatuses(placed.orderId())).containsExactly(TenderStatus.RELEASED, TenderStatus.SETTLED);
        assertThat(settlementStore
                        .findSettlement(TENANT, placed.orderId())
                        .orElseThrow()
                        .settledMinor())
                .as("the money the platform actually has, to the som")
                .isEqualTo(80_000L);
        assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor())
                .as("the points went back and stayed back; settling them a second time from a "
                        + "hold that no longer exists is the invention this refuses")
                .isEqualTo(20_000L);

        // The operator's worklist, which is what makes it resolvable rather than
        // merely recorded.
        assertThat(settlementStore.settlementsRestingPartiallySettled(
                        TENANT, clock.instant().plus(Duration.ofMinutes(1)), 10))
                .extracting(JdbcSettlementStore.SettlementRow::orderId)
                .containsExactly(placed.orderId());

        // And the money stays accountable in both directions: 80 000 came in, so
        // 80 000 is all that can go back out.
        var outcome = tx(() -> remedies.recordRefund(refundOf(placed.orderId(), 80_000L)));
        assertThat(outcome.recorded()).isTrue();
        assertThatThrownBy(() -> tx(() -> remedies.recordRefund(refundOf(placed.orderId(), 1_000L))))
                .hasMessageContaining("cannot exceed what the tenders settled");
    }

    /**
     * A cancellation must not erase money a provider genuinely captured.
     *
     * <p>{@code PARTIALLY_SETTLED} could never rest before, so failing it on a
     * terminal outcome was free. It can rest now, and failing it would zero
     * {@code settled_minor} over 80 000 som that Payme has — leaving the refund
     * that money is owed through with nothing to give back.
     */
    @Test
    @DisplayName("cancelling a short-settled order leaves the collected money accountable")
    void cancellingAShortSettledOrderKeepsTheMoney() {
        seedRedemptionPolicy();
        seedBalance(20_000L);
        var wired = checkoutWith.apply(realPayments(UUID.randomUUID()));
        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-payme-cancel", "PAYME", 20_000L)));

        presentedAttempt(placed.orderId(), PaymentProviderType.PAYME);
        clock.advance(Duration.ofMinutes(31));
        tx(() -> loyaltySweep.releaseStaleHolds());
        advance(placed.orderId(), OrderStatus.CONFIRMED);

        tx(() -> settlementPlanner.recordTerminalOutcome(TENANT, placed.orderId(), "ORDER_CANCELLED", "operator"));

        assertThat(settlementStatus(placed.orderId())).isEqualTo(SettlementStatus.PARTIALLY_SETTLED);
        assertThat(settlementStore
                        .findSettlement(TENANT, placed.orderId())
                        .orElseThrow()
                        .settledMinor())
                .isEqualTo(80_000L);
        assertThat(tx(() -> remedies.recordRefund(refundOf(placed.orderId(), 80_000L)))
                        .recorded())
                .as("the customer's money is still refundable, which failing the settlement "
                        + "would have made impossible")
                .isTrue();
    }

    @Test
    @DisplayName("points and cash settle one order, and a refund returns the money before " + "the points")
    void aSplitTenderOrderIsPlannedAndSettled() {
        seedRedemptionPolicy();
        seedBalance(20_000L);
        var wired = checkoutWith.apply(realPayments(NO_SELLER));

        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-split", "CASH", 20_000L)));
        handOver(placed.orderId());

        assertThat(tenderAmounts(placed.orderId()))
                .as("the balance tender reserves first, so it is sequence one")
                .containsExactly(20_000L, 80_000L);
        assertThat(settlementStatus(placed.orderId())).isEqualTo(SettlementStatus.SETTLED);

        var outcome = tx(() -> remedies.recordRefund(refundOf(placed.orderId(), 80_000L)));

        assertThat(outcome.remedy().attestedMoneyMinor())
                .as("the money unwinds first; a customer refunded 80 000 on this order gets "
                        + "80 000 som and no points back")
                .isEqualTo(80_000L);
        assertThat(outcome.remedy().platformSettledMinor()).isZero();
    }

    /**
     * The regression test for the money bug this change exists to close.
     *
     * <p>Wiring {@code planSettlement} into checkout silently changed what a
     * points hold means. It used to span a checkout; it now has to span checkout
     * to handover, because a cash tender settles {@code ON_HANDOVER} and the
     * balance tender settles with the money tender it accompanies. A Tashkent
     * delivery order is forty to sixty minutes door to door and the hold's fuse
     * was thirty — so the sweep reached a live confirmed order's hold, credited
     * the points back, and the handover then settled a tender whose reservation
     * was already gone without throwing or logging anything. The tenant handed
     * over 100 000 of food, collected 80 000 in cash, and gave the customer their
     * 20 000 back to spend again.
     */
    @Test
    @DisplayName("a cash order handed over an hour after checkout does not give the points back")
    void aSlowCashOrderKeepsItsPointsHold() {
        seedRedemptionPolicy();
        seedBalance(20_000L);
        var wired = checkoutWith.apply(realPayments(NO_SELLER));
        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-slow-cash", "CASH", 20_000L)));
        UUID account =
                loyaltyStore.findAccount(TENANT, BRAND, CUSTOMER).orElseThrow().id();

        assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor())
                .as("the hold is the debit: the points left the balance at checkout")
                .isZero();

        // Thirty-two minutes in, the courier is still on the road and the sweep
        // runs on its two-minute cadence.
        clock.advance(Duration.ofMinutes(32));
        tx(() -> loyaltySweep.releaseStaleHolds());

        assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor())
                .as("a live confirmed order's hold is not an abandoned cart's")
                .isZero();

        handOver(placed.orderId());

        assertThat(settlementStatus(placed.orderId())).isEqualTo(SettlementStatus.SETTLED);
        assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor())
                .as("the tenant handed over 100 000 of food and collected 80 000 in cash; the "
                        + "20 000 in points is spent, not spendable again")
                .isZero();

        // And the order is still refundable, which the released hold made
        // impossible: reverse finds a RELEASED reservation and throws.
        var outcome = tx(() -> remedies.recordRefund(refundOf(placed.orderId(), 100_000L)));
        assertThat(outcome.recorded()).isTrue();
        assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor()).isEqualTo(20_000L);
    }

    /**
     * The other four of {@code HeldTenderProgress.STILL_WORKING}.
     *
     * <p>Only {@code CONFIRMED} was ever exercised by a sweep test, and a status
     * silently dropped from that set is this defect again — a live order losing
     * its points mid-service, with nothing to fail until the money arrives. So
     * every status in the set is swept in, one order walking the whole kitchen and
     * the road with thirty-one minutes between each step, because a renewed hold
     * only becomes stale again after another lifetime and a fixture that did not
     * let that time pass would be asserting about a row the sweep never reached.
     */
    @Test
    @DisplayName("every status the platform is working in keeps the order's points hold")
    void everyWorkingStatusKeepsItsHold() {
        seedRedemptionPolicy();
        seedBalance(20_000L);
        var placed = placeDeliveryOrder("idem-working-statuses", 20_000L);
        UUID account =
                loyaltyStore.findAccount(TENANT, BRAND, CUSTOMER).orElseThrow().id();

        assertThat(placed.status()).isEqualTo(OrderStatus.CONFIRMED);

        for (OrderStatus status : List.of(OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.FULFILLING)) {
            advance(placed.orderId(), status);
            clock.advance(Duration.ofMinutes(31));

            assertThat(tx(() -> loyaltySweep.releaseStaleHolds()))
                    .as("an order in " + status + " is one somebody is still working on")
                    .isZero();
            assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor())
                    .as("the points are spent while the order is live, in " + status)
                    .isZero();
        }

        // Two hours and thirty-three minutes after checkout, the courier arrives
        // and the tenders settle for what they held.
        advance(placed.orderId(), OrderStatus.COMPLETED);
        assertThat(settlementStatus(placed.orderId())).isEqualTo(SettlementStatus.SETTLED);
        assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor()).isZero();
    }

    /**
     * The fifth, which needs its own order because a branch has to be asked before
     * it can be in {@code AWAITING_APPROVAL} at all.
     *
     * <p>An approval deadline is minutes and a hold is thirty of them, so this is
     * not a hypothetical overlap: a branch that takes its time deciding is a
     * branch whose customer loses their points while it does.
     */
    @Test
    @DisplayName("an order waiting on the branch keeps its points hold")
    void anOrderAwaitingApprovalKeepsItsHold() {
        requireApproval();
        seedRedemptionPolicy();
        seedBalance(20_000L);
        var wired = checkoutWith.apply(realPayments(NO_SELLER));
        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-awaiting", "CASH", 20_000L)));
        UUID account =
                loyaltyStore.findAccount(TENANT, BRAND, CUSTOMER).orElseThrow().id();

        assertThat(placed.status()).isEqualTo(OrderStatus.AWAITING_APPROVAL);

        clock.advance(Duration.ofMinutes(31));
        assertThat(orderStore.find(TENANT, placed.orderId()).orElseThrow().status())
                .as("nothing here drives the approval timer, so the branch is still deciding")
                .isEqualTo(OrderStatus.AWAITING_APPROVAL);

        assertThat(tx(() -> loyaltySweep.releaseStaleHolds())).isZero();
        assertThat(loyaltyBalances.balance(TENANT, account).balanceMinor()).isZero();
        assertThat(tenderStatuses(placed.orderId())).containsExactly(TenderStatus.RESERVED, TenderStatus.PLANNED);
    }

    @Test
    @DisplayName("a guest checkout that asks to redeem is refused rather than silently ignored")
    void aGuestCannotRedeem() {
        allowGuestOrders();
        var cart = readyGuestCart();
        var command = guestCheckoutCommand(cart, "idem-guest-points");
        var redeeming = new CheckoutService.CheckoutCommand(
                TENANT,
                BRAND,
                cart,
                command.expectedCartVersion(),
                command.quoteId(),
                command.contextHash(),
                "idem-guest-redeem",
                "CASH",
                10_000L,
                "CUSTOMER",
                null,
                null);

        var refused = tx(() -> checkout.checkout(redeeming));

        assertThat(refused.rejectionCode()).isEqualTo("GUEST_CANNOT_REDEEM");
        assertThat(countOrders()).isZero();
    }

    /**
     * The second way an unrefundable order was reachable, and the cheaper one:
     * omit the field. The storefront request record did not require it and step 7b
     * was gated on it, so a checkout that left it out produced a real, confirmable,
     * completable order with no settlement, no tenders and no refund path.
     */
    @Test
    @DisplayName(
            "a checkout that names no payment method is refused rather than creating an " + "order nobody can refund")
    void aCheckoutMustNameAPaymentMethod() {
        var wired = checkoutWith.apply(realPayments(NO_SELLER));
        var refused = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-no-method", null)));

        assertThat(refused.rejectionCode()).isEqualTo("PAYMENT_METHOD_REQUIRED");
        assertThat(countOrders())
                .as("an order that cannot be refunded must not exist at all")
                .isZero();
    }

    @Test
    @DisplayName("a redemption that would cover the whole order is refused at checkout")
    void pointsCannotCoverTheWholeOrder() {
        var refused = tx(() -> checkout.checkout(checkoutCommand(readyCart(), "idem-all-points", "CASH", 100_000L)));

        // ADR 0046's structural rule: an order with no money tender has no fiscal
        // path and nothing for a courier to collect.
        assertThat(refused.rejectionCode()).isEqualTo("REDEMPTION_EXCEEDS_ORDER");
        assertThat(countOrders()).isZero();
    }

    // ------------------------------------ the intent and the money tender
    //
    // One number with two names. The intent is what a provider or a courier is
    // asked to collect; the money tenders are what the settlement says was
    // collected. They were computed independently — the intent from the quote
    // total, the tender from the total minus the redemption — so a customer who
    // spent 12 000 points on a 94 000 order was asked by Click for 94 000 while
    // the settlement recorded 82 000. The customer paid twice for the same food,
    // and the 12 000 surplus sat on no tender, so no refund could reach it
    // either: every refund path is bounded by refundableMinor() per tender.
    //
    // The fix makes the settlement the authority and the intent the thing told,
    // which is why the tests below read both figures out of the database and
    // compare them to each other rather than to two literals.

    /**
     * The defect, on the storefront path that reaches it.
     *
     * <p>{@code StorefrontOrderingController} carries {@code paymentMethodCode}
     * and {@code redeemFromBalanceMinor} as independent fields and checkout
     * refuses a redemption only for a guest and only when it covers the whole
     * order — never because the method is a provider one. So this is an ordinary
     * order, not a corner.
     */
    @Test
    @DisplayName("a split-tender provider order asks the provider for the total less the points")
    void aSplitTenderProviderOrderIsChargedOnlyTheMoneyLeg() {
        seedRedemptionPolicy();
        seedBalance(20_000L);
        var wired = checkoutWith.apply(realPayments(UUID.randomUUID()));

        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-split-intent", "PAYME", 20_000L)));

        assertThat(placed.status()).isEqualTo(OrderStatus.PAYMENT_AUTHORIZING);
        assertThat(orderStore.find(TENANT, placed.orderId()).orElseThrow().totalMinor())
                .as("the order is still worth what the menu said")
                .isEqualTo(100_000L);
        assertThat(tenderAmounts(placed.orderId())).containsExactly(20_000L, 80_000L);
        assertThat(intentAmount(placed.orderId()))
                .as("the provider collects what is left after the points, not the order total; "
                        + "asking for 100 000 charges the customer for the 20 000 they spent")
                .isEqualTo(80_000L);
    }

    /**
     * The property, rather than two more literals.
     *
     * <p>Stating it once over several splits is the point: the two figures drifted
     * because each was derived separately, and a test that asserts each against a
     * number of its own would let them drift again the next time only one of the
     * two derivations is edited.
     */
    @Test
    @DisplayName("the intent is for exactly what the settlement's money tenders sum to")
    void theIntentAndTheMoneyTendersAreOneFigure() {
        seedRedemptionPolicy();
        seedBalance(70_000L);
        var wired = checkoutWith.apply(realPayments(UUID.randomUUID()));

        for (long redeemed : List.of(0L, 1L, 12_000L, 50_000L)) {
            var placed =
                    tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-leg-" + redeemed, "PAYME", redeemed)));

            assertThat(intentAmount(placed.orderId()))
                    .as("redeeming %d: the intent and the money tenders are one figure", redeemed)
                    .isEqualTo(moneyTenderMinor(placed.orderId()));
        }
    }

    /**
     * The boundary {@code RedemptionLimit} already names, carried through to the
     * intent.
     *
     * <p>One som of money is the floor a redemption may not configure away, so it
     * is a real amount due and the provider is asked for it. The order is made
     * small rather than the policy generous because V0042 caps
     * {@code max_share_basis_points} at 9000 in the database: 90% of ten som
     * leaves exactly one.
     *
     * <p>Zero is the other side of the same boundary and stays a refusal —
     * {@code pointsCannotCoverTheWholeOrder} above — because a provider intent for
     * nothing is not a thing and an order with no money tender has no fiscal path.
     */
    @Test
    @DisplayName("points covering all but one som leave a money leg of one som")
    void pointsMayCoverAllButOneSom() {
        jdbc.sql("UPDATE pricing.prices SET amount_minor = 10").update();
        seedRedemptionPolicy(9000, 1L);
        seedBalance(9L);
        var wired = checkoutWith.apply(realPayments(UUID.randomUUID()));
        UUID cart = openCart();
        putLine(cart, "a", burgerVariant, 1);
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));

        var placed = tx(() -> wired.checkout(checkoutCommand(cart, "idem-one-som", "PAYME", 9L)));

        assertThat(orderStore.find(TENANT, placed.orderId()).orElseThrow().totalMinor())
                .isEqualTo(10L);
        assertThat(tenderAmounts(placed.orderId())).containsExactly(9L, 1L);
        assertThat(intentAmount(placed.orderId()))
                .as("a som is a whole minor unit in this market and a real amount due")
                .isEqualTo(1L);
    }

    /**
     * The same figure where no provider is involved at all.
     *
     * <p>A cash order does get an intent — it is the row ADR 0038's
     * {@code NOT_APPLICABLE} fiscal decision hangs off — and its amount is what a
     * courier is expected to arrive with. An intent for the menu price on an order
     * part-paid from points is a courier asking for 20 000 som the customer
     * already spent.
     */
    @Test
    @DisplayName("a cash split tender collects at the door only what the points did not cover")
    void aCashSplitTenderCollectsOnlyTheMoneyLeg() {
        seedRedemptionPolicy();
        seedBalance(20_000L);
        var wired = checkoutWith.apply(realPayments(NO_SELLER));

        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-split-cash-intent", "CASH", 20_000L)));

        assertThat(placed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(tenderAmounts(placed.orderId())).containsExactly(20_000L, 80_000L);
        assertThat(intentAmount(placed.orderId()))
                .as("the cash intent is the figure the courier collects, not the menu price")
                .isEqualTo(80_000L);
        assertThat(tx(() -> settlements.cashDueMinor(TENANT, placed.orderId(), "CASH")))
                .as("and it agrees with the figure ADR 0014 snapshots onto the assignment")
                .isEqualTo(80_000L);

        handOver(placed.orderId());
        assertThat(settlementStatus(placed.orderId())).isEqualTo(SettlementStatus.SETTLED);
    }

    /**
     * The order that has no balance leg at all, unchanged.
     *
     * <p>A guest cannot redeem, so the money leg is the order total and this is
     * the case that must not move. It is the majority of orders.
     */
    @Test
    @DisplayName("a guest order, which can redeem nothing, is still charged the whole total")
    void aGuestOrderIsChargedTheWholeTotal() {
        allowGuestOrders();
        var wired = checkoutWith.apply(realPayments(NO_SELLER));

        var placed = tx(() -> wired.checkout(guestCheckoutCommand(readyGuestCart(), "idem-guest-intent")));

        assertThat(placed.created()).isTrue();
        assertThat(tenderAmounts(placed.orderId())).containsExactly(100_000L);
        assertThat(intentAmount(placed.orderId()))
                .isEqualTo(
                        orderStore.find(TENANT, placed.orderId()).orElseThrow().totalMinor());
        assertThat(intentAmount(placed.orderId())).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("a replayed checkout plans exactly one settlement")
    void aReplayedCheckoutPlansOneSettlement() {
        var wired = checkoutWith.apply(realPayments(NO_SELLER));
        var command = checkoutCommand(readyCart(), "idem-replay-settlement", "CASH");

        var first = tx(() -> wired.checkout(command));
        var second = tx(() -> wired.checkout(command));

        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(jdbc.sql("SELECT count(*) FROM payments.order_settlements")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM payments.tenders")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a settlement is invisible to another tenant")
    void aSettlementIsScopedToItsTenant() {
        var wired = checkoutWith.apply(realPayments(NO_SELLER));
        var placed = tx(() -> wired.checkout(checkoutCommand(readyCart(), "idem-tenant", "CASH")));
        handOver(placed.orderId());
        UUID otherTenant = UUID.randomUUID();

        assertThat(settlementStore.findSettlement(otherTenant, placed.orderId()))
                .as("an order id is a UUID somebody else can hold; the tenant predicate is what "
                        + "stands between it and another tenant's money")
                .isEmpty();
        assertThatThrownBy(() ->
                        tx(() -> settlements.refund(otherTenant, placed.orderId(), 10_000L, "GOODWILL", "attacker")))
                .hasMessageContaining("The order has no settlement");
        assertThat(refundedMinor(placed.orderId())).isZero();
    }

    // ----------------------------------------------------------- fixtures

    private static final PaymentIntentPort UNWIRED_PAYMENTS = new PaymentIntentPort() {
        @Override
        public boolean paymentRequiredBeforeConfirmation(UUID tenantId, UUID orderId, String paymentMethodCode) {
            return false;
        }

        @Override
        public UUID createIntent(
                UUID tenantId,
                UUID orderId,
                long amountMinor,
                String currency,
                String paymentMethodCode,
                String idempotencyKey) {
            return null;
        }

        @Override
        public boolean isWired() {
            return false;
        }
    };

    /**
     * ADR 0014's planner over this suite's real tables, assembled where it is used.
     *
     * <p>The delivery order port is the production one, because it is the thing
     * this change adds and a stand-in here would assert nothing at all.
     */
    private uz.horecaos.platform.fulfillment.application.DeliveryPlanningService deliveryPlanning() {
        return new uz.horecaos.platform.fulfillment.application.DeliveryPlanningService(
                deliveryOrders,
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore(jdbc),
                new JdbcPolicyResolver(jdbc, objectMapper),
                clock);
    }

    /** Gives the branch a pin, which ADR 0014 refuses to plan without. */
    private void placeBranchOnTheMap() {
        jdbc.sql("""
                UPDATE tenant.locations
                SET latitude = 41.2995, longitude = 69.2401, coordinate_source = 'MERCHANT_PIN'
                WHERE tenant_id = :tenantId AND id = :locationId
                """).param("tenantId", TENANT).param("locationId", LOCATION).update();
    }

    private UUID openDeliveryCart() {
        return tx(() -> carts.create(TENANT, BRAND, LOCATION, "STOREFRONT", FulfillmentMode.DELIVERY, CUSTOMER, null))
                .cartId();
    }

    private CartService.DestinationCommand destinationCommand(UUID addressId) {
        return new CartService.DestinationCommand(addressId, "Dilnoza", "+998901112233", null);
    }

    /**
     * One of ADR 0015's addresses, written the way the customers module writes it.
     *
     * <p>The field document is encrypted under {@code customer.addresses}'s own
     * associated data rather than stubbed, because the whole point of the capture
     * path is that it decrypts a row it does not own and re-encrypts it against the
     * row it does. A stub agreeing with itself would assert nothing.
     *
     * <p>A null coordinate is written as {@code LANDMARK_ONLY} and a present one as
     * {@code CUSTOMER_PIN}, because V0021's constraint makes source and coordinates
     * an equivalence and a fixture that violated it would fail on the insert rather
     * than in the assertion.
     */
    private UUID insertAddress(UUID accountId, String label, Double latitude, Double longitude) {
        UUID addressId = UUID.randomUUID();
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
                        uz.horecaos.platform.iam.api.protection.DataClass.PERSONAL,
                        new FieldProtection.RecordRef("customer.addresses", "encrypted_fields", addressId),
                        document)
                .serialize();
        String instructions = protection
                .protect(
                        TENANT,
                        uz.horecaos.platform.iam.api.protection.DataClass.PERSONAL,
                        new FieldProtection.RecordRef(
                                "customer.addresses", "delivery_instructions_encrypted", addressId),
                        "Ring the top bell")
                .serialize();

        jdbc.sql("""
                INSERT INTO customer.addresses (id, tenant_id, customer_account_id, label,
                    encrypted_fields, delivery_instructions_encrypted, latitude, longitude,
                    coordinate_source, status, version)
                VALUES (:id, :tenantId, :accountId, :label, :fields, :instructions,
                    :latitude, :longitude, :source, 'ACTIVE', 1)
                """)
                .param("id", addressId)
                .param("tenantId", TENANT)
                .param("accountId", accountId)
                .param("label", label)
                .param("fields", fields)
                .param("instructions", instructions)
                .param("latitude", latitude)
                .param("longitude", longitude)
                .param("source", latitude == null ? "LANDMARK_ONLY" : "CUSTOMER_PIN")
                .update();
        return addressId;
    }

    /** A delivery cart with a destination, priced, checked out. */
    private CheckoutService.CheckoutResult placeDeliveryOrder(String idempotencyKey) {
        return placeDeliveryOrder(idempotencyKey, 0L);
    }

    /** The same, part-paid from the customer's balance. */
    private CheckoutService.CheckoutResult placeDeliveryOrder(String idempotencyKey, long redeemFromBalanceMinor) {
        UUID address = insertAddress(CUSTOMER, "Home", 41.311081, 69.240562);
        UUID cart = openDeliveryCart();
        putLine(cart, "a", burgerVariant, 2);
        tx(() -> carts.setDestination(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), destinationCommand(address)));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        return tx(() -> checkout.checkout(checkoutCommand(cart, idempotencyKey, "CASH", redeemFromBalanceMinor)));
    }

    private long reservationCount() {
        return jdbc.sql("SELECT count(*) FROM inventory.reservations")
                .query(Long.class)
                .single();
    }

    private UUID openCart() {
        return tx(() -> carts.create(TENANT, BRAND, LOCATION, "STOREFRONT", FulfillmentMode.PICKUP, CUSTOMER, null))
                .cartId();
    }

    private void putLine(UUID cartId, String lineKey, UUID variantId, int quantity) {
        tx(() -> carts.putLine(
                TENANT, BRAND, CUSTOMER, cartId, cartVersion(cartId), lineKey, variantId, quantity, List.of(), null));
    }

    /** A cart with two burgers, priced and ready to check out. */
    private UUID readyCart() {
        UUID cart = openCart();
        putLine(cart, "a", burgerVariant, 2);
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));
        return cart;
    }

    private CheckoutService.CheckoutResult placeOrder(String idempotencyKey) {
        UUID cart = readyCart();
        return tx(() -> checkout.checkout(checkoutCommand(cart, idempotencyKey)));
    }

    /**
     * A checkout that names how it will be paid, because every real one does.
     *
     * <p>This defaulted to no method, which is now a refusal: an order with no
     * payment method plans no settlement, and an order with no settlement can
     * never be refunded. Cash is the market's majority tender and the honest
     * default here.
     */
    private CheckoutService.CheckoutCommand checkoutCommand(UUID cartId, String idempotencyKey) {
        return checkoutCommand(cartId, idempotencyKey, "CASH");
    }

    private CheckoutService.CheckoutCommand checkoutCommand(
            UUID cartId, String idempotencyKey, String paymentMethodCode) {
        return checkoutCommand(cartId, idempotencyKey, paymentMethodCode, 0L);
    }

    private CheckoutService.CheckoutCommand checkoutCommand(
            UUID cartId, String idempotencyKey, String paymentMethodCode, long redeemFromBalanceMinor) {
        var cart = readCart(cartId);
        return new CheckoutService.CheckoutCommand(
                TENANT,
                BRAND,
                cartId,
                cart.version(),
                cart.pricingQuoteId(),
                cart.pricingContextHash(),
                idempotencyKey,
                paymentMethodCode,
                redeemFromBalanceMinor,
                "CUSTOMER",
                CUSTOMER.toString(),
                null);
    }

    private OrderStateService.DecisionCommand decision(String decisionId, OrderStateService.DecisionAction action) {
        return new OrderStateService.DecisionCommand(
                decisionId,
                action,
                "HORECAOS_OPERATIONS",
                "USER",
                "operator",
                "OPERATOR_DECISION",
                clock.instant(),
                null);
    }

    private void advance(UUID orderId, OrderStatus target) {
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();
        tx(() -> orderState.advance(TENANT, orderId, target, version, "KITCHEN", "USER", "operator", null));
    }

    private JdbcCartStore.CartRow readCart(UUID cartId) {
        return cartStore.find(TENANT, BRAND, cartId).orElseThrow();
    }

    private int cartVersion(UUID cartId) {
        return readCart(cartId).version();
    }

    private long countOrders() {
        return jdbc.sql("SELECT count(*) FROM ordering.orders")
                .query(Long.class)
                .single();
    }

    private String reservationStatus() {
        return jdbc.sql("SELECT status FROM inventory.reservations")
                .query(String.class)
                .single();
    }

    private long movementCount() {
        return jdbc.sql("SELECT count(*) FROM inventory.movements")
                .query(Long.class)
                .single();
    }

    /** Switches the location to RESTAURANT_APPROVAL with a five-minute deadline. */
    private void requireApproval() {
        var policy = new OrderAcceptancePolicy(
                AcceptanceMode.RESTAURANT_APPROVAL,
                ApprovalChannel.HORECAOS_OPERATIONS,
                300,
                ApprovalTimeoutAction.AUTO_REJECT,
                true,
                true);
        UUID policyId = UUID.randomUUID();
        var from = java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC);

        jdbc.sql("""
                INSERT INTO tenant.policies (id, key_code, scope_type, tenant_id, brand_id,
                    location_id, version, status, document, document_hash, valid_from, created_by)
                VALUES (:id, 'ordering.acceptance', 'LOCATION', :tenantId, :brandId, :locationId,
                    1, 'ACTIVE', CAST(:document AS jsonb), :hash, :from, 'test')
                """)
                .param("id", policyId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("document", JsonMapper.builder().build().writeValueAsString(policy))
                .param("hash", "0".repeat(64))
                .param("from", from)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.policy_current (key_code, scope_type, tenant_id, brand_id,
                    location_id, policy_id, policy_version, activated_by)
                VALUES ('ordering.acceptance', 'LOCATION', :tenantId, :brandId, :locationId,
                    :policyId, 1, 'test')
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("policyId", policyId)
                .update();
    }

    private <T> T tx(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status -> work.get());
    }

    private void tx(Runnable work) {
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(status -> work.run());
    }

    // ----------------------------------------------- settlement fixtures

    /**
     * Drives the order to the door: cash becomes money at handover and not before.
     *
     * <p><strong>Time passes.</strong> This was three {@code advance} calls with
     * no {@code clock.advance} between them, so the whole suite reserved a points
     * hold and settled it at the same instant on the {@link MutableClock} — and
     * every lifetime in this area, the hold's above all, was therefore never
     * tested at all. Forty minutes is a Tashkent delivery order door to door: ten
     * on the pass, thirty on the road. A fixture's clock is the test's clock, and
     * a fixture that never lets time pass cannot fail a test about elapsed time.
     */
    private void handOver(UUID orderId) {
        advance(orderId, OrderStatus.PREPARING);
        clock.advance(Duration.ofMinutes(10));
        advance(orderId, OrderStatus.READY);
        clock.advance(Duration.ofMinutes(30));
        advance(orderId, OrderStatus.COMPLETED);
    }

    // ------------------------------------------- ADR 0013 payment attempts
    //
    // The attempt is the fact that separates a customer sitting on Payme's
    // checkout page from a customer who closed the tab, and until this suite
    // could create one it could only ever assert the second. Everything below
    // writes through the production store, in the order and with the columns
    // PaymentAttemptService writes them.

    /**
     * The ADR 0013 merchant binding an attempt hangs from, and the chain the
     * schema requires beneath it.
     *
     * <p>{@code payments.payment_attempts.merchant_binding_id} is a real foreign
     * key, so a fixture that invented the id would be asserting against a world
     * the schema does not permit.
     */
    private UUID seedMerchantBinding(PaymentProviderType provider) {
        UUID installation = UUID.randomUUID();
        UUID binding = UUID.randomUUID();
        UUID legalEntity = UUID.randomUUID();
        UUID merchantBinding = UUID.randomUUID();
        String environment = provider.name() + "_SANDBOX";

        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'PAYMENT', :provider, 'https://example.test', false, 'example.test')
                ON CONFLICT (code) DO NOTHING
                """)
                .param("code", environment)
                .param("provider", provider.name())
                .update();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status)
                VALUES (:id, :tenantId, 'PAYMENT', :provider, :environment, :provider, 'ACTIVE')
                """)
                .param("id", installation)
                .param("tenantId", TENANT)
                .param("provider", provider.name())
                .param("environment", environment)
                .update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installation, :brandId, 'ACTIVE')
                """)
                .param("id", binding)
                .param("tenantId", TENANT)
                .param("installation", installation)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :tenantId, :code, 'Sinov MCHJ', '123456789', 'ACTIVE')
                """)
                .param("id", legalEntity)
                .param("tenantId", TENANT)
                .param("code", "LE-" + provider.name())
                .update();
        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (id, tenant_id, legal_entity_id,
                    provider_type, installation_id, binding_id, merchant_account_reference,
                    secret_reference, callback_path_segment, supports_reversal,
                    supports_partner_fiscalization, status, effective_from)
                VALUES (:id, :tenantId, :entity, :provider, :installation, :binding, 'service-1',
                    :secret, :segment, true, false, 'ACTIVE', :from)
                """)
                .param("id", merchantBinding)
                .param("tenantId", TENANT)
                .param("entity", legalEntity)
                .param("provider", provider.name())
                .param("installation", installation)
                .param("binding", binding)
                .param(
                        "secret",
                        "horecaos:test:provider_payment:tenant:"
                                + provider.name().toLowerCase(java.util.Locale.ROOT))
                .param("segment", "checkout-" + merchantBinding)
                .param("from", java.time.LocalDate.of(2020, 1, 1))
                .update();
        return merchantBinding;
    }

    /**
     * The attempt {@code POST /orders/{id}/payment-sessions} opens: a payable link
     * minted and handed to a customer, and nothing back from the provider.
     *
     * <p>This is the abandoned tab. Everything the hold sweep must still release
     * on the old cadence stops here.
     */
    private UUID presentedAttempt(UUID orderId, PaymentProviderType provider) {
        var intent = intentStore.findLiveForOrder(TENANT, orderId).orElseThrow();
        UUID attemptId = UUID.randomUUID();
        paymentAttemptStore.insert(new uz.horecaos.platform.payments.domain.PaymentAttempt(
                attemptId,
                TENANT,
                intent.id(),
                provider,
                seedMerchantBinding(provider),
                attemptId.toString().replace("-", ""),
                clock.instant().atZone(ZoneId.of("Asia/Tashkent")).toLocalDate(),
                null,
                null,
                intent.amount(),
                uz.horecaos.platform.payments.domain.PaymentAttemptStatus.INITIATED,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                clock.instant(),
                null));
        paymentAttemptStore.recordPresentation(
                TENANT,
                attemptId,
                uz.horecaos.platform.payments.domain.PresentationKind.PAYMENT_LINK,
                "invoice-" + attemptId,
                null,
                clock.instant());
        paymentAttemptStore.transition(
                TENANT,
                attemptId,
                uz.horecaos.platform.payments.domain.PaymentAttemptStatus.INITIATED,
                uz.horecaos.platform.payments.domain.PaymentAttemptStatus.PRESENTED,
                null,
                null,
                null,
                null,
                clock.instant());
        return attemptId;
    }

    /**
     * The customer reached the provider and the provider created a transaction.
     *
     * <p>Payme's {@code CreateTransaction}, in other words, with the window
     * measured from Payme's own {@code params.time} — which is the twelve hours
     * {@code PaymentAttemptService.PAYME_TRANSACTION_TIMEOUT} names and the whole
     * reason an order can sit in {@code PAYMENT_AUTHORIZING} for an hour without
     * anything being wrong.
     */
    private UUID reservedAttempt(UUID orderId, PaymentProviderType provider, Duration window) {
        UUID attemptId = presentedAttempt(orderId, provider);
        String providerTransaction = "px" + attemptId.toString().replace("-", "");
        paymentAttemptStore.transition(
                TENANT,
                attemptId,
                uz.horecaos.platform.payments.domain.PaymentAttemptStatus.PRESENTED,
                uz.horecaos.platform.payments.domain.PaymentAttemptStatus.RESERVED,
                new uz.horecaos.platform.payments.domain.ProviderEvidence("1", null, clock.instant()),
                providerTransaction,
                null,
                null,
                clock.instant());
        paymentAttemptStore.recordProviderCreation(
                TENANT,
                attemptId,
                providerTransaction,
                clock.instant(),
                clock.instant().plus(window));
        return attemptId;
    }

    /** The money lands: Payme's {@code PerformTransaction}, Click's Complete. */
    private void captureAttempt(UUID attemptId) {
        paymentAttemptStore.transition(
                TENANT,
                attemptId,
                uz.horecaos.platform.payments.domain.PaymentAttemptStatus.RESERVED,
                uz.horecaos.platform.payments.domain.PaymentAttemptStatus.CAPTURED,
                new uz.horecaos.platform.payments.domain.ProviderEvidence("2", null, clock.instant()),
                null,
                null,
                null,
                clock.instant());
    }

    private OrderRemedyService.RefundCommand refundOf(UUID orderId, long amountMinor) {
        return new OrderRemedyService.RefundCommand(
                TENANT,
                orderId,
                amountMinor,
                "UZS",
                "GOODWILL",
                "Cold food, customer called",
                ExecutionChannel.CASH_DRAWER,
                null,
                "cashier-7",
                clock.instant(),
                uz.horecaos.platform.audit.api.ActorRef.user("support-1", null),
                "k-" + UUID.randomUUID(),
                null);
    }

    private SettlementStatus settlementStatus(UUID orderId) {
        return settlementStore.findSettlement(TENANT, orderId).orElseThrow().status();
    }

    private List<TenderStatus> tenderStatuses(UUID orderId) {
        UUID settlementId =
                settlementStore.findSettlement(TENANT, orderId).orElseThrow().id();
        return settlementStore.tendersOf(TENANT, settlementId).stream()
                .map(JdbcSettlementStore.TenderRow::status)
                .toList();
    }

    private List<Long> tenderAmounts(UUID orderId) {
        UUID settlementId =
                settlementStore.findSettlement(TENANT, orderId).orElseThrow().id();
        return settlementStore.tendersOf(TENANT, settlementId).stream()
                .map(JdbcSettlementStore.TenderRow::amountMinor)
                .toList();
    }

    /** What the settlement says is to be collected in money, read back from the rows. */
    private long moneyTenderMinor(UUID orderId) {
        UUID settlementId =
                settlementStore.findSettlement(TENANT, orderId).orElseThrow().id();
        return settlementStore.tendersOf(TENANT, settlementId).stream()
                .filter(tender -> !tender.settlesFromBalance())
                .mapToLong(JdbcSettlementStore.TenderRow::amountMinor)
                .sum();
    }

    /** What the payment intent asks for. */
    private long intentAmount(UUID orderId) {
        return intentStore
                .findLiveForOrder(TENANT, orderId)
                .orElseThrow()
                .amount()
                .value();
    }

    private long refundedMinor(UUID orderId) {
        UUID settlementId =
                settlementStore.findSettlement(TENANT, orderId).orElseThrow().id();
        return settlementStore.tendersOf(TENANT, settlementId).stream()
                .mapToLong(JdbcSettlementStore.TenderRow::refundedMinor)
                .sum();
    }

    /**
     * ADR 0046's redemption policy, as product and finance would confirm it: half
     * an order at most, on orders of 50 000 or more, delivery fee excluded.
     */
    private void seedRedemptionPolicy() {
        seedRedemptionPolicy(5000, 50_000L);
    }

    /**
     * The same, with the share and the minimum stated.
     *
     * <p>V0042 caps {@code max_share_basis_points} at 9000 in the database, so the
     * ceiling this overload can reach is the ceiling a tenant can.
     */
    private void seedRedemptionPolicy(int maxShareBasisPoints, long minOrderMinor) {
        jdbc.sql("""
                INSERT INTO loyalty.redemption_policies (id, tenant_id, brand_id,
                    max_share_basis_points, min_order_minor, excludes_delivery_fee,
                    allowed_channels, status, version, valid_from)
                VALUES (:id, :tenantId, :brandId, :maxShare, :minOrder, true, '{}', 'ACTIVE', 1,
                    :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("maxShare", maxShareBasisPoints)
                .param("minOrder", minOrderMinor)
                .param("validFrom", java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
    }

    /**
     * Gives the customer a spendable balance the way an operator would — an
     * adjustment with a reason, which is also ADR 0046's rollout path for a legacy
     * opening balance.
     */
    private void seedBalance(long amountMinor) {
        tx(() -> loyaltyAdjustments.adjust(
                new uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService.AdjustmentCommand(
                        TENANT,
                        BRAND,
                        CUSTOMER,
                        amountMinor,
                        "UZS",
                        uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService.REASON_LEGACY_OPENING_BALANCE,
                        "Seeded for the test",
                        uz.horecaos.platform.audit.api.ActorRef.user("seed-operator", "Seed"),
                        "seed-" + UUID.randomUUID(),
                        "corr-seed")));
    }

    private void allowGuestOrders() {
        jdbc.sql("UPDATE tenant.sales_channels SET guest_orders_allowed = true WHERE id = :id")
                .param("id", storefrontChannel)
                .update();
    }

    /**
     * A guest's cart: no account behind it, and therefore no balance to draw on.
     *
     * <p>Assembled as an account holder's and then made a guest's by hand, because
     * {@code CartService.ownedBy} answers false for a null caller and so no guest
     * cart in this build can be edited or priced through the service at all. That
     * is a real gap and it is not this change's; what matters here is that the
     * checkout — the production one, unchanged — meets a cart with no account on
     * it and still plans a settlement.
     */
    private UUID readyGuestCart() {
        UUID cart = readyCart();
        jdbc.sql("""
                UPDATE ordering.carts
                   SET customer_account_id = NULL, guest_reference_hash = :guest
                 WHERE id = :id
                """)
                .param("guest", "guest-" + UUID.randomUUID())
                .param("id", cart)
                .update();
        return cart;
    }

    private CheckoutService.CheckoutCommand guestCheckoutCommand(UUID cartId, String idempotencyKey) {
        var cart = readCart(cartId);
        return new CheckoutService.CheckoutCommand(
                TENANT,
                BRAND,
                cartId,
                cart.version(),
                cart.pricingQuoteId(),
                cart.pricingContextHash(),
                idempotencyKey,
                "CASH",
                0L,
                "CUSTOMER",
                null,
                null);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void seedTenancyAndCatalog() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'ordering-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        insertLocation(LOCATION, "MAIN01", "main-01");
        insertLocation(OTHER_LOCATION, "MAIN02", "main-02");

        insertCustomer(CUSTOMER);
        insertCustomer(OTHER_CUSTOMER);

        storefrontChannel = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE', false)
                """).param("id", storefrontChannel).param("tenantId", TENANT).update();

        for (UUID location : List.of(LOCATION, OTHER_LOCATION)) {
            jdbc.sql("""
                    INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id,
                        status)
                    VALUES (:tenantId, :channelId, :locationId, 'ACTIVE')
                    """)
                    .param("tenantId", TENANT)
                    .param("channelId", storefrontChannel)
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
        }
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
                    .param("opens", LocalTime.of(9, 0))
                    .param("closes", LocalTime.of(23, 0))
                    .update();
        }
        for (UUID location : List.of(LOCATION, OTHER_LOCATION)) {
            for (FulfillmentMode mode : List.of(FulfillmentMode.PICKUP, FulfillmentMode.DELIVERY)) {
                jdbc.sql("""
                        INSERT INTO tenant.location_service_bindings (tenant_id, brand_id,
                            location_id, fulfillment_mode, schedule_id)
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

        catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        burgerVariant = seedProduct("BURGER", "Qo'y burger");
        pizzaVariant = seedProduct("PIZZA", "Pizza");
        seedPublication("STOREFRONT");
        seedPublishedModifierRules();
    }

    private void insertLocation(UUID id, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Branch', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void insertCustomer(UUID id) {
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :tenantId, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", id).param("tenantId", TENANT).update();
    }

    private void seedPublication(String channel) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, :channel, 'PUBLISHED', 'hash', now())
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .param("channel", channel)
                .update();
        if ("STOREFRONT".equals(channel)) {
            publicationId = id;
        }
    }

    /**
     * Publishes the pizza with a size group, and one group it does not offer.
     *
     * <p>On the pizza and not the burger, deliberately: every other test in this
     * class builds a burger basket with no modifiers, and those baskets are still
     * legal precisely because the published menu says nothing about the burger's
     * groups. Both halves are worth having in one fixture — the item whose rules
     * the cart must enforce, and the item it has no rules for.
     */
    private void seedPublishedModifierRules() {
        sizeGroup = UUID.randomUUID();
        sizeSmall = UUID.randomUUID();
        sizeMedium = UUID.randomUUID();
        sizeLarge = UUID.randomUUID();
        UUID extrasGroup = UUID.randomUUID();
        extrasBacon = UUID.randomUUID();

        insertPublicationItem("PRODUCT", productIdByCode.get("PIZZA"), """
                {"code": "PIZZA", "status": "ACTIVE",
                 "variants": [{"variantId": "%s", "status": "ACTIVE"}],
                 "modifierGroupIds": ["%s"]}
                """.formatted(pizzaVariant, sizeGroup));

        insertPublicationItem("MODIFIER_GROUP", sizeGroup, """
                {"code": "SIZE", "required": true, "minimumSelections": 1,
                 "maximumSelections": 2, "allowSameOptionMultipleTimes": false,
                 "options": [{"optionId": "%s", "maximumQuantity": 1},
                             {"optionId": "%s", "maximumQuantity": 1},
                             {"optionId": "%s", "maximumQuantity": 1}]}
                """.formatted(sizeSmall, sizeMedium, sizeLarge));

        insertPublicationItem("MODIFIER_GROUP", extrasGroup, """
                {"code": "EXTRAS", "required": false, "minimumSelections": 0,
                 "maximumSelections": 3, "allowSameOptionMultipleTimes": true,
                 "options": [{"optionId": "%s", "maximumQuantity": 2}]}
                """.formatted(extrasBacon));
    }

    private void insertPublicationItem(String entityType, UUID entityId, String content) {
        jdbc.sql("""
                INSERT INTO catalog.publication_items (publication_id, tenant_id, brand_id,
                    entity_type, entity_id, entity_version, immutable_content_json)
                VALUES (:publicationId, :tenantId, :brandId, :entityType, :entityId, 1,
                    CAST(:content AS jsonb))
                """)
                .param("publicationId", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("content", content)
                .update();
    }

    private UUID seedProduct(String code, String name) {
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
        productIdByCode.put(code, productId);
        return variantId;
    }

    private void seedPricingAndStock() {
        UUID priceBook = UUID.randomUUID();
        var validFrom = java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC);

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

        for (UUID location : List.of(LOCATION, OTHER_LOCATION)) {
            inventory.listVariantAtLocation(TENANT, BRAND, location, burgerVariant, TrackingMode.BINARY);
        }
        assertThat(pizzaVariant).isNotNull();
    }

    /** Collects the ordering facts a transaction publishes, in order. */
    private static final class RecordingEventPublisher implements ApplicationEventPublisher {

        private final List<OrderingEvent> events = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        /**
         * Stands in for the Spring dispatch a {@code BEFORE_COMMIT} listener gets.
         *
         * <p>These services are wired by hand, so nothing would otherwise deliver
         * {@code OrderConfirmed} to the listeners that hang off it — and the
         * payments listener that settles a provider tender is production code
         * whose absence is exactly what a test must not paper over.
         */
        private volatile java.util.function.Consumer<OrderConfirmed> onConfirmed = confirmed -> {};

        @Override
        public void publishEvent(Object event) {
            if (event instanceof OrderingEvent ordering) {
                events.add(ordering);
            }
            if (event instanceof OrderConfirmed confirmed) {
                onConfirmed.accept(confirmed);
            }
        }
    }

    /**
     * ADR 0022's approval gate, standing aside.
     *
     * <p>Whether a remedy needs a second pair of eyes is decided and tested in the
     * payments suite; here it must not be the reason a refund fails, because the
     * question under test is whether there is anything to refund at all.
     */
    private static final uz.horecaos.platform.audit.api.ApprovalService ALWAYS_APPROVES =
            new uz.horecaos.platform.audit.api.ApprovalService() {

                @Override
                public uz.horecaos.platform.audit.api.ApprovalOutcome requireApproval(
                        uz.horecaos.platform.audit.api.ApprovalRequestCommand command) {
                    return new uz.horecaos.platform.audit.api.ApprovalOutcome.Approved(
                            UUID.randomUUID(), "checker-1", () -> {});
                }

                @Override
                public void decide(
                        UUID requestId,
                        Decision decision,
                        uz.horecaos.platform.audit.api.ActorRef approver,
                        String reason) {}

                @Override
                public int expireOverdue() {
                    return 0;
                }
            };

    /** Lets a test move time forward without sleeping. */
    private static final class MutableClock extends java.time.Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
