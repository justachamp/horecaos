package uz.horecaos.platform.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.partner.api.PartnerPrincipal;
import uz.horecaos.platform.partner.application.HandoverVerificationService;
import uz.horecaos.platform.partner.application.MarketplaceIngestionService;
import uz.horecaos.platform.partner.application.MarketplaceIngestionService.PartnerOrderPush;
import uz.horecaos.platform.partner.application.MarketplaceIngestionService.PushLine;
import uz.horecaos.platform.partner.application.MarketplaceLivenessService;
import uz.horecaos.platform.partner.application.PartnerAuthenticationService;
import uz.horecaos.platform.partner.domain.DiscountFunding;
import uz.horecaos.platform.partner.domain.ExternalReference;
import uz.horecaos.platform.partner.domain.ExternalTotals;
import uz.horecaos.platform.partner.domain.HandoverChallengeStatus;
import uz.horecaos.platform.partner.domain.HandoverCodeHasher;
import uz.horecaos.platform.partner.domain.MarketplaceOrderLifecycle;
import uz.horecaos.platform.partner.domain.RejectionCode;
import uz.horecaos.platform.partner.infrastructure.ordering.JdbcMarketplaceOrderIntake;
import uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * The marketplace channel and the partner API (ADR 0040).
 *
 * <p>Against a real PostgreSQL, because most of what ADR 0040 promises is a
 * property of the database rather than of the Java. Whether an externally priced
 * total can reach a HorecaOS channel is a check constraint; whether two concurrent
 * pushes of one partner order produce one order is a unique index; whether a
 * marketplace order can name a delivery provider's binding is a trigger. None of
 * those can be tested against a mock, and every one of them is what actually
 * goes wrong in a live integration.
 */
class MarketplaceChannelTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();

    /** A Tuesday lunchtime in Tashkent. */
    private static final Instant NOON = Instant.parse("2026-08-25T07:00:00Z");

    /**
     * When the bindings start being effective, stated rather than defaulted.
     *
     * <p>{@code integration.bindings.effective_from} defaults to {@code now()},
     * and {@code PartnerAuthenticationService} asks for bindings effective at the
     * service clock — which is {@link #NOON}, a fixed instant. So a row inserted
     * at real wall-clock time is effective from <em>now</em> and invisible to a
     * query asking about a moment in the past. Every test here passed until the
     * real clock crossed 07:00Z on the day NOON names, and would have failed for
     * good afterwards, with an error that says the partner's credential is
     * invalid rather than that the fixture is.
     */
    private static final java.time.OffsetDateTime EFFECTIVE_FROM =
            NOON.minus(java.time.Duration.ofDays(1)).atOffset(ZoneOffset.UTC);

    private static final String VENUE = "uzum-venue-4471";
    private static final String CLIENT_ID = "partner-uzum-tezkor";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcPartnerStore store;
    private JdbcMarketplaceOrderIntake intake;
    private MarketplaceIngestionService ingestion;
    private HandoverVerificationService handovers;
    private MarketplaceLivenessService liveness;
    private PartnerAuthenticationService authentication;
    private HandoverCodeHasher hasher;
    private RecordingAuditRecorder audit;
    private uz.horecaos.platform.payments.settlement.JdbcSettlementStore settlementStore;
    private uz.horecaos.platform.payments.settlement.OrderSettlementService settlementService;
    private uz.horecaos.platform.payments.settlement.CheckoutSettlementPlanner settlementPlanner;

    /**
     * A marketplace order has no customer account behind it and therefore no
     * balance tender, so no redemption is ever reached. Throwing rather than
     * returning is what makes that an assertion instead of an assumption.
     */
    private static final uz.horecaos.platform.loyalty.api.PointsRedemptionPort NO_REDEMPTION =
            new uz.horecaos.platform.loyalty.api.PointsRedemptionPort() {

                @Override
                public RedemptionOffer quote(RedemptionQuery query) {
                    throw new UnsupportedOperationException("A marketplace order redeems nothing");
                }

                @Override
                public PointsHold reserve(ReserveCommand command) {
                    throw new UnsupportedOperationException("A marketplace order redeems nothing");
                }

                @Override
                public void settle(UUID tenantId, UUID tenderId) {
                    throw new UnsupportedOperationException("A marketplace order redeems nothing");
                }

                @Override
                public void release(UUID tenantId, UUID tenderId, String reasonCode, String actor) {
                    throw new UnsupportedOperationException("A marketplace order redeems nothing");
                }

                @Override
                public void reverse(UUID tenantId, UUID tenderId, long amountMinor, String reasonCode, String actor) {
                    throw new UnsupportedOperationException("A marketplace order redeems nothing");
                }
            };

    private UUID branch;
    private UUID installation;
    private UUID binding;
    private UUID deliveryBinding;
    private UUID channel;
    private UUID mappedVariant;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for marketplace channel tests");
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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("TRUNCATE TABLE partner.inbound_orders, partner.api_clients CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.order_handover_challenges, "
                        + "ordering.order_external_references, ordering.order_external_pricing, "
                        + "ordering.order_lines, ordering.order_revisions, ordering.orders, "
                        + "ordering.order_number_counters CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE payments.tenders, payments.order_settlements, " + "payments.payment_methods CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE integration.provider_activity_watermarks, "
                        + "integration.provider_entity_mappings, integration.bindings, "
                        + "integration.installations, integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(NOON, ZoneOffset.UTC);
        store = new JdbcPartnerStore(jdbc);
        intake = new JdbcMarketplaceOrderIntake(jdbc);
        hasher = new HandoverCodeHasher("a-pepper-long-enough-for-a-test".getBytes(StandardCharsets.UTF_8));
        audit = new RecordingAuditRecorder();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        // ADR 0046's settlement stack, production classes end to end. An
        // aggregator order that plans no settlement is an order no operator can
        // refund, which is exactly the gap this wiring closes; a stub here would
        // hide it again.
        settlementStore = new uz.horecaos.platform.payments.settlement.JdbcSettlementStore(jdbc);
        settlementService = new uz.horecaos.platform.payments.settlement.OrderSettlementService(
                settlementStore, NO_REDEMPTION, clock);
        settlementPlanner = new uz.horecaos.platform.payments.settlement.CheckoutSettlementPlanner(
                settlementStore, settlementService, clock);

        ingestion = new MarketplaceIngestionService(
                store, intake, settlementPlanner, new PassthroughFieldProtection(), hasher, transactions, clock);
        handovers = new HandoverVerificationService(store, hasher, audit, clock);
        liveness = new MarketplaceLivenessService(store, clock);
        authentication = new PartnerAuthenticationService(store, clock);

        seed();
    }

    // ------------------------------------------------------------------ pricing

    @Test
    @DisplayName("an externally priced order whose parts do not sum to its total is refused, "
            + "and the refusal is stored with the payload")
    void aTotalThatDoesNotAddUpIsRefused() {
        // 40,000 + 5,000 fee - 3,000 discount is 42,000, and the partner says
        // 45,000. Somebody's arithmetic is wrong and it is not going to be
        // discovered three months later by an accountant.
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                push(
                        "UZ-1",
                        new ExternalTotals("UZS", 45_000, 40_000, 3_000, 5_000, null),
                        List.of(line("ITEM-1", 40_000))));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.rejectionCode()).isEqualTo(RejectionCode.EXTERNAL_TOTAL_MISMATCH);

        assertThat(jdbc.sql("""
                SELECT outcome, rejection_code, order_id, raw_payload_encrypted IS NOT NULL
                FROM partner.inbound_orders WHERE external_order_id = 'UZ-1'
                """)
                        .query((row, n) -> List.of(
                                row.getString(1), row.getString(2),
                                String.valueOf(row.getObject(3)), row.getBoolean(4)))
                        .single())
                .as("a refused push has to be evidence that HorecaOS received it: the partner's "
                        + "portal shows an order, and without this row HorecaOS has none")
                .containsExactly("REJECTED", "EXTERNAL_TOTAL_MISMATCH", "null", true);

        assertThat(countOrders()).isZero();
    }

    @Test
    @DisplayName("an accepted push books the partner's own numbers and reconciles both ways")
    void anAcceptedPushBooksThePartnersNumbers() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                push(
                        "UZ-2",
                        new ExternalTotals("UZS", 42_000, 40_000, 3_000, 5_000, null),
                        List.of(line("ITEM-1", 40_000))));

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.duplicate()).isFalse();

        Map<String, Object> order =
                jdbc.sql("""
                SELECT origin, pricing_authority, fulfillment_authority, entry_mode,
                       pricing_quote_id, cart_id, total_minor, subtotal_minor, tax_minor,
                       fee_minor, discount_minor
                FROM ordering.orders WHERE id = :id
                """).param("id", outcome.orderId()).query().singleRow();

        assertThat(order.get("origin")).isEqualTo("MARKETPLACE");
        assertThat(order.get("pricing_authority")).isEqualTo("EXTERNAL");
        assertThat(order.get("fulfillment_authority")).isEqualTo("PARTNER");
        assertThat(order.get("entry_mode")).isEqualTo("API");
        assertThat(order.get("total_minor")).isEqualTo(42_000L);
        assertThat(order.get("pricing_quote_id"))
                .as("no quote was computed, and a fabricated one would make an aggregator's "
                        + "total look reconstructible in every report that joins to pricing")
                .isNull();
        assertThat(order.get("cart_id")).isNull();

        // external_tax_minor stays null rather than becoming zero: the partner
        // said nothing about tax, which is not the same as saying tax is zero.
        assertThat(jdbc.sql("SELECT external_tax_minor FROM ordering.order_external_pricing " + "WHERE order_id = :id")
                        .param("id", outcome.orderId())
                        .query(Long.class)
                        .optional())
                .isEmpty();
    }

    /**
     * ADR 0040 decided an aggregator order is a first-class order. It was not one:
     * the intake wrote {@code ordering.orders} and planned nothing, so every
     * remedy an operator could record against it — a refund, a delivery-fee
     * reimbursement, the courier's cash figure — answered
     * {@code RESOURCE_NOT_FOUND, "The order has no settlement"}. A first-class
     * order that cannot be refunded is not one.
     */
    @Test
    @DisplayName("an accepted push gets a settlement, so an aggregator order can be refunded")
    void anAcceptedPushIsSettleable() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                push(
                        "UZ-SETTLE",
                        new ExternalTotals("UZS", 42_000, 40_000, 3_000, 5_000, null),
                        List.of(line("ITEM-1", 40_000))));

        assertThat(outcome.accepted()).isTrue();

        var settlement =
                settlementStore.findSettlement(TENANT, orderIdOf(outcome)).orElseThrow();
        assertThat(settlement.totalDueMinor())
                .as("the tenders sum to what the customer actually paid the aggregator")
                .isEqualTo(42_000L);

        var tenders = settlementStore.tendersOf(TENANT, settlement.id());
        assertThat(tenders).hasSize(1);
        assertThat(tenders.getFirst().amountMinor()).isEqualTo(42_000L);
        assertThat(tenders.getFirst().settlesFromBalance())
                .as("a marketplace order has no customer account and therefore no balance to " + "draw on")
                .isFalse();
    }

    /**
     * A fully discounted push is a real order and has nothing owed on it.
     *
     * <p>It reconciles ({@code 0 == 50 000 + 0 + 0 - 50 000}), passes both check
     * constraints, reaches the kitchen, and the branch cooks it. What it does not
     * have is money: the customer paid the aggregator nothing.
     *
     * <p>This used to be tendered under a planner-owned
     * {@code MARKETPLACE_PROMOTION} method for the value of the discount, on the
     * argument that {@code ck_order_settlement_total} forbids a settlement of zero
     * and that the promotion's value was the most a goodwill remedy could be worth.
     * The figure was not read that way by anything downstream. A promotion tender
     * is not {@code settles_from_balance}, so {@code OrderSettlementService.refund}
     * counted the whole of it as money and {@code OrderRemedyService.recordRefund}
     * would have written a cash refund of fifty thousand som — attested,
     * unverifiable, indistinguishable in every report from a card reversal — to a
     * customer who paid nothing.
     */
    @Test
    @DisplayName("a fully discounted push is a real order with no settlement, because nothing " + "is owed on it")
    void aFullyDiscountedPushIsOwedNothingAndTenderedNothing() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                push(
                        "UZ-FREE",
                        new ExternalTotals("UZS", 0, 50_000, 50_000, 0, null),
                        DiscountFunding.PARTNER,
                        List.of(line("ITEM-1", 50_000))));

        assertThat(outcome.accepted())
                .as("a hundred-percent-off promo order is a legitimate order and the branch " + "cooks it")
                .isTrue();
        assertThat(jdbc.sql("SELECT total_minor FROM ordering.orders WHERE id = :id")
                        .param("id", outcome.orderId())
                        .query(Long.class)
                        .single())
                .as("the customer paid the aggregator nothing, and the order says so")
                .isZero();

        assertThat(settlementStore.findSettlement(TENANT, orderIdOf(outcome)))
                .as("a settlement is the record of money owed and money moved; this order has "
                        + "neither, and the honest number of tenders for it is none")
                .isEmpty();
        assertThat(settlementStore.findMethodByCode(TENANT, "MARKETPLACE_PROMOTION"))
                .as("and no registry row was invented to hold a campaign as if it were a " + "payment method")
                .isEmpty();

        assertThat(jdbc.sql("""
                SELECT external_discount_minor FROM ordering.order_external_pricing
                 WHERE order_id = :id
                """)
                        .param("id", outcome.orderId())
                        .query(Long.class)
                        .single())
                .as("the discount is still recorded, once, where ADR 0040 put it")
                .isEqualTo(50_000L);
    }

    /**
     * The defect, stated as the thing an operator must not be able to do.
     *
     * <p>The refund goes through {@code OrderSettlementService.refund} because that
     * is the exact call {@code OrderRemedyService} delegates the cap to — "the cap
     * is not reimplemented here" — so whatever ceiling exists here is the ceiling
     * on a real cash refund recorded against a customer.
     */
    @Test
    @DisplayName("a fully discounted order cannot be refunded, because there is no money on it " + "to give back")
    void aFullyDiscountedOrderCannotBeCashRefunded() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                push(
                        "UZ-FREE-REMEDY",
                        new ExternalTotals("UZS", 0, 50_000, 50_000, 0, null),
                        DiscountFunding.PARTNER,
                        List.of(line("ITEM-1", 50_000))));

        // In production this is OrderConfirmedSettlementTrigger on ordering's own
        // OrderConfirmed event; the marketplace lifecycle's RECEIVED -> CONFIRMED
        // publishes it like any other. Called directly here because this suite
        // wires no application context. It is a no-op for an order with no
        // settlement, which is the point.
        settlementPlanner.recordConfirmation(TENANT, orderIdOf(outcome), "test");

        Throwable refused = catchThrowable(
                () -> settlementService.refund(TENANT, orderIdOf(outcome), 1L, "SERVICE_RECOVERY", "operator"));

        assertThat(refused)
                .as("one som of cash to a customer who paid none is the whole defect; the "
                        + "promotion's value was a goodwill ceiling and recordRefund is not a "
                        + "goodwill remedy")
                .isInstanceOf(ApiException.class);
        assertThat(refused.getMessage()).contains("no settlement");
    }

    @Test
    @DisplayName("a partly discounted push is still settled at what the customer paid, and the "
            + "discount does not raise the refund ceiling")
    void aPartlyPaidPushIsSettledAtWhatTheCustomerPaid() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                push(
                        "UZ-PART",
                        new ExternalTotals("UZS", 30_000, 50_000, 20_000, 0, null),
                        DiscountFunding.PARTNER,
                        List.of(line("ITEM-1", 50_000))));

        assertThat(outcome.accepted()).isTrue();

        var settlement =
                settlementStore.findSettlement(TENANT, orderIdOf(outcome)).orElseThrow();
        assertThat(settlement.totalDueMinor()).isEqualTo(30_000L);

        var tenders = settlementStore.tendersOf(TENANT, settlement.id());
        assertThat(tenders).hasSize(1);
        assertThat(settlementStore
                        .findMethod(TENANT, tenders.getFirst().paymentMethodId())
                        .orElseThrow()
                        .code())
                .as("money the customer handed the aggregator, not a campaign")
                .isEqualTo("MARKETPLACE");

        settlementPlanner.recordConfirmation(TENANT, orderIdOf(outcome), "test");

        assertThat(settlementService.refund(TENANT, orderIdOf(outcome), 30_000L, "COLD_FOOD", "operator"))
                .isEqualTo(30_000L);

        Throwable beyond =
                catchThrowable(() -> settlementService.refund(TENANT, orderIdOf(outcome), 1L, "COLD_FOOD", "operator"));
        assertThat(beyond)
                .as("folding the 20 000 discount into the settlement would let an operator "
                        + "return 50 000 to a customer who paid 30 000")
                .isInstanceOf(ApiException.class);
    }

    /**
     * ADR 0038's registry column decides who issues the fiscal receipt, and ADR 0040
     * names the answer for an aggregator-settled order in as many words: the
     * aggregator, where contracted as fiscal agent. The planner used to derive it
     * from whether the method had a {@code PaymentProviderType}, and {@code
     * MARKETPLACE} deliberately has none — HorecaOS holds no merchant account behind an
     * aggregator — so every marketplace tender registered the tenant as fiscally
     * responsible for money the aggregator collected.
     */
    @Test
    @DisplayName("a marketplace tender registers the aggregator as the fiscal responsibility, " + "never the tenant")
    void aMarketplaceTenderIsTheAggregatorsFiscalResponsibility() {
        ingestion.receive(principal(), push("UZ-FISCAL", totals(42_000), List.of(line("ITEM-1", 42_000))));

        assertThat(settlementStore
                        .findMethodByCode(TENANT, "MARKETPLACE")
                        .orElseThrow()
                        .responsibility())
                .as("OPERATOR is ADR 0038's 'a fiscal operator called directly by HorecaOS', which "
                        + "the pilot ships without; recording it here makes the tenant liable "
                        + "for a receipt the aggregator issues")
                .isEqualTo("MARKETPLACE");
    }

    /**
     * The books balanced, as one statement over every accepted push.
     *
     * <p>Asserted as an invariant rather than per shape, because the two defects
     * this suite records were both found by tracing one order path and the third
     * was found by asking what the other paths do. A settlement whose
     * {@code total_due_minor} disagrees with the order's own {@code total_minor}
     * is a refund ceiling that disagrees with what the customer paid, and the
     * direction of the disagreement decides whether the tenant loses money or the
     * customer does.
     */
    @Test
    @DisplayName("every settlement a push produces is for exactly what the order says it is " + "worth")
    void aSettlementNeverDisagreesWithTheOrderItSettles() {
        ingestion.receive(principal(), push("UZ-BAL-PAID", totals(42_000), List.of(line("ITEM-1", 42_000))));
        ingestion.receive(
                principal(),
                push(
                        "UZ-BAL-PART",
                        new ExternalTotals("UZS", 30_000, 50_000, 20_000, 0, null),
                        DiscountFunding.PARTNER,
                        List.of(line("ITEM-1", 50_000))));
        ingestion.receive(
                principal(),
                push(
                        "UZ-BAL-FREE",
                        new ExternalTotals("UZS", 0, 50_000, 50_000, 0, null),
                        DiscountFunding.PARTNER,
                        List.of(line("ITEM-1", 50_000))));
        ingestion.receive(
                principal(),
                push(
                        "UZ-BAL-FEE",
                        new ExternalTotals("UZS", 47_000, 40_000, 3_000, 10_000, null),
                        DiscountFunding.MERCHANT,
                        List.of(line("ITEM-1", 40_000))));

        List<Map<String, Object>> disagreements =
                jdbc.sql("""
                SELECT o.id AS order_id, o.total_minor, s.total_due_minor
                  FROM ordering.orders o
                  JOIN payments.order_settlements s
                    ON s.order_id = o.id AND s.tenant_id = o.tenant_id
                 WHERE o.tenant_id = :tenantId AND s.total_due_minor <> o.total_minor
                """).param("tenantId", TENANT).query().listOfRows();

        assertThat(disagreements)
                .as("the promotion tender put total_due_minor at 50 000 on an order whose "
                        + "total_minor is 0, for ever, on every fully discounted push")
                .isEmpty();

        Long tenderSumMismatches =
                jdbc.sql("""
                SELECT count(*) FROM payments.order_settlements s
                 WHERE s.tenant_id = :tenantId
                   AND s.total_due_minor <> (
                       SELECT COALESCE(SUM(t.amount_minor), 0) FROM payments.tenders t
                        WHERE t.settlement_id = s.id AND t.tenant_id = s.tenant_id)
                """).param("tenantId", TENANT).query(Long.class).single();
        assertThat(tenderSumMismatches)
                .as("and the tenders sum to the settlement, which is what makes the refund "
                        + "ceiling and the order total the same number")
                .isZero();
    }

    @Test
    @DisplayName("a push with no consideration anywhere in it is refused rather than cooked")
    void aPushWithNoConsiderationIsRefused() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(), push("UZ-ZERO", new ExternalTotals("UZS", 0, 0, 0, 0, null), List.of(line("ITEM-1", 0))));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.rejectionCode())
                .as("nothing paid and nothing discounted: no figure above zero anywhere in the "
                        + "push, which is a malformed push rather than a free meal")
                .isEqualTo(RejectionCode.ZERO_VALUE_ORDER);
        assertThat(countOrders()).isZero();
        assertThat(jdbc.sql("""
                SELECT rejection_code FROM partner.inbound_orders
                WHERE external_order_id = 'UZ-ZERO'
                """).query(String.class).single()).isEqualTo("ZERO_VALUE_ORDER");
    }

    @Test
    @DisplayName("another tenant reaches neither the settlement nor the registry row behind it")
    void aSettlementBelongsToTheTenantItWasPushedFor() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                push(
                        "UZ-PAID-TENANT",
                        new ExternalTotals("UZS", 50_000, 50_000, 0, 0, null),
                        DiscountFunding.PARTNER,
                        List.of(line("ITEM-1", 50_000))));

        assertThat(settlementStore.findSettlement(OTHER_TENANT, orderIdOf(outcome)))
                .as("an order id alone authorises nothing; the query constrains on the tenant "
                        + "the caller was authorised against")
                .isEmpty();
        assertThat(settlementStore.findMethodByCode(OTHER_TENANT, "MARKETPLACE"))
                .as("the ADR 0038 registry is tenant-scoped, and one tenant's marketplace " + "method is not another's")
                .isEmpty();

        settlementPlanner.recordConfirmation(TENANT, orderIdOf(outcome), "test");

        Throwable crossTenant = catchThrowable(() ->
                settlementService.refund(OTHER_TENANT, orderIdOf(outcome), 10_000L, "SERVICE_RECOVERY", "operator"));
        assertThat(crossTenant).isInstanceOf(ApiException.class);
        assertThat(crossTenant.getMessage()).contains("no settlement");
    }

    @Test
    @DisplayName("EXTERNAL pricing on a HorecaOS-origin order fails at the database, not only " + "in the service")
    void externalPricingCannotReachAHorecaOSChannel() {
        // Asserted on the constraint itself as well as on a refused INSERT.
        // The INSERT proves that a hand-written statement cannot get past it —
        // the path a service-level guard would not cover — but a row that
        // violates this constraint violates two others at the same time, and
        // which one PostgreSQL names is not something to assert on.
        assertThat(jdbc.sql("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_order_external_pricing_is_marketplace'
                """).query(String.class).single())
                .as("this is the boundary the decision exists to draw: an unvalidated external "
                        + "total must not be reachable from a channel HorecaOS prices")
                .contains("pricing_authority")
                .contains("'HORECAOS'")
                .contains("origin")
                .contains("'MARKETPLACE'");

        Throwable insert = catchThrowable(() -> jdbc.sql("""
                INSERT INTO ordering.orders (
                    id, public_order_number, tenant_id, brand_id, location_id, channel_id,
                    channel_code_snapshot, guest_reference_hash, fulfillment_mode,
                    acceptance_mode_snapshot, acceptance_policy_version,
                    approval_channel_snapshot, status, currency, subtotal_minor, tax_minor,
                    total_minor, idempotency_key, promise_basis, origin, pricing_authority)
                VALUES (
                    :id, 'X-1', :tenantId, :brandId, :locationId, :channelId,
                    'AGGREGATOR', 'guest-hash', 'DELIVERY',
                    'AUTO_CONFIRM', 0,
                    'NONE', 'RECEIVED', 'UZS', 1000, 0,
                    1000, 'k-1', 'NOT_PROMISED', 'HORECAOS', 'EXTERNAL')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channel)
                .update());

        assertThat(insert).isNotNull();
        assertThat(countOrders()).isZero();
    }

    @Test
    @DisplayName("a marketplace order cannot name a binding of a non-marketplace installation")
    void aMarketplaceOrderCannotNameADeliveryBinding() {
        MarketplaceIngestionService.Outcome outcome =
                ingestion.receive(principal(), push("UZ-3", totals(50_000), List.of(line("ITEM-1", 50_000))));

        Throwable moved = catchThrowable(() -> jdbc.sql("""
                UPDATE ordering.orders SET marketplace_binding_id = :binding WHERE id = :id
                """)
                .param("binding", deliveryBinding)
                .param("id", outcome.orderId())
                .update());

        assertThat(moved)
                .as("Yandex Delivery sources a courier for an order HorecaOS owns and Yandex Eda "
                        + "sends one HorecaOS did not create; same company, opposite direction")
                .isNotNull();
        assertThat(moved.getMessage()).contains("MARKETPLACE installation");
    }

    // --------------------------------------------------------------- idempotency

    @Test
    @DisplayName("two pushes of one partner order produce exactly one order")
    void aRetriedPushDoesNotCookTwice() {
        MarketplaceIngestionService.Outcome first =
                ingestion.receive(principal(), push("UZ-DUP", totals(60_000), List.of(line("ITEM-1", 60_000))));
        MarketplaceIngestionService.Outcome second =
                ingestion.receive(principal(), push("UZ-DUP", totals(60_000), List.of(line("ITEM-1", 60_000))));

        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isTrue();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.orderId()).isEqualTo(first.orderId());

        assertThat(countOrders())
                .as("a retried order that creates a second one is a restaurant cooking twice")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the duplicate defence is the database's, not the service's")
    void theDuplicateDefenceIsAUniqueIndex() {
        ingestion.receive(principal(), push("UZ-IDX", totals(10_000), List.of(line("ITEM-1", 10_000))));

        Throwable failure = catchThrowable(() -> jdbc.sql("""
                INSERT INTO partner.inbound_orders (
                    id, tenant_id, binding_id, external_order_id, raw_payload_encrypted,
                    payload_sha256, outcome, rejection_code)
                VALUES (
                    :id, :tenantId, :bindingId, 'UZ-IDX', 'x',
                    '0000000000000000000000000000000000000000000000000000000000000000',
                    'REJECTED', 'UNKNOWN_VENUE')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("bindingId", binding)
                .update());

        assertThat(failure)
                .as("an application-level existence check lets two concurrent pushes both pass")
                .isNotNull();
        assertThat(failure.getMessage()).contains("uq_inbound_per_binding");
    }

    @Test
    @DisplayName("a refused partner order id stays refused, with the same code")
    void theOutcomeOfOnePartnerOrderIdIsDecidedOnce() {
        MarketplaceIngestionService.Outcome first = ingestion.receive(
                principal(),
                push(
                        "UZ-RESTATE",
                        new ExternalTotals("UZS", 45_000, 40_000, 3_000, 5_000, null),
                        List.of(line("ITEM-1", 40_000))));
        assertThat(first.rejectionCode()).isEqualTo(RejectionCode.EXTERNAL_TOTAL_MISMATCH);

        // The partner "fixes" the arithmetic and pushes the same identifier again.
        MarketplaceIngestionService.Outcome second =
                ingestion.receive(principal(), push("UZ-RESTATE", totals(42_000), List.of(line("ITEM-1", 42_000))));

        assertThat(second.accepted())
                .as("re-evaluating a second push of one identifier would let a partner restate "
                        + "a total HorecaOS had already refused, and the restatement would win")
                .isFalse();
        assertThat(second.rejectionCode()).isEqualTo(RejectionCode.EXTERNAL_TOTAL_MISMATCH);
        assertThat(countOrders()).isZero();
    }

    // ------------------------------------------------------------- unmapped lines

    @Test
    @DisplayName("a line the catalogue does not carry is accepted, flagged, and reported back")
    void anUnmappedLineIsFlaggedRatherThanRefused() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                push("UZ-UNMAPPED", totals(70_000), List.of(line("ITEM-1", 40_000), line("ITEM-UNKNOWN", 30_000))));

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.unmappedItems()).containsExactly("ITEM-UNKNOWN");

        List<Map<String, Object>> lines =
                jdbc.sql("""
                SELECT external_item_reference, external_mapping_status, source_variant_id
                FROM ordering.order_lines WHERE order_id = :id ORDER BY line_number
                """).param("id", outcome.orderId()).query().listOfRows();

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("external_mapping_status")).isEqualTo("MAPPED");
        assertThat(lines.get(0).get("source_variant_id")).isEqualTo(mappedVariant);
        assertThat(lines.get(1).get("external_mapping_status"))
                .as("refusing the order over a menu-sync lag means a customer who already paid "
                        + "the aggregator gets nothing, and the branch never learns why")
                .isEqualTo("UNMAPPED");
        assertThat(lines.get(1).get("source_variant_id")).isNull();
    }

    @Test
    @DisplayName("an unmapped line cannot be written without saying so")
    void aVariantlessLineMustDeclareItself() {
        MarketplaceIngestionService.Outcome outcome =
                ingestion.receive(principal(), push("UZ-LINECHK", totals(10_000), List.of(line("ITEM-1", 10_000))));

        Throwable failure = catchThrowable(
                () -> jdbc.sql("""
                UPDATE ordering.order_lines SET source_variant_id = NULL WHERE order_id = :id
                """).param("id", outcome.orderId()).update());

        assertThat(failure)
                .as("a nullable status column would leave this row passing on three-valued logic")
                .isNotNull();
        assertThat(failure.getMessage()).contains("ck_order_line_unmapped_has_no_variant");
    }

    // ----------------------------------------------------------------- rejections

    @Test
    @DisplayName("a venue nobody has bound is refused before anything is written")
    void anUnknownVenueIsRefused() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                new PartnerOrderPush(
                        "no-such-venue",
                        "UZ-4",
                        null,
                        "DELIVERY",
                        totals(10_000),
                        DiscountFunding.UNKNOWN,
                        List.of(line("ITEM-1", 10_000)),
                        null,
                        null,
                        "{}",
                        "{}"));

        assertThat(outcome.rejectionCode()).isEqualTo(RejectionCode.UNKNOWN_VENUE);
        assertThat(jdbc.sql("SELECT count(*) FROM partner.inbound_orders")
                        .query(Long.class)
                        .single())
                .as("a staging row against a binding the caller does not hold would be a write "
                        + "an enumeration attempt could cause")
                .isZero();
    }

    @Test
    @DisplayName("a branch closed by an explicit override refuses the order")
    void aForceClosedBranchRefuses() {
        jdbc.sql("""
                INSERT INTO tenant.location_service_state (
                    location_id, tenant_id, brand_id, mode, reason_code)
                VALUES (:locationId, :tenantId, :brandId, 'FORCE_CLOSED', 'EQUIPMENT_FAILURE')
                """)
                .param("locationId", branch)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        MarketplaceIngestionService.Outcome outcome =
                ingestion.receive(principal(), push("UZ-5", totals(10_000), List.of(line("ITEM-1", 10_000))));

        assertThat(outcome.rejectionCode()).isEqualTo(RejectionCode.BRANCH_CLOSED);
    }

    @Test
    @DisplayName("a currency the branch does not sell in is refused")
    void aForeignCurrencyIsRefused() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                push("UZ-6", new ExternalTotals("USD", 10_000, 10_000, 0, 0, null), List.of(line("ITEM-1", 10_000))));

        assertThat(outcome.rejectionCode()).isEqualTo(RejectionCode.CURRENCY_MISMATCH);
    }

    // ------------------------------------------------------------ authentication

    @Test
    @DisplayName("a partner credential cannot name another tenant")
    void aPartnerCannotNameAnotherTenant() {
        Throwable failure = catchThrowable(() -> authentication.authenticate(CLIENT_ID, OTHER_TENANT));

        assertThat(failure)
                .as("a partner is the principal least able to be trusted with a path parameter")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a suspended credential stops working immediately")
    void aSuspendedCredentialStopsWorking() {
        assertThat(authentication.authenticate(CLIENT_ID, TENANT).bindingIds()).contains(binding);

        jdbc.sql("UPDATE partner.api_clients SET status = 'SUSPENDED' WHERE client_id = :c")
                .param("c", CLIENT_ID)
                .update();

        assertThat(catchThrowable(() -> authentication.authenticate(CLIENT_ID, TENANT)))
                .as("rollback suspends the binding; it must not need a deployment")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an expired partner credential stops working")
    void anExpiredCredentialStopsWorking() {
        jdbc.sql("""
                UPDATE partner.api_clients
                   SET secret_rotated_at = :rotated, secret_expires_at = :expired
                 WHERE client_id = :c
                """)
                .param("rotated", NOON.minus(Duration.ofDays(90)).atOffset(ZoneOffset.UTC))
                .param("expired", NOON.minusSeconds(1).atOffset(ZoneOffset.UTC))
                .param("c", CLIENT_ID)
                .update();

        assertThat(catchThrowable(() -> authentication.authenticate(CLIENT_ID, TENANT)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a credential with no live binding reaches nothing")
    void anUnboundCredentialReachesNothing() {
        jdbc.sql("UPDATE integration.bindings SET status = 'SUSPENDED' WHERE id = :binding")
                .param("binding", binding)
                .update();

        assertThat(catchThrowable(() -> authentication.authenticate(CLIENT_ID, TENANT)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a partner cannot push to a venue outside its own bindings")
    void aPartnerCannotPushToSomebodyElsesVenue() {
        // A principal holding a binding that is not this venue's — the shape of
        // an integration bug that iterates identifiers, and of an attack.
        PartnerPrincipal stranger = new PartnerPrincipal(
                UUID.randomUUID(), "partner-other", TENANT, UUID.randomUUID(), Set.of(UUID.randomUUID()));

        MarketplaceIngestionService.Outcome outcome =
                ingestion.receive(stranger, push("UZ-7", totals(10_000), List.of(line("ITEM-1", 10_000))));

        assertThat(outcome.rejectionCode()).isEqualTo(RejectionCode.VENUE_NOT_PERMITTED);
        assertThat(countOrders()).isZero();
    }

    // ---------------------------------------------------------------- references

    @Test
    @DisplayName("an order is findable by a hyphenated, spaced, lowercase or #-prefixed rendering")
    void referenceSearchFindsWhateverWasReadOffAScreen() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                new PartnerOrderPush(
                        VENUE,
                        "UZ-2291-04",
                        "YE-2291-04",
                        "DELIVERY",
                        totals(10_000),
                        DiscountFunding.UNKNOWN,
                        List.of(line("ITEM-1", 10_000)),
                        null,
                        null,
                        "{}",
                        "{}"));

        for (String typed : List.of("YE-2291-04", "ye 2291 04", "#YE229104", "  Ye-2291 04 ")) {
            assertThat(store.searchByReference(TENANT, ExternalReference.normalise(typed), 10))
                    .as(
                            "an operator reading %s off a courier's phone finds nothing while the "
                                    + "order sits four rows above in the same list",
                            typed)
                    .extracting(JdbcPartnerStore.ReferenceMatch::orderId)
                    .contains(outcome.orderId());
        }
    }

    @Test
    @DisplayName("normalisation does not collapse a zero-padded code into an unpadded one")
    void normalisationKeepsLeadingZeros() {
        assertThat(ExternalReference.normalise("0042")).isEqualTo("0042");
        assertThat(ExternalReference.normalise("#42")).isEqualTo("42");
    }

    // ------------------------------------------------------------------ handover

    @Test
    @DisplayName("five wrong codes reach FAILED, and no response ever carries the expected value")
    void fiveWrongCodesExhaustTheChallenge() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                new PartnerOrderPush(
                        VENUE,
                        "UZ-8",
                        null,
                        "DELIVERY",
                        totals(10_000),
                        DiscountFunding.UNKNOWN,
                        List.of(line("ITEM-1", 10_000)),
                        "4417",
                        null,
                        "{}",
                        "{}"));

        List<HandoverVerificationService.Verification> attempts = new ArrayList<>();
        for (int attempt = 0; attempt < 5; attempt++) {
            attempts.add(handovers.verify(TENANT, orderIdOf(outcome), "0000", "expo-1"));
        }

        assertThat(attempts).allMatch(result -> !result.verified());
        assertThat(attempts.get(4).status()).isEqualTo(HandoverChallengeStatus.FAILED);
        assertThat(attempts)
                .extracting(HandoverVerificationService.Verification::attemptsRemaining)
                .containsExactly(4, 3, 2, 1, 0);

        assertThat(jdbc.sql("SELECT status FROM ordering.order_handover_challenges " + "WHERE order_id = :id")
                        .param("id", outcome.orderId())
                        .query(String.class)
                        .single())
                .isEqualTo("FAILED");

        assertThat(jdbc.sql("SELECT expected_value_hash FROM ordering.order_handover_challenges "
                                + "WHERE order_id = :id")
                        .param("id", outcome.orderId())
                        .query(String.class)
                        .single())
                .as("a handover code in a readable column is a code anyone with a read replica " + "can use")
                .isNotEqualTo("4417");
    }

    @Test
    @DisplayName("the right code verifies, and a hash cannot be replayed against another order")
    void theRightCodeVerifiesAndDoesNotTravel() {
        MarketplaceIngestionService.Outcome first = ingestion.receive(
                principal(),
                new PartnerOrderPush(
                        VENUE,
                        "UZ-9",
                        null,
                        "DELIVERY",
                        totals(10_000),
                        DiscountFunding.UNKNOWN,
                        List.of(line("ITEM-1", 10_000)),
                        "4417",
                        null,
                        "{}",
                        "{}"));
        MarketplaceIngestionService.Outcome second = ingestion.receive(
                principal(),
                new PartnerOrderPush(
                        VENUE,
                        "UZ-10",
                        null,
                        "DELIVERY",
                        totals(10_000),
                        DiscountFunding.UNKNOWN,
                        List.of(line("ITEM-1", 10_000)),
                        "4417",
                        null,
                        "{}",
                        "{}"));

        String firstHash = challengeHash(orderIdOf(first));
        String secondHash = challengeHash(orderIdOf(second));
        assertThat(firstHash)
                .as("two orders drawing the same code must not produce the same stored value, "
                        + "or one hash lifted from a dump works against the other order")
                .isNotEqualTo(secondHash);

        assertThat(handovers.verify(TENANT, orderIdOf(first), "44 17", "expo-1").verified())
                .as("couriers read codes back with spaces in them, and a branch forced to type "
                        + "separators exactly is a branch that bypasses the check")
                .isTrue();
    }

    @Test
    @DisplayName("a bypass needs a reason and writes the audit fact naming the supervisor")
    void aBypassIsAlwaysAttributable() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                new PartnerOrderPush(
                        VENUE,
                        "UZ-11",
                        null,
                        "DELIVERY",
                        totals(10_000),
                        DiscountFunding.UNKNOWN,
                        List.of(line("ITEM-1", 10_000)),
                        "4417",
                        null,
                        "{}",
                        "{}"));

        assertThat(catchThrowable(() -> handovers.bypass(
                        TENANT, ResourceScope.tenant(TENANT), orderIdOf(outcome), "  ", "sup-1", "Aziza", null)))
                .isInstanceOf(ApiException.class);

        handovers.bypass(
                TENANT,
                ResourceScope.tenant(TENANT),
                orderIdOf(outcome),
                "COURIER_APP_OFFLINE",
                "sup-1",
                "Aziza",
                null);

        assertThat(jdbc.sql("SELECT status, bypass_reason_code, verified_by "
                                + "FROM ordering.order_handover_challenges WHERE order_id = :id")
                        .param("id", outcome.orderId())
                        .query((row, n) -> List.of(row.getString(1), row.getString(2), row.getString(3)))
                        .single())
                .containsExactly("BYPASSED", "COURIER_APP_OFFLINE", "sup-1");

        assertThat(audit.facts)
                .as("an override that succeeded without a record is indistinguishable from one "
                        + "that never happened")
                .anySatisfy(fact -> {
                    assertThat(fact.actionCode()).isEqualTo("marketplace.handover.bypassed");
                    assertThat(fact.reason()).isEqualTo("COURIER_APP_OFFLINE");
                    assertThat(fact.capabilityUsed()).isEqualTo("marketplace.handover.bypass");
                });
    }

    @Test
    @DisplayName("a bypass is still available after the attempts are exhausted")
    void aBypassSurvivesExhaustion() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                new PartnerOrderPush(
                        VENUE,
                        "UZ-12",
                        null,
                        "DELIVERY",
                        totals(10_000),
                        DiscountFunding.UNKNOWN,
                        List.of(line("ITEM-1", 10_000)),
                        "4417",
                        null,
                        "{}",
                        "{}"));

        for (int attempt = 0; attempt < 5; attempt++) {
            handovers.verify(TENANT, orderIdOf(outcome), "0000", "expo-1");
        }

        handovers.bypass(
                TENANT,
                ResourceScope.tenant(TENANT),
                orderIdOf(outcome),
                "COURIER_APP_OFFLINE",
                "sup-1",
                "Aziza",
                null);

        assertThat(jdbc.sql("SELECT status FROM ordering.order_handover_challenges " + "WHERE order_id = :id")
                        .param("id", outcome.orderId())
                        .query(String.class)
                        .single())
                .as("a branch with no way past an exhausted challenge invents one, usually by "
                        + "handing over the food and typing nothing")
                .isEqualTo("BYPASSED");
    }

    @Test
    @DisplayName("one order can have only one open challenge")
    void oneOpenChallengePerOrder() {
        MarketplaceIngestionService.Outcome outcome = ingestion.receive(
                principal(),
                new PartnerOrderPush(
                        VENUE,
                        "UZ-13",
                        null,
                        "DELIVERY",
                        totals(10_000),
                        DiscountFunding.UNKNOWN,
                        List.of(line("ITEM-1", 10_000)),
                        "4417",
                        null,
                        "{}",
                        "{}"));

        Throwable failure = catchThrowable(() -> jdbc.sql("""
                INSERT INTO ordering.order_handover_challenges (
                    id, tenant_id, order_id, challenge_type, issued_by, expected_value_hash, status)
                VALUES (:id, :tenantId, :orderId, 'CODE', 'HORECAOS', 'deadbeef', 'PENDING')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("orderId", outcome.orderId())
                .update());

        assertThat(failure)
                .as("two open challenges is one bag proved handed over twice, to two people")
                .isNotNull();
        assertThat(failure.getMessage()).contains("uq_handover_open_per_order");
    }

    // ------------------------------------------------------------------ liveness

    @Test
    @DisplayName("a successful push clears the alert; a silence past the threshold raises one")
    void livenessTracksSilenceRatherThanFailure() {
        ingestion.receive(principal(), push("UZ-14", totals(10_000), List.of(line("ITEM-1", 10_000))));

        assertThat(liveness.matrix(TENANT)).singleElement().satisfies(row -> {
            assertThat(row.alertState()).isEqualTo("HEALTHY");
            assertThat(row.lastSuccessReference()).isEqualTo("UZ-14");
            assertThat(row.silenceSeconds()).isZero();
        });

        // Pull the last success back beyond the binding's own threshold.
        jdbc.sql("""
                UPDATE integration.provider_activity_watermarks
                SET last_success_at = last_success_at - make_interval(secs => stale_after_seconds + 60)
                WHERE tenant_id = :tenantId
                """).param("tenantId", TENANT).update();

        assertThat(liveness.evaluateStaleness(TENANT)).containsExactly(binding);
        assertThat(liveness.evaluateStaleness(TENANT))
                .as("a sweep that runs every minute must raise one alert per binding, not one " + "per run")
                .isEmpty();

        ingestion.receive(principal(), push("UZ-15", totals(10_000), List.of(line("ITEM-1", 10_000))));
        assertThat(liveness.matrix(TENANT))
                .singleElement()
                .satisfies(row -> assertThat(row.alertState()).isEqualTo("HEALTHY"));
    }

    @Test
    @DisplayName("a binding that has never received anything reports no silence at all")
    void neverHeardFromIsNotTheSameAsWentQuiet() {
        ingestion.receive(
                principal(), push("UZ-16", new ExternalTotals("UZS", 1, 2, 0, 0, null), List.of(line("ITEM-1", 2))));

        assertThat(liveness.matrix(TENANT)).singleElement().satisfies(row -> {
            assertThat(row.lastSuccessAt()).isNull();
            assertThat(row.silenceSeconds())
                    .as("an unfinished configuration and a working integration that stopped are "
                            + "fixed in different places")
                    .isNull();
            assertThat(row.lastFailureCode()).isEqualTo("EXTERNAL_TOTAL_MISMATCH");
        });
    }

    // ----------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("a partner-fulfilled order never passes through FULFILLING")
    void theNarrowedPathSkipsFulfilling() {
        assertThat(MarketplaceOrderLifecycle.PATH)
                .as("FULFILLING means HorecaOS is delivering this, and HorecaOS is not")
                .doesNotContain("FULFILLING")
                .containsExactly("RECEIVED", "CONFIRMED", "PREPARING", "READY", "COMPLETED");

        assertThat(MarketplaceOrderLifecycle.permits("READY", "FULFILLING")).isFalse();
        assertThat(MarketplaceOrderLifecycle.permits("READY", "COMPLETED")).isTrue();
    }

    @Test
    @DisplayName("a partner may cancel a confirmed order and nothing else")
    void thePartnerDrivesExactlyOneTransition() {
        assertThat(MarketplaceOrderLifecycle.partnerMayDrive("CONFIRMED", "CANCELLED"))
                .as("refusing it leaves a kitchen cooking food for a customer the aggregator " + "has already refunded")
                .isTrue();
        assertThat(MarketplaceOrderLifecycle.partnerMayDrive("CONFIRMED", "COMPLETED"))
                .isFalse();
        assertThat(MarketplaceOrderLifecycle.partnerMayDrive("RECEIVED", "PREPARING"))
                .isFalse();
        assertThat(MarketplaceOrderLifecycle.partnerMayDrive("COMPLETED", "CANCELLED"))
                .as("a partner arguing about a bag that left the building is a settlement "
                        + "conversation, not a state change")
                .isFalse();
    }

    @Test
    @DisplayName("the lifecycle's statuses are a subset of the order status vocabulary")
    void theLifecycleAgreesWithTheDatabase() {
        List<String> permitted =
                jdbc.sql("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_order_status'
                """).query(String.class).single().lines().toList();

        String definition = String.join(" ", permitted);
        for (String status : MarketplaceOrderLifecycle.PATH) {
            assertThat(definition)
                    .as("a narrowed path naming a status the table refuses is a path nothing " + "can walk")
                    .contains("'" + status + "'");
        }
    }

    // -------------------------------------------------------------------- helpers

    private PartnerPrincipal principal() {
        return authentication.authenticate(CLIENT_ID, TENANT);
    }

    /**
     * {@code Outcome.orderId()} is null only for a rejected push (see
     * {@code Outcome.rejected}); every call site here follows an accepted or
     * duplicate outcome, where it is always present.
     */
    private static UUID orderIdOf(MarketplaceIngestionService.Outcome outcome) {
        return Objects.requireNonNull(outcome.orderId(), "an accepted or duplicate outcome always has an order id");
    }

    private ExternalTotals totals(long total) {
        return new ExternalTotals("UZS", total, total, 0, 0, null);
    }

    private PushLine line(String reference, long amount) {
        return new PushLine(reference, "Palov", 1, amount, amount, null);
    }

    private PartnerOrderPush push(String externalOrderId, ExternalTotals totals, List<PushLine> lines) {
        return push(externalOrderId, totals, DiscountFunding.UNKNOWN, lines);
    }

    private PartnerOrderPush push(
            String externalOrderId, ExternalTotals totals, DiscountFunding funding, List<PushLine> lines) {
        return new PartnerOrderPush(
                VENUE, externalOrderId, null, "DELIVERY", totals, funding, lines, null, null, "{}", "{}");
    }

    private long countOrders() {
        return jdbc.sql("SELECT count(*) FROM ordering.orders")
                .query(Long.class)
                .single();
    }

    private String challengeHash(UUID orderId) {
        return jdbc.sql("SELECT expected_value_hash FROM ordering.order_handover_challenges " + "WHERE order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }

    private void seed() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'marketplace-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'other-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", OTHER_TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        branch = insertLocation("CENTRE", "centre");
        // A second location in the same tenant, so a query that forgets a
        // location predicate has more than one row to leak across.
        insertLocation("NORTH", "north");

        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production,
                    egress_allowlist)
                VALUES ('uzum-sandbox', 'MARKETPLACE', 'UZUM_TEZKOR',
                        'https://sandbox.example.uz', false, 'sandbox.example.uz')
                """).update();
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production,
                    egress_allowlist)
                VALUES ('yandex-delivery', 'DELIVERY', 'YANDEX_DELIVERY',
                        'https://delivery.example.uz', false, 'delivery.example.uz')
                """).update();

        installation = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status)
                VALUES (:id, :tenantId, 'MARKETPLACE', 'UZUM_TEZKOR', 'uzum-sandbox',
                        'Uzum Tezkor', 'ACTIVE')
                """).param("id", installation).param("tenantId", TENANT).update();

        UUID deliveryInstallation = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status)
                VALUES (:id, :tenantId, 'DELIVERY', 'YANDEX_DELIVERY', 'yandex-delivery',
                        'Yandex Delivery', 'ACTIVE')
                """)
                .param("id", deliveryInstallation)
                .param("tenantId", TENANT)
                .update();

        binding = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (
                    id, tenant_id, installation_id, brand_id, location_id, status,
                    effective_from, configuration_override)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE',
                        :effectiveFrom, CAST(:override AS jsonb))
                """)
                .param("effectiveFrom", EFFECTIVE_FROM)
                .param("id", binding)
                .param("tenantId", TENANT)
                .param("installationId", installation)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("override", "{\"venueReference\": \"" + VENUE + "\"}")
                .update();

        deliveryBinding = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (
                    id, tenant_id, installation_id, brand_id, location_id, status,
                    effective_from)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE',
                        :effectiveFrom)
                """)
                .param("effectiveFrom", EFFECTIVE_FROM)
                .param("id", deliveryBinding)
                .param("tenantId", TENANT)
                .param("installationId", deliveryInstallation)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .update();

        channel = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (
                    id, tenant_id, code, system_type, display_name, status, externally_priced,
                    provider_installation_id)
                VALUES (:id, :tenantId, 'UZUM', 'AGGREGATOR', 'Uzum Tezkor', 'ACTIVE', true,
                        :installationId)
                """)
                .param("id", channel)
                .param("tenantId", TENANT)
                .param("installationId", installation)
                .update();

        mappedVariant = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.provider_entity_mappings (
                    id, tenant_id, installation_id, binding_id, entity_type, horecaos_entity_id,
                    external_entity_id, status, mapping_source)
                VALUES (:id, :tenantId, :installationId, :bindingId, 'MENU_ITEM', :variantId,
                        'ITEM-1', 'ACTIVE', 'OPERATOR')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("installationId", installation)
                .param("bindingId", binding)
                .param("variantId", mappedVariant)
                .update();

        jdbc.sql("""
                INSERT INTO partner.api_clients (
                    id, tenant_id, installation_id, client_id, secret_reference, status)
                VALUES (:id, :tenantId, :installationId, :clientId,
                        'horecaos:local:provider_pos:tenant:uzum-partner', 'ACTIVE')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("installationId", installation)
                .param("clientId", CLIENT_ID)
                .update();
    }

    private UUID insertLocation(String code, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :name, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .param("slug", slug)
                .param("name", code)
                .update();
        return id;
    }

    /**
     * Envelope encryption is ADR 0029's and is tested there. What this suite
     * needs is that the ingestion routes the payload through it at all, which a
     * passthrough proves without dragging key management into a marketplace test.
     */
    private static final class PassthroughFieldProtection implements FieldProtection {

        @Override
        public ProtectedValue protect(UUID tenantId, DataClass dataClass, RecordRef record, String plaintext) {
            return new ProtectedValue("test-key", "none", new byte[12], plaintext.getBytes(StandardCharsets.UTF_8), 1);
        }

        @Override
        public String reveal(UUID tenantId, ProtectedValue value, RecordRef record, String purpose) {
            return new String(value.ciphertext(), StandardCharsets.UTF_8);
        }

        @Override
        public String lookupHash(UUID tenantId, String lookupDomain, String normalizedValue) {
            return Integer.toHexString(normalizedValue.hashCode());
        }
    }

    private static final class RecordingAuditRecorder implements AuditRecorder {

        private final List<AuditFact> facts = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }
    }
}
