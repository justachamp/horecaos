package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
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
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.migration.application.MigrationOwnershipService;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcMigrationScopeStore;
import uz.horecaos.platform.ordering.api.OrderCancelled;
import uz.horecaos.platform.ordering.api.OrderingEvent;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.ordering.application.CheckoutService;
import uz.horecaos.platform.ordering.application.OrderAcceptancePolicyService;
import uz.horecaos.platform.ordering.application.OrderAmendmentService;
import uz.horecaos.platform.ordering.application.OrderInventoryProcess;
import uz.horecaos.platform.ordering.application.OrderOutcomeReasonService;
import uz.horecaos.platform.ordering.application.OrderOutcomeService;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.application.OrderStateService;
import uz.horecaos.platform.ordering.domain.AmendmentCommandType;
import uz.horecaos.platform.ordering.domain.AmendmentStatus;
import uz.horecaos.platform.ordering.domain.CustomerRefund;
import uz.horecaos.platform.ordering.domain.LiabilityParty;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.domain.OutcomeReasonKind;
import uz.horecaos.platform.ordering.domain.OutcomeSystemCategory;
import uz.horecaos.platform.ordering.domain.StockDisposition;
import uz.horecaos.platform.ordering.infrastructure.catalog.JdbcOrderCatalogSnapshot;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCheckoutAttemptStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderAmendmentStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOutcomeReasonStore;
import uz.horecaos.platform.ordering.infrastructure.pos.JdbcPosExportStatus;
import uz.horecaos.platform.ordering.infrastructure.tenancy.JdbcOrderingTenantContext;
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
 * ADR 0039: amendment, revisions, and terminal outcome accounting.
 *
 * <p>Runs against a real database for the same reason the ADR 0019 suite does:
 * every property worth asserting here only exists there. Whether a second
 * operator can open an amendment on an order somebody already has, whether an
 * increase can be applied without the customer's recorded agreement, whether
 * attribution survives a hand-written UPDATE, and whether a revision that does
 * not reconcile can be stored at all are all settled by constraints rather than
 * by application code, and a stubbed store would agree with itself about every
 * one of them.
 *
 * <p>The services are wired by hand, so each test states exactly which
 * collaborators are real. All of them are.
 */
class OrderAmendmentAndOutcomeTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();

    /** A Friday at noon Tashkent time, comfortably inside every seeded schedule. */
    private static final Instant NOW = Instant.parse("2026-08-21T07:00:00Z");

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private MutableClock clock;
    private RecordingEventPublisher published;

    private CartService carts;
    private CheckoutService checkout;
    private OrderStateService orderState;
    private uz.horecaos.platform.payments.settlement.CheckoutSettlementPlanner settlementPlanner;
    private OrderQueryService orderQuery;
    private OrderAmendmentService amendments;
    private OrderOutcomeService outcomes;
    private OrderOutcomeReasonService reasons;
    private OrderInventoryProcess inventoryProcess;
    /** One factory, so a test can substitute the store and change nothing else. */
    private java.util.function.Function<JdbcOrderStore, OrderStateService> orderStateWith;

    private InventoryService inventory;
    private JdbcOrderStore orderStore;
    private JdbcOrderAmendmentStore amendmentStore;
    private JdbcCartStore cartStore;

    private UUID burgerVariant;
    private UUID catalogId;
    private UUID storefrontChannel;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for amendment and outcome tests");
        db = TestDatabase.migrated();
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
                TRUNCATE TABLE ordering.order_amendment_commands, ordering.order_amendments,
                    ordering.order_outcomes, ordering.order_outcome_reason_texts,
                    ordering.order_outcome_reasons, ordering.order_revisions,
                    ordering.order_process_states, ordering.order_timers,
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
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.outbox_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();

        clock = new MutableClock(NOW);
        published = new RecordingEventPublisher();
        ObjectMapper objectMapper = JsonMapper.builder().build();

        var pricingStore = new JdbcPricingStore(jdbc, objectMapper);
        var inventoryStore = new JdbcInventoryStore(jdbc);
        var channelStore = new JdbcSalesChannelStore(jdbc);
        var serviceabilityStore = new JdbcServiceabilityStore(jdbc);

        inventory = new InventoryService(inventoryStore, event -> {}, clock);
        var deliveryFees = new uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver(
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore(
                        jdbc, objectMapper),
                (origin, destination, installationId) -> java.util.Optional.empty(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        var quotes = new QuoteService(
                pricingStore,
                new PricingEngine(),
                new JdbcCatalogPricingContext(jdbc, "uz"),
                channelStore,
                deliveryFees,
                clock);
        var serviceability = new ServiceabilityService(serviceabilityStore, clock);

        cartStore = new JdbcCartStore(jdbc);
        orderStore = new JdbcOrderStore(jdbc);
        amendmentStore = new JdbcOrderAmendmentStore(jdbc);
        var reasonStore = new JdbcOutcomeReasonStore(jdbc);
        var attemptStore = new JdbcCheckoutAttemptStore(jdbc);
        var processStore = new JdbcOrderProcessStore(jdbc);

        FieldProtection protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-kek")::get, clock),
                "local"));

        var tenantContext = new JdbcOrderingTenantContext(jdbc);
        var catalogSnapshot = new JdbcOrderCatalogSnapshot(jdbc, "uz");
        var policies = new OrderAcceptancePolicyService(
                new JdbcPolicyResolver(jdbc, objectMapper),
                new uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcPolicyAuthor(
                        jdbc, objectMapper, new JdbcAuditRecorder(jdbc, objectMapper), clock));
        var auditRecorder = new JdbcAuditRecorder(jdbc, objectMapper);

        // ADR 0046's real planner. No checkout in this suite names a payment
        // method, so none of them plans a settlement — which is precisely why the
        // real class belongs here rather than a stub: if that ever changes, these
        // tests meet the production behaviour rather than a stand-in's opinion of
        // it. The redemption port throws because nothing here may redeem; a call
        // would be a change nobody meant to make.
        var settlementStore = new uz.horecaos.platform.payments.settlement.JdbcSettlementStore(jdbc);
        settlementPlanner = new uz.horecaos.platform.payments.settlement.CheckoutSettlementPlanner(
                settlementStore,
                new uz.horecaos.platform.payments.settlement.OrderSettlementService(
                        settlementStore, NO_REDEMPTION, clock),
                clock);

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
        orderStateWith = store -> new OrderStateService(
                store, serviceability, inventoryProcess, policies, settlementPlanner, auditRecorder, published, clock);
        orderState = orderStateWith.apply(orderStore);
        orderQuery = new OrderQueryService(orderStore, processStore, UNWIRED_PAYMENTS, protection, objectMapper);
        reasons = new OrderOutcomeReasonService(reasonStore, clock);
        outcomes = new OrderOutcomeService(orderState, reasons, orderStore, protection, objectMapper);
        // The real adapter, against the same database: the export guard is only
        // meaningful if it reads the table the export actually writes, and a stub
        // here would reproduce exactly the failure this port was built to end.
        amendments = new OrderAmendmentService(
                orderStore, amendmentStore, auditRecorder, objectMapper, clock, new JdbcPosExportStatus(jdbc));

        var migrationOwnership = new MigrationOwnershipService(
                new JdbcMigrationScopeStore(jdbc, objectMapper), new SimpleMeterRegistry());

        // CheckoutService is now an orchestrator over package-private
        // collaborators; CheckoutServiceTestFactory (same package as the
        // collaborators, so it may construct them) assembles them from exactly
        // the ports this suite already wires by hand, in the same order the
        // constructor used to take them.
        checkout = uz.horecaos.platform.ordering.application.CheckoutServiceTestFactory.create(
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
                UNWIRED_PAYMENTS,
                settlementPlanner,
                protection,
                objectMapper,
                published,
                clock);

        seedTenancyAndCatalog();
        seedPricingAndStock();
    }

    // -------------------------------------------------------------- revisions

    @Test
    @DisplayName("checkout writes revision 1, and it is the only CHECKOUT revision an order can have")
    void checkoutWritesRevisionOne() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));

        var revisions = orderQuery.revisions(TENANT, orderId);
        assertThat(revisions).hasSize(1);
        assertThat(revisions.getFirst().revision()).isEqualTo(1);
        assertThat(revisions.getFirst().source()).isEqualTo("CHECKOUT");
        assertThat(revisions.getFirst().deltaTotalMinor()).isZero();
        assertThat(revisions.getFirst().totalMinor())
                .as("a revision carries the total it was agreed at, copied rather than derived")
                .isEqualTo(orderStore.find(TENANT, orderId).orElseThrow().totalMinor());

        // The equivalence is stated in both directions in the schema, so a second
        // CHECKOUT revision is refused even by hand. Without it, a maintainer
        // writing a "re-snapshot" job would silently give an order two origins.
        Throwable second = catchThrowable(() -> jdbc.sql("""
                INSERT INTO ordering.order_revisions (
                    order_id, revision, tenant_id, source, pricing_quote_id, pricing_context_hash,
                    currency, subtotal_minor, tax_minor, discount_minor, fee_minor, total_minor)
                VALUES (:orderId, 2, :tenantId, 'CHECKOUT', :quoteId, 'hash', 'UZS',
                    100, 0, 0, 0, 100)
                """)
                .param("orderId", orderId)
                .param("tenantId", TENANT)
                .param("quoteId", orderStore.find(TENANT, orderId).orElseThrow().pricingQuoteId())
                .update());

        assertThat(second).isNotNull();
        assertThat(second.getMessage()).contains("ck_order_revision_first");
    }

    @Test
    @DisplayName("applying an amendment appends a revision and leaves the previous one untouched")
    void anAmendmentAppendsRatherThanEdits() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));
        var before = orderQuery.revisions(TENANT, orderId).getFirst();

        var result = amend("k-1", OrderAmendmentService.AmendmentCommand.kitchenNote("Без лука"));

        assertThat(result.amendment().status()).isEqualTo(AmendmentStatus.APPLIED);
        assertThat(result.amendment().appliedRevision()).isEqualTo(2);

        var revisions = orderQuery.revisions(TENANT, orderId);
        assertThat(revisions).hasSize(2);
        // The whole ADR 0019 promise, asserted rather than assumed: an amendment
        // is a new fact and not a mutation, so a report pinned to revision 1 still
        // reconciles to the original total.
        assertThat(revisions.getFirst())
                .as("revision N-1 is byte-identical after an amendment")
                .isEqualTo(before);
        assertThat(revisions.get(1).source()).isEqualTo("AMENDMENT");
        assertThat(revisions.get(1).amendmentId()).isEqualTo(result.amendment().id());
        assertThat(revisions.get(1).totalMinor()).isEqualTo(before.totalMinor());

        var order = orderStore.find(TENANT, orderId).orElseThrow();
        assertThat(order.currentRevision()).isEqualTo(2);
        assertThat(order.kitchenNote()).isEqualTo("Без лука");
    }

    /**
     * The claim {@code OrderAmendmentService} makes in a comment, asserted.
     *
     * <p>ADR 0046 plans a settlement at checkout and nothing re-plans it. An
     * amendment that raised the order total would therefore leave the settlement
     * short by the increase for ever: the refund ceiling, the courier's cash
     * figure and every reconciliation would be computed from a total the customer
     * no longer owes. The service says that cannot happen because none of the
     * three built commands touches money — and until now that was a sentence in a
     * comment, true of this build and load-bearing for another module.
     *
     * <p>Two assertions and they close two different doors. The first is about
     * today: an applied amendment leaves {@code ordering.orders.total_minor}
     * exactly where it was. The second is about tomorrow: the set of commands the
     * application can carry out contains nothing ADR 0039 calls financial, so the
     * day somebody builds {@code ADD_LINES} this test goes red and the settlement
     * question is asked before the money is wrong rather than after.
     */
    @Test
    @DisplayName("an amendment cannot move the order total, so a settlement planned before it "
            + "still agrees with the order")
    void anAmendmentLeavesTheSettledTotalAlone() {
        UUID orderId = orderIdOf(placeOrder("idem-total"));
        long before = orderStore.find(TENANT, orderId).orElseThrow().totalMinor();

        amend("k-total-1", OrderAmendmentService.AmendmentCommand.kitchenNote("Острее"));

        assertThat(orderStore.find(TENANT, orderId).orElseThrow().totalMinor())
                .as("nothing a built command does reaches total_minor, subtotal_minor, "
                        + "fee_minor or tax_minor, and applyRevision has no column for them")
                .isEqualTo(before);

        assertThat(java.util.Arrays.stream(AmendmentCommandType.values())
                        .filter(AmendmentCommandType::built)
                        .filter(AmendmentCommandType::financial)
                        .toList())
                .as("the moment a financial command becomes buildable, a settlement planned at "
                        + "checkout stops being the whole of what the customer owes, and "
                        + "OrderSettlementPort needs a re-plan before that ships")
                .isEmpty();
    }

    @Test
    @DisplayName("a second amendment loses the compare-and-set rather than producing a second revision")
    void twoAmendmentsOnOneOrderSettleAtOne() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        tx(() -> amendments.propose(
                TENANT, orderId, propose("k-1", version, OrderAmendmentService.AmendmentCommand.callback(true))));

        // The second operator read the same version and is now working from a
        // basket that has moved. Being told so is the point; applying underneath
        // would give one order two revisions for one change.
        assertThatThrownBy(() -> tx(() -> amendments.propose(
                        TENANT,
                        orderId,
                        propose("k-2", version, OrderAmendmentService.AmendmentCommand.callback(false)))))
                .isInstanceOf(OrderStateService.StaleOrderException.class);

        assertThat(orderQuery.revisions(TENANT, orderId)).hasSize(2);
    }

    /**
     * The If-Match on a kitchen action is a read followed by an UPDATE keyed on
     * the status, and the two are not one statement. Two operators pressing ready
     * settle correctly — the loser's UPDATE finds the status already moved — so
     * the interleaving that matters is the one that moves the version and leaves
     * the status alone, which is exactly what an amendment does.
     *
     * <p>The store below commits that bump in the window the real amendment would
     * land in: after {@code advance} has read version N and before its settling
     * UPDATE runs. Without the check, the UPDATE still sees the status it expected,
     * wins, and marks ready an order whose contents the operator has never seen.
     */
    @Test
    @DisplayName("a version that moved between the read and the write loses, even at the same status")
    void anActionCannotSettleOnAVersionThatMovedUnderneathIt() {
        UUID orderId = orderIdOf(placeOrder("idem-race"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        OrderStateService racing = orderStateWith.apply(new AmendedMidFlightOrderStore(jdbc));

        assertThatThrownBy(() -> tx(() -> racing.advance(
                        TENANT, orderId, OrderStatus.PREPARING, version, "KITCHEN", "USER", "sharif", null)))
                .isInstanceOf(OrderStateService.StaleOrderException.class);

        var order = orderStore.find(TENANT, orderId).orElseThrow();
        assertThat(order.status())
                .as("the losing transition is rolled back with the transaction, not half-applied")
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orderQuery.timeline(TENANT, orderId).stream().anyMatch(row -> "PREPARING".equals(row.toStatus())))
                .as("and leaves no history saying it happened")
                .isFalse();
    }

    /** A store whose settling UPDATE is preceded by somebody else's version bump. */
    private static final class AmendedMidFlightOrderStore extends JdbcOrderStore {

        private final JdbcClient jdbc;

        private AmendedMidFlightOrderStore(JdbcClient jdbc) {
            super(jdbc);
            this.jdbc = jdbc;
        }

        @Override
        public Optional<Integer> transition(
                UUID tenantId, UUID orderId, OrderStatus from, OrderStatus to, java.time.Instant now) {
            // Stands in for the amendment that committed while this caller was
            // deciding. Only the version moves: the status is the one the caller
            // read, which is what makes the status predicate no defence.
            jdbc.sql("UPDATE ordering.orders SET version = version + 1 WHERE id = :id")
                    .param("id", orderId)
                    .update();
            return super.transition(tenantId, orderId, from, to, now);
        }
    }

    @Test
    @DisplayName("one open amendment per order: the second operator is told who has it")
    void onlyOneAmendmentIsOpenAtATime() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        // Proposed without applying, so it stays open and holds the order.
        tx(() -> amendments.propose(
                TENANT,
                orderId,
                new OrderAmendmentService.ProposeCommand(
                        version,
                        List.of(OrderAmendmentService.AmendmentCommand.callback(true)),
                        false,
                        "k-1",
                        "OPERATOR_EDIT",
                        "USER",
                        "sharif",
                        null)));

        assertThatThrownBy(() -> tx(() -> amendments.propose(
                        TENANT,
                        orderId,
                        new OrderAmendmentService.ProposeCommand(
                                version,
                                List.of(OrderAmendmentService.AmendmentCommand.callback(false)),
                                false,
                                "k-2",
                                "OPERATOR_EDIT",
                                "USER",
                                "dilnoza",
                                null))))
                .isInstanceOf(OrderAmendmentService.AmendmentInProgressException.class)
                .hasMessageContaining("sharif");
    }

    @Test
    @DisplayName("an amendment past the quote TTL applies nothing")
    void anExpiredAmendmentAppliesNothing() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        var proposed = tx(() -> amendments.propose(
                TENANT,
                orderId,
                new OrderAmendmentService.ProposeCommand(
                        version,
                        List.of(OrderAmendmentService.AmendmentCommand.cashTendered(200_000L)),
                        false,
                        "k-1",
                        "OPERATOR_EDIT",
                        "USER",
                        "sharif",
                        null)));

        clock.advance(OrderAmendmentService.TTL.plus(Duration.ofMinutes(1)));

        assertThatThrownBy(() -> tx(() -> amendments.apply(
                        TENANT, orderId, proposed.amendment().id(), version, "USER", "sharif", "OPERATOR_EDIT", null)))
                .isInstanceOf(OrderAmendmentService.AmendmentExpiredException.class);

        assertThat(orderQuery.revisions(TENANT, orderId))
                .as("an expired amendment leaves the order exactly where it was")
                .hasSize(1);
        assertThat(orderStore.find(TENANT, orderId).orElseThrow().cashTenderedExpectedMinor())
                .isNull();
    }

    @Test
    @DisplayName("the expiry sweep settles amendments nobody finished")
    void theSweepExpiresOverdueAmendments() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        tx(() -> amendments.propose(
                TENANT,
                orderId,
                new OrderAmendmentService.ProposeCommand(
                        version,
                        List.of(OrderAmendmentService.AmendmentCommand.callback(true)),
                        false,
                        "k-1",
                        "OPERATOR_EDIT",
                        "USER",
                        "sharif",
                        null)));

        clock.advance(OrderAmendmentService.TTL.plus(Duration.ofMinutes(1)));
        assertThat(tx(() -> amendments.expireOverdue(50))).isEqualTo(1);

        assertThat(amendmentStore.findOpen(TENANT, orderId))
                .as("an expired amendment releases the order for the next operator")
                .isEmpty();
    }

    // --------------------------------------------------------------- commands

    @Test
    @DisplayName("an unbuilt command is refused by name and writes nothing")
    void anUnbuiltCommandIsRefused() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        assertThatThrownBy(() -> tx(() -> amendments.propose(
                        TENANT,
                        orderId,
                        new OrderAmendmentService.ProposeCommand(
                                version,
                                List.of(new OrderAmendmentService.AmendmentCommand(
                                        AmendmentCommandType.ADD_LINES, Map.of())),
                                true,
                                "k-1",
                                "OPERATOR_EDIT",
                                "USER",
                                "sharif",
                                null))))
                .isInstanceOf(OrderAmendmentService.AmendmentNotPermittedException.class)
                .hasMessageContaining("ADD_LINES");

        assertThat(amendments.forOrder(TENANT, orderId))
                .as("a refused command leaves no half-built amendment behind")
                .isEmpty();
        assertThat(orderQuery.revisions(TENANT, orderId)).hasSize(1);
    }

    @Test
    @DisplayName("change-due short of the total warns the operator rather than refusing the order")
    void changeDueShortOfTheTotalIsAWarning() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));
        long total = orderStore.find(TENANT, orderId).orElseThrow().totalMinor();

        var result = amend("k-1", OrderAmendmentService.AmendmentCommand.cashTendered(total - 1_000));

        // ADR 0039: the customer can hand over more. Refusing here would stop an
        // order over a figure that is a hint and not money.
        assertThat(result.warnings()).containsExactly("CASH_TENDERED_INSUFFICIENT");
        assertThat(result.amendment().status()).isEqualTo(AmendmentStatus.APPLIED);
        assertThat(orderStore.find(TENANT, orderId).orElseThrow().cashTenderedExpectedMinor())
                .isEqualTo(total - 1_000);
    }

    @Test
    @DisplayName("the callback flag is raised and resolved by the same command, recording who cleared it")
    void theCallbackFlagRecordsWhoResolvedIt() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));

        amend("k-1", OrderAmendmentService.AmendmentCommand.callback(true));
        assertThat(orderStore.find(TENANT, orderId).orElseThrow().callbackRequested())
                .isTrue();

        amend("k-2", OrderAmendmentService.AmendmentCommand.callback(false));

        var order = orderStore.find(TENANT, orderId).orElseThrow();
        assertThat(order.callbackRequested()).isFalse();
        assertThat(order.callbackResolvedBy()).isEqualTo("sharif");
        assertThat(order.callbackResolvedAt()).isNotNull();
        assertThat(order.currentRevision())
                .as("every applied amendment is a revision, including the ones that move no money")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a retried proposal replays rather than amending twice")
    void aRetriedProposalIsHarmless() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        tx(() -> amendments.propose(
                TENANT,
                orderId,
                propose("k-1", version, OrderAmendmentService.AmendmentCommand.kitchenNote("Позвонить на входе"))));
        var replay = tx(() -> amendments.propose(
                TENANT,
                orderId,
                propose("k-1", version, OrderAmendmentService.AmendmentCommand.kitchenNote("Позвонить на входе"))));

        assertThat(replay.replayed()).isTrue();
        assertThat(orderQuery.revisions(TENANT, orderId)).hasSize(2);
    }

    @Test
    @DisplayName("an amendment never applies underneath an unacknowledged POS export")
    void aSentPosExportBlocksAmendment() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        // Written into integration.pos_order_exports, which is where the export
        // actually keeps its state. This test used to insert a POS_ORDER_EXPORT
        // row into ordering.order_process_states instead -- a table nothing has
        // ever written for this process -- so it proved the guard's logic while
        // the guard's wiring was dead, and every amendment in the platform's
        // history passed it. The failure being prevented is a kitchen holding a
        // ticket and cooking it while the order changes underneath.
        exportRow(orderId, "SENT");

        assertThatThrownBy(() -> tx(() -> amendments.propose(
                        TENANT,
                        orderId,
                        propose("k-1", version, OrderAmendmentService.AmendmentCommand.callback(true)))))
                .isInstanceOf(OrderAmendmentService.PosExportUnacknowledgedException.class);

        assertThat(orderQuery.revisions(TENANT, orderId)).hasSize(1);
    }

    @Test
    @DisplayName("an uncertain POS export blocks an amendment too")
    void anUncertainPosExportBlocksAmendment() {
        UUID orderId = orderIdOf(placeOrder("idem-unc"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        // The case most worth guarding. An export that may or may not have landed
        // is exactly when amending could change an order already on a kitchen
        // screen, and "we do not know" must not read as "it is safe".
        exportRow(orderId, "UNCERTAIN");

        assertThatThrownBy(() -> tx(() -> amendments.propose(
                        TENANT,
                        orderId,
                        propose("k-unc", version, OrderAmendmentService.AmendmentCommand.callback(true)))))
                .isInstanceOf(OrderAmendmentService.PosExportUnacknowledgedException.class);
    }

    @Test
    @DisplayName("an order no till ever received is still amendable")
    void anOrderWithNoPosExportIsAmendable() {
        UUID orderId = orderIdOf(placeOrder("idem-none"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        // A location with no till exports nothing, and that is the ordinary case
        // rather than an edge. Failing closed here would trade a guard nobody
        // needed for an amendment path nobody could use.
        tx(() -> amendments.propose(
                TENANT, orderId, propose("k-none", version, OrderAmendmentService.AmendmentCommand.callback(true))));

        assertThat(orderQuery.revisions(TENANT, orderId)).hasSize(2);
    }

    /**
     * A POS export row in the state the till would have put it in.
     *
     * <p>The installation and binding are inserted for real rather than
     * fabricated, because pos_order_exports has composite foreign keys to both —
     * which is the schema saying an export belongs to a till somebody configured.
     */
    private void exportRow(UUID orderId, String state) {
        UUID installationId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'POS', 'clopos', 'clopos-open-api-v2', 'Till',
                    'ACTIVE', 'horecaos:local:pos:till:token')
                """).param("id", installationId).param("tenantId", TENANT).update();

        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", bindingId)
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", BRAND)
                .update();

        jdbc.sql("""
                INSERT INTO integration.pos_order_exports (
                    id, tenant_id, order_id, binding_id, installation_id, state,
                    line_fingerprint, customer_phone_hash, external_venue_reference,
                    requested_at)
                VALUES (:id, :tenantId, :orderId, :bindingId, :installationId, :state,
                    'fingerprint', 'hash', 'venue-1', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("orderId", orderId)
                .param("bindingId", bindingId)
                .param("installationId", installationId)
                .param("state", state)
                .update();
    }

    @Test
    @DisplayName("the database refuses an applied increase the customer never agreed to")
    void anUnconfirmedIncreaseCannotBeStored() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));

        // Written by hand because no financial command exists to produce a
        // positive delta yet. The constraint is what has to hold when one does:
        // charging more than the customer agreed to is the failure the attestation
        // exists to prevent, and a rule that lives only in a service is a rule the
        // next call path walks around.
        Throwable refused = catchThrowable(() -> jdbc.sql("""
                INSERT INTO ordering.order_amendments (
                    id, tenant_id, order_id, status, base_revision, applied_revision,
                    delta_total_minor, idempotency_key, expires_at, created_by_actor_type,
                    settled_at)
                VALUES (:id, :tenantId, :orderId, 'APPLIED', 1, 2, 18000, 'by-hand',
                    now() + interval '15 minutes', 'USER', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("orderId", orderId)
                .update());

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("ck_amendment_increase_confirmed");
    }

    // ------------------------------------------------------- terminal outcomes

    @Test
    @DisplayName("a rejection, an expiry and a cancellation are three different recorded facts")
    void theThreeRefusalsAreDifferentFacts() {
        requireApproval();

        UUID rejected = orderIdOf(placeOrder("idem-1"));
        tx(() -> orderState.decide(TENANT, rejected, decision("d-1", OrderStateService.DecisionAction.REJECT)));

        UUID expired = orderIdOf(placeOrder("idem-2"));
        clock.advance(Duration.ofMinutes(6));
        tx(() -> orderState.approvalDeadlineReached(TENANT, expired));

        UUID cancelled = orderIdOf(placeOrder("idem-3"));
        int version = orderStore.find(TENANT, cancelled).orElseThrow().version();
        tx(() -> orderState.cancel(TENANT, cancelled, version, "CUSTOMER_CALLED", "USER", "sharif", null));

        assertThat(orderQuery.outcome(TENANT, rejected).orElseThrow())
                .extracting("kind", "systemCategory")
                .containsExactly("REJECTED", "RESTAURANT_REFUSED");
        assertThat(orderQuery.outcome(TENANT, expired).orElseThrow())
                .extracting("kind", "systemCategory")
                .containsExactly("EXPIRED", "APPROVAL_DEADLINE_LAPSED");
        assertThat(orderQuery.outcome(TENANT, cancelled).orElseThrow())
                .extracting("kind", "systemCategory")
                .containsExactly("CANCELLED", "OTHER");
    }

    @Test
    @DisplayName("a completed order records how it was completed, not merely that it was")
    void completionRecordsHow() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));
        advance(orderId, OrderStatus.PREPARING);
        advance(orderId, OrderStatus.READY);

        int version = orderStore.find(TENANT, orderId).orElseThrow().version();
        tx(() -> outcomes.complete(TENANT, orderId, version, null, "USER", "sharif", null));

        var outcome = orderQuery.outcome(TENANT, orderId).orElseThrow();
        assertThat(outcome.kind()).isEqualTo("COMPLETED");
        // A pickup order that ends COMPLETED with nothing else recorded cannot
        // tell a manager whether a courier was owed for it.
        assertThat(outcome.systemCategory()).isEqualTo("COLLECTED_BY_CUSTOMER");
        assertThat(outcome.stockDisposition()).isEqualTo("NO_EFFECT");
        assertThat(outcome.liabilityParty()).isNull();
    }

    @Test
    @DisplayName("a completion reason is refused on a fulfilment mode it is not valid for")
    void aCompletionReasonIsValidatedAgainstTheMode() {
        UUID reasonId = tx(() -> reasons.create(
                TENANT,
                new OrderOutcomeReasonService.CreateReason(
                        OutcomeReasonKind.COMPLETION,
                        OutcomeSystemCategory.DELIVERED_PARTNER_COURIER,
                        "Доставлен сторонней службой",
                        null,
                        null,
                        null,
                        List.of(FulfillmentMode.DELIVERY),
                        texts("Доставлено"))));

        UUID orderId = orderIdOf(placeOrder("idem-1"));
        advance(orderId, OrderStatus.PREPARING);
        advance(orderId, OrderStatus.READY);
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        assertThatThrownBy(
                        () -> tx(() -> outcomes.complete(TENANT, orderId, version, reasonId, "USER", "sharif", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PICKUP");

        assertThat(orderStore.find(TENANT, orderId).orElseThrow().status()).isEqualTo(OrderStatus.READY);
    }

    @Test
    @DisplayName("cancelling before commitment releases, whatever the reason's disposition says")
    void cancellationBeforeCommitmentAlwaysReleases() {
        requireApproval();
        UUID writeOff = writeOffReason();

        UUID orderId = orderIdOf(placeOrder("idem-1"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        tx(() -> outcomes.cancel(
                TENANT,
                orderId,
                version,
                new OrderOutcomeService.CancelCommand(writeOff, "клиент передумал", "USER", "sharif", null)));

        var outcome = orderQuery.outcome(TENANT, orderId).orElseThrow();
        assertThat(outcome.reservationCommitted()).isFalse();
        // ADR 0017's rule, closed by ADR 0039: nothing was cooked, so nothing is
        // written off however the reason is configured.
        assertThat(outcome.stockDisposition()).isEqualTo(StockDisposition.RELEASE.name());
        assertThat(outcome.reasonId()).isEqualTo(writeOff);
        assertThat(outcome.reasonVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("cancelling after commitment records the reason's disposition, and never both")
    void cancellationAfterCommitmentRecordsTheDisposition() {
        UUID writeOff = writeOffReason();

        UUID orderId = orderIdOf(placeOrder("idem-1"));
        assertThat(orderStore.find(TENANT, orderId).orElseThrow().status())
                .as("this branch auto-confirms, so the hold has been committed")
                .isEqualTo(OrderStatus.CONFIRMED);
        inventoryProcess.runOnce(10);
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        long movementsBefore = movementCount();
        tx(() -> outcomes.cancel(
                TENANT,
                orderId,
                version,
                new OrderOutcomeService.CancelCommand(writeOff, null, "USER", "sharif", null)));

        var outcome = orderQuery.outcome(TENANT, orderId).orElseThrow();
        assertThat(outcome.reservationCommitted()).isTrue();
        assertThat(outcome.stockDisposition()).isEqualTo(StockDisposition.WRITE_OFF.name());
        assertThat(outcome.liabilityParty()).isEqualTo(LiabilityParty.TENANT.name());
        assertThat(outcome.customerRefund()).isEqualTo(CustomerRefund.FULL.name());
        // The movement itself is not written: the ADR 0017 port ordering holds is
        // hold, commit and release, and a return or waste movement needs a fourth
        // verb inventory owns. The outcome is the record until it exists, which is
        // why inventory_movement_id is null rather than the movement being faked.
        assertThat(outcome.inventoryMovementId()).isNull();
        assertThat(movementCount())
                .as("a cancellation never reopens a committed reservation")
                .isEqualTo(movementsBefore);
    }

    @Test
    @DisplayName("cancelling a confirmed order needs a reason; without one it is still refused")
    void confirmedCancellationWithoutAReasonIsRefused() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        assertThatThrownBy(
                        () -> tx(() -> orderState.cancel(TENANT, orderId, version, "BECAUSE", "USER", "sharif", null)))
                .isInstanceOf(OrderStateService.CancellationNotPermittedException.class);

        assertThat(orderQuery.outcome(TENANT, orderId)).isEmpty();
    }

    @Test
    @DisplayName("the cancellation event carries the category, disposition and liable party")
    void theCancellationEventCarriesTheOutcome() {
        UUID writeOff = writeOffReason();
        UUID orderId = orderIdOf(placeOrder("idem-1"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        tx(() -> outcomes.cancel(
                TENANT,
                orderId,
                version,
                new OrderOutcomeService.CancelCommand(writeOff, null, "USER", "sharif", null)));

        OrderCancelled event = published.events.stream()
                .filter(OrderCancelled.class::isInstance)
                .map(OrderCancelled.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();

        assertThat(event.systemCategory()).isEqualTo("ITEM_UNAVAILABLE");
        assertThat(event.stockDisposition()).isEqualTo("WRITE_OFF");
        assertThat(event.liabilityParty()).isEqualTo("TENANT");
        // ADR 0029 and ADR 0032: the tenant's internal wording is a statement
        // about a customer and stays behind an authorized API.
        assertThat(event.payload().toString()).doesNotContain("Нет товара");
    }

    @Test
    @DisplayName("only one outcome can ever be recorded for one order")
    void anOrderHasExactlyOneOutcome() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();
        tx(() -> outcomes.cancel(
                TENANT,
                orderId,
                version,
                new OrderOutcomeService.CancelCommand(writeOffReason(), null, "USER", "sharif", null)));

        Throwable second = catchThrowable(() -> jdbc.sql("""
                INSERT INTO ordering.order_outcomes (order_id, tenant_id, kind, system_category,
                    actor_type, stock_disposition, reservation_committed, occurred_at)
                VALUES (:orderId, :tenantId, 'COMPLETED', 'COLLECTED_BY_CUSTOMER', 'USER',
                    'NO_EFFECT', true, now())
                """)
                .param("orderId", orderId)
                .param("tenantId", TENANT)
                .update());

        assertThat(second).isNotNull();
    }

    // --------------------------------------------------------- reason registry

    @Test
    @DisplayName("a reason without wording in every locale is refused")
    void aReasonNeedsEveryLocale() {
        assertThatThrownBy(() -> tx(() -> reasons.create(
                        TENANT,
                        new OrderOutcomeReasonService.CreateReason(
                                OutcomeReasonKind.CANCELLATION,
                                OutcomeSystemCategory.CUSTOMER_UNREACHABLE,
                                "Не дозвонились",
                                StockDisposition.RETURN_TO_STOCK,
                                LiabilityParty.CUSTOMER,
                                CustomerRefund.FULL,
                                null,
                                Map.of("ru", "Мы не смогли до вас дозвониться")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uz-Latn");
    }

    @Test
    @DisplayName("a cancellation reason cannot omit the three consequences it exists to carry")
    void aCancellationReasonCarriesItsConsequences() {
        assertThatThrownBy(() -> tx(() -> reasons.create(
                        TENANT,
                        new OrderOutcomeReasonService.CreateReason(
                                OutcomeReasonKind.CANCELLATION,
                                OutcomeSystemCategory.CUSTOMER_UNREACHABLE,
                                "Не дозвонились",
                                null,
                                null,
                                null,
                                null,
                                texts("Не дозвонились")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safe default");
    }

    @Test
    @DisplayName("the customer text and the internal name are different statements, in different rows")
    void theTwoTextsAreSeparate() {
        UUID reasonId = tx(() -> reasons.create(
                TENANT,
                new OrderOutcomeReasonService.CreateReason(
                        OutcomeReasonKind.CANCELLATION,
                        OutcomeSystemCategory.CUSTOMER_UNREACHABLE,
                        "Не дозвонились",
                        StockDisposition.RETURN_TO_STOCK,
                        LiabilityParty.CUSTOMER,
                        CustomerRefund.FULL,
                        null,
                        texts("К сожалению, мы не смогли связаться с вами"))));

        assertThat(reasons.texts(reasonId)).containsKeys("ru", "uz-Latn", "en").doesNotContainValue("Не дозвонились");
    }

    @Test
    @DisplayName("an archived reason cannot be cited by a new cancellation")
    void anArchivedReasonIsRefused() {
        UUID reasonId = writeOffReason();
        tx(() -> reasons.archive(TENANT, reasonId, 1));

        UUID orderId = orderIdOf(placeOrder("idem-1"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();

        assertThatThrownBy(() -> tx(() -> outcomes.cancel(
                        TENANT,
                        orderId,
                        version,
                        new OrderOutcomeService.CancelCommand(reasonId, null, "USER", "sharif", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archived");
    }

    @Test
    @DisplayName("renaming a reason does not rewrite an outcome already recorded under it")
    void theOutcomeSnapshotSurvivesARename() {
        UUID reasonId = writeOffReason();
        UUID orderId = orderIdOf(placeOrder("idem-1"));
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();
        tx(() -> outcomes.cancel(
                TENANT,
                orderId,
                version,
                new OrderOutcomeService.CancelCommand(reasonId, null, "USER", "sharif", null)));

        tx(() -> reasons.update(
                TENANT,
                reasonId,
                1,
                new OrderOutcomeReasonService.CreateReason(
                        OutcomeReasonKind.CANCELLATION,
                        OutcomeSystemCategory.ITEM_UNAVAILABLE,
                        "Продукт закончился",
                        StockDisposition.RETURN_TO_STOCK,
                        LiabilityParty.PLATFORM,
                        CustomerRefund.NONE,
                        null,
                        texts("Извините"))));

        var outcome = orderQuery.outcome(TENANT, orderId).orElseThrow();
        assertThat(outcome.reasonVersion()).isEqualTo(1);
        assertThat(outcome.stockDisposition()).isEqualTo("WRITE_OFF");
        assertThat(outcome.liabilityParty()).isEqualTo("TENANT");
        assertThat(outcome.reasonSnapshot())
                .as("last year's funnel is not rewritten by this year's rename")
                .contains("Нет товара");
    }

    // ----------------------------------------------------------- attribution

    @Test
    @DisplayName("attribution records who entered the order and who accepted it, separately")
    void attributionSeparatesEnteringFromAccepting() {
        requireApproval();
        UUID orderId = orderIdOf(placeOrder("idem-1"));

        var received = orderStore.find(TENANT, orderId).orElseThrow();
        assertThat(received.createdByActorType()).isEqualTo("CUSTOMER");
        assertThat(received.acceptedAt())
                .as("nobody has accepted an order that is still awaiting approval")
                .isNull();

        tx(() -> orderState.decide(TENANT, orderId, decision("d-1", OrderStateService.DecisionAction.APPROVE)));

        var confirmed = orderStore.find(TENANT, orderId).orElseThrow();
        assertThat(confirmed.createdByActorId()).isEqualTo(CUSTOMER.toString());
        assertThat(confirmed.acceptedByActorId()).isEqualTo("operator");
        assertThat(confirmed.acceptedAt()).isNotNull();
    }

    @Test
    @DisplayName("the database refuses to rewrite who took an order")
    void attributionCannotBeRewritten() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));

        // A leaderboard a later action can rewrite measures nothing, so the rule
        // is enforced where a support "fix" cannot bypass it.
        Throwable rewritten =
                catchThrowable(() -> jdbc.sql("""
                UPDATE ordering.orders SET created_by_actor_id = 'someone-else' WHERE id = :id
                """).param("id", orderId).update());

        assertThat(rewritten).isNotNull();
        assertThat(rewritten.getMessage()).contains("written once");
    }

    @Test
    @DisplayName("an auto-confirmed order names the rule that accepted it rather than nobody")
    void anAutoConfirmedOrderNamesTheSystem() {
        UUID orderId = orderIdOf(placeOrder("idem-1"));

        var order = orderStore.find(TENANT, orderId).orElseThrow();
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.acceptedByActorType()).isEqualTo("SYSTEM_JOB");
        assertThat(order.acceptedByActorId()).isEqualTo("order-acceptance-policy");
    }

    // ------------------------------------------------------------- helpers

    private Map<String, String> texts(String text) {
        return Map.of("ru", text, "uz-Latn", text, "en", text);
    }

    /** «Нет товара»: committed stock is written off and the restaurant carries it. */
    private UUID writeOffReason() {
        return tx(() -> reasons.create(
                TENANT,
                new OrderOutcomeReasonService.CreateReason(
                        OutcomeReasonKind.CANCELLATION,
                        OutcomeSystemCategory.ITEM_UNAVAILABLE,
                        "Нет товара",
                        StockDisposition.WRITE_OFF,
                        LiabilityParty.TENANT,
                        CustomerRefund.FULL,
                        null,
                        texts("Извините, блюдо закончилось"))));
    }

    private OrderAmendmentService.ProposeCommand propose(
            String key, int version, OrderAmendmentService.AmendmentCommand command) {
        return new OrderAmendmentService.ProposeCommand(
                version, List.of(command), true, key, "OPERATOR_EDIT", "USER", "sharif", null);
    }

    private OrderAmendmentService.AmendmentResult amend(String key, OrderAmendmentService.AmendmentCommand command) {
        UUID orderId = jdbc.sql("SELECT id FROM ordering.orders LIMIT 1")
                .query(UUID.class)
                .single();
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();
        return tx(() -> amendments.propose(TENANT, orderId, propose(key, version, command)));
    }

    private UUID openCart() {
        return tx(() -> carts.create(TENANT, BRAND, LOCATION, "STOREFRONT", FulfillmentMode.PICKUP, CUSTOMER, null))
                .cartId();
    }

    /**
     * The order id of a checkout that succeeded. {@code CheckoutResult.orderId}
     * is null for a rejected or unavailable outcome (ADR 0019); every call site
     * here is on the CREATED/REPLAYED path, where the order always exists.
     */
    private static UUID orderIdOf(CheckoutService.CheckoutResult result) {
        return Objects.requireNonNull(result.orderId(), "a created checkout always has an order id");
    }

    private CheckoutService.CheckoutResult placeOrder(String idempotencyKey) {
        UUID cart = openCart();
        tx(() -> carts.putLine(
                TENANT, BRAND, CUSTOMER, cart, cartVersion(cart), "a", burgerVariant, 2, List.of(), null));
        tx(() -> carts.price(TENANT, BRAND, CUSTOMER, cart, cartVersion(cart)));

        var row = cartStore.find(TENANT, BRAND, cart).orElseThrow();
        return tx(() -> checkout.checkout(new CheckoutService.CheckoutCommand(
                TENANT,
                BRAND,
                cart,
                row.version(),
                Objects.requireNonNull(row.pricingQuoteId(), "the fixture cart is always priced first"),
                Objects.requireNonNull(row.pricingContextHash(), "the fixture cart is always priced first"),
                idempotencyKey,
                // Naming a method is a precondition of checkout now: an order that
                // names none plans no settlement and can never be refunded.
                "CASH",
                0L,
                "CUSTOMER",
                CUSTOMER.toString(),
                null)));
    }

    private int cartVersion(UUID cartId) {
        return cartStore.find(TENANT, BRAND, cartId).orElseThrow().version();
    }

    private void advance(UUID orderId, OrderStatus target) {
        int version = orderStore.find(TENANT, orderId).orElseThrow().version();
        tx(() -> orderState.advance(TENANT, orderId, target, version, "KITCHEN", "USER", "sharif", null));
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

    private long movementCount() {
        return jdbc.sql("SELECT count(*) FROM inventory.movements")
                .query(Long.class)
                .single();
    }

    /** Switches the location to RESTAURANT_APPROVAL with a five-minute deadline. */
    private void requireApproval() {
        var policy = new uz.horecaos.platform.ordering.domain.OrderAcceptancePolicy(
                uz.horecaos.platform.ordering.domain.AcceptanceMode.RESTAURANT_APPROVAL,
                uz.horecaos.platform.ordering.domain.ApprovalChannel.HORECAOS_OPERATIONS,
                300,
                uz.horecaos.platform.ordering.domain.ApprovalTimeoutAction.AUTO_REJECT,
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

    private void seedTenancyAndCatalog() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'amendment-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
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

        storefrontChannel = UUID.randomUUID();
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

        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, burgerVariant, TrackingMode.BINARY);
    }

    /**
     * A points ledger nothing in this suite is allowed to touch.
     *
     * <p>Throwing rather than returning a plausible hold: an order here that
     * quietly reserved points would be a redemption no test asked for, and a
     * silent stub is how that goes unnoticed.
     */
    private static final uz.horecaos.platform.loyalty.api.PointsRedemptionPort NO_REDEMPTION =
            new uz.horecaos.platform.loyalty.api.PointsRedemptionPort() {

                @Override
                public RedemptionOffer quote(RedemptionQuery query) {
                    throw new UnsupportedOperationException("No order here redeems points");
                }

                @Override
                public PointsHold reserve(ReserveCommand command) {
                    throw new UnsupportedOperationException("No order here redeems points");
                }

                @Override
                public void settle(UUID tenantId, UUID tenderId) {
                    throw new UnsupportedOperationException("No order here redeems points");
                }

                @Override
                public void release(UUID tenantId, UUID tenderId, String reasonCode, String actor) {
                    throw new UnsupportedOperationException("No order here redeems points");
                }

                @Override
                public void reverse(UUID tenantId, UUID tenderId, long amountMinor, String reasonCode, String actor) {
                    throw new UnsupportedOperationException("No order here redeems points");
                }
            };

    /** The unwired payments port, which is a stand-in in production too. */
    private static final PaymentIntentPort UNWIRED_PAYMENTS = new PaymentIntentPort() {
        @Override
        public @Nullable UUID createIntent(
                UUID tenantId,
                UUID orderId,
                long amountMinor,
                String currency,
                String paymentMethodCode,
                String idempotencyKey) {
            return null;
        }

        @Override
        public boolean paymentRequiredBeforeConfirmation(UUID tenantId, UUID orderId, String paymentMethodCode) {
            return false;
        }

        @Override
        public boolean isWired() {
            return false;
        }
    };

    /** Collects the ordering facts a transaction publishes, in order. */
    private static final class RecordingEventPublisher implements ApplicationEventPublisher {

        private final List<OrderingEvent> events = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public void publishEvent(Object event) {
            if (event instanceof OrderingEvent ordering) {
                events.add(ordering);
            }
        }
    }

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
