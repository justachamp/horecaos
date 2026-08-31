package uz.horecaos.platform.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.loyalty.api.PointsRedemptionPort;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.api.EntitlementBenefit;
import uz.horecaos.platform.payments.api.EntitlementScope;
import uz.horecaos.platform.payments.api.RemedyEntitlementPort.RedeemCommand;
import uz.horecaos.platform.payments.api.RemedyEntitlementPort.RedemptionOutcome;
import uz.horecaos.platform.payments.application.DeliveryFeeBasisPort;
import uz.horecaos.platform.payments.settlement.CheckoutSettlementPlanner;
import uz.horecaos.platform.payments.settlement.EntitlementStatus;
import uz.horecaos.platform.payments.settlement.ExecutionChannel;
import uz.horecaos.platform.payments.settlement.JdbcRemedyStore;
import uz.horecaos.platform.payments.settlement.JdbcRemedyStore.RemedyRow;
import uz.horecaos.platform.payments.settlement.JdbcRemedyStore.RemedyTotals;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore;
import uz.horecaos.platform.payments.settlement.OrderRemedyService;
import uz.horecaos.platform.payments.settlement.OrderRemedyService.FutureDiscountCommand;
import uz.horecaos.platform.payments.settlement.OrderRemedyService.RefundCommand;
import uz.horecaos.platform.payments.settlement.OrderRemedyService.RemedyOutcome;
import uz.horecaos.platform.payments.settlement.OrderSettlementService;
import uz.horecaos.platform.payments.settlement.OrderSettlementService.PlannedTender;
import uz.horecaos.platform.payments.settlement.OrderSettlementService.SettlementPlan;
import uz.horecaos.platform.payments.settlement.RemedyEntitlementService;
import uz.horecaos.platform.payments.settlement.RemedyType;
import uz.horecaos.platform.payments.settlement.SettlementBasis;
import uz.horecaos.platform.payments.settlement.SettlementStatus;
import uz.horecaos.platform.payments.settlement.TenderStatus;
import uz.horecaos.platform.payments.settlement.VerificationState;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * Refunds and service-recovery remedies as bookkeeping (ADR 0013, amended by the
 * owner's decision of 2026-08-25).
 *
 * <p>Against a real PostgreSQL, because the load-bearing parts of this design are
 * properties of the database. Whether a future discount can be summed into a
 * refund total is a question about a check constraint. Whether an attestation can
 * be recorded with nobody attached to it is a question about another one. Whether
 * two orders can both spend the last use of a three-use grant is a question about
 * a conditional UPDATE. None of those can be asserted against a mock.
 *
 * <p>The schema comes from V0052, which Flyway applies to the shared template
 * this suite's database is cloned from, like any other migration. It was handed over as a DDL constant while this slice could not add
 * one; that constant is gone now rather than left beside the migration, because
 * two copies of a schema disagree eventually and the test would be the one
 * asserting against the wrong half.
 *
 * <p>Several assertions below are about a <em>separation</em> rather than a
 * total — that money the platform did not move is reported apart from money it
 * did, that a delivery-fee reimbursement never lands in a refund figure. Those
 * are the tests worth having, because the whole risk of this design is a ledger
 * that quietly reads as if HorecaOS had performed the refunds it merely recorded.
 */
class RefundAndRemedyTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();

    /** A Tuesday lunchtime in Tashkent. */
    private static final Instant NOW = Instant.parse("2026-08-25T07:00:00Z");

    /** Matches the service default, so the thresholds in these tests are the real ones. */
    private static final long THRESHOLD = 200_000L;

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private MutableClock clock;

    private JdbcSettlementStore settlementStore;
    private JdbcRemedyStore remedyStore;
    private OrderSettlementService settlements;
    private CheckoutSettlementPlanner planner;
    private OrderRemedyService remedies;
    private RemedyEntitlementService entitlements;

    private RecordingPoints points;
    private RecordingAudit audit;
    private SwitchableApprovals approvals;
    private StubDeliveryFees deliveryFees;
    private StubOrders orders;

    private UUID locationId;
    private UUID channelId;
    private UUID publicationId;
    private UUID customerId;
    private UUID otherCustomerId;
    private UUID clickMethod;
    private UUID pointsMethod;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for remedy tests");
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

        jdbc.sql("TRUNCATE TABLE payments.entitlement_redemptions, "
                        + "payments.remedy_entitlements, payments.order_remedies CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE payments.tenders, payments.order_settlements, " + "payments.payment_methods CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.order_lines, ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(NOW);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        settlementStore = new JdbcSettlementStore(jdbc);
        remedyStore = new JdbcRemedyStore(jdbc);
        points = new RecordingPoints();
        audit = new RecordingAudit();
        approvals = new SwitchableApprovals();
        deliveryFees = new StubDeliveryFees();
        orders = new StubOrders();

        settlements = new OrderSettlementService(settlementStore, points, clock);
        planner = new CheckoutSettlementPlanner(settlementStore, settlements, clock);
        // A no-op publisher: this suite's own StubOrders is a fake OrderDirectory
        // with no JdbcOrderStore behind it, so there is nothing here for
        // PaymentProjectionTrigger to write to. What recordRefund now publishes is
        // covered against a real order in CartCheckoutAndOrderTests instead.
        remedies = new OrderRemedyService(
                remedyStore, settlements, orders, deliveryFees, approvals, audit, event -> {}, clock, THRESHOLD);
        entitlements = new RemedyEntitlementService(remedyStore, clock);

        seedTenancy();
        seedPaymentMethods();
    }

    // -------------------------------------------------- the reconciliation gap

    @Test
    @DisplayName(
            "a refund splits into the money the platform moved and the money it is taking " + "somebody's word for")
    void aRefundSeparatesWhatThePlatformSettledFromWhatItWasTold() {
        UUID order = settledSplitOrder("A-1", 100_000L, 4_000L);

        RemedyOutcome outcome = refund(order, 100_000L, consoleRefund());

        RemedyRow remedy = Objects.requireNonNull(outcome.remedy(), "this refund needed no approval");
        assertThat(remedy.amountMinor()).isEqualTo(100_000L);
        assertThat(remedy.attestedMoneyMinor())
                .as("the 96 000 that went back through the card is an assertion about a cabinet "
                        + "this platform never called")
                .isEqualTo(96_000L);
        assertThat(remedy.platformSettledMinor())
                .as("the 4 000 of points was reversed here, in this transaction, against the " + "lots that were spent")
                .isEqualTo(4_000L);
        assertThat(remedy.settlementBasis()).isEqualTo(SettlementBasis.MIXED);
        assertThat(remedy.attestedMoneyMinor() + remedy.platformSettledMinor())
                .as("the two buckets are the amount, which is the invariant that stops a report "
                        + "producing one figure without choosing to")
                .isEqualTo(remedy.amountMinor());
        assertThat(points.reversed).containsExactly(4_000L);
    }

    @Test
    @DisplayName("a recorded refund is born unverified, and says who claimed it and when, "
            + "separately from who typed it in")
    void anAttestationCarriesItsClaimantAndIsNotAFact() {
        UUID order = settledSplitOrder("A-2", 100_000L, 4_000L);
        Instant inTheCabinet = NOW.minus(Duration.ofMinutes(20));

        RemedyRow remedy = Objects.requireNonNull(
                refund(
                                order,
                                50_000L,
                                new RefundEvidence(
                                        ExecutionChannel.PROVIDER_CONSOLE,
                                        "CLICK-REV-88213",
                                        "cashier-7",
                                        inTheCabinet))
                        .remedy(),
                "this refund needed no approval");

        assertThat(remedy.verificationState())
                .as("nothing in this build can corroborate it: ADR 0013's settlement import does "
                        + "not exist, so unverified is the resting state and not a step")
                .isEqualTo(VerificationState.UNVERIFIED);
        assertThat(remedy.executedBy()).isEqualTo("cashier-7");
        assertThat(remedy.executedAt()).isEqualTo(inTheCabinet);
        assertThat(remedy.recordedBy())
                .as("the person who says they did it and the person who recorded it are separate "
                        + "columns, because during an investigation they are separate questions")
                .isEqualTo("support-1");
        assertThat(remedy.recordedAt()).isEqualTo(NOW);
        assertThat(remedy.providerReference()).isEqualTo("CLICK-REV-88213");
    }

    @Test
    @DisplayName("money the platform did not move cannot be recorded without who moved it")
    void anAttestationWithoutAClaimantIsRefused() {
        UUID order = settledSplitOrder("A-3", 100_000L, 4_000L);

        assertThatThrownBy(() -> refund(order, 50_000L, new RefundEvidence(null, null, null, null)))
                .as("a ledger line asserting money left a merchant account, with nobody attached "
                        + "to the assertion, is the shape this whole design exists to avoid")
                .isInstanceOf(ApiException.class);

        assertThat(remedyStore.remediesOfOrder(TENANT, order)).isEmpty();
    }

    @Test
    @DisplayName("a refund made in a provider cabinet is refused without the reference the " + "cabinet showed")
    void aConsoleRefundWithoutItsReferenceIsRefused() {
        UUID order = settledSplitOrder("A-4", 100_000L, 4_000L);

        assertThatThrownBy(() -> refund(
                        order, 50_000L, new RefundEvidence(ExecutionChannel.PROVIDER_CONSOLE, "  ", "cashier-7", NOW)))
                .as("without it nothing can ever match a settlement line, which is the only way "
                        + "the assertion is ever discharged")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a refund that came back entirely as points needs no cabinet reference")
    void aPlatformSettledRefundIsNotAskedForEvidenceItCannotHave() {
        UUID order = settledSplitOrder("A-5", 100_000L, 4_000L);

        // Money first, points last: the money tender is exhausted by the first
        // call, so the second reaches only the points.
        refund(order, 96_000L, consoleRefund());
        RemedyRow onlyPoints = Objects.requireNonNull(
                refund(order, 4_000L, new RefundEvidence(null, null, null, null))
                        .remedy(),
                "this refund needed no approval");

        assertThat(onlyPoints.settlementBasis()).isEqualTo(SettlementBasis.PLATFORM_SETTLED);
        assertThat(onlyPoints.attestedMoneyMinor()).isZero();
        assertThat(onlyPoints.executedBy())
                .as("there was no cabinet and no operator; a required field here is a field "
                        + "somebody fills in with a plausible string")
                .isNull();
    }

    @Test
    @DisplayName("the unverified worklist is the gap, and a verification takes a row off it")
    void unverifiedAttestationsAreQueryableAndDischargeable() {
        UUID order = settledSplitOrder("A-6", 100_000L, 4_000L);
        RemedyRow remedy = Objects.requireNonNull(
                refund(order, 96_000L, consoleRefund()).remedy(), "this refund needed no approval");

        assertThat(remedies.unverifiedAttestations(TENANT, Duration.ofHours(24), 50))
                .as("recorded a moment ago, it has had no chance to appear in a settlement file")
                .isEmpty();

        clock.advance(Duration.ofDays(2));
        assertThat(remedies.unverifiedAttestations(TENANT, Duration.ofHours(24), 50))
                .extracting(RemedyRow::id)
                .containsExactly(remedy.id());

        boolean recorded = transactions.execute(status -> remedies.recordVerification(
                TENANT,
                remedy.id(),
                VerificationState.CONFIRMED,
                "click-settlement-2026-08-25",
                ActorRef.user("finance-1", null),
                "Matched the settlement line",
                null));
        assertThat(recorded).isTrue();
        assertThat(remedies.unverifiedAttestations(TENANT, Duration.ofHours(24), 50))
                .isEmpty();

        Boolean second = transactions.execute(status -> remedies.recordVerification(
                TENANT,
                remedy.id(),
                VerificationState.DISPUTED,
                "second-look",
                ActorRef.user("finance-2", null),
                "Looking again",
                null));
        assertThat(second)
                .as("a second reconciliation run must not overwrite what the first one found")
                .isFalse();
    }

    // ------------------------------------------------------------- the cap

    @Test
    @DisplayName("cumulative partial refunds cannot exceed what the tenders settled, and the "
            + "refused one records nothing")
    void theCumulativeCapIsTheSettlementServicesAndIsNotReimplemented() {
        UUID order = settledSplitOrder("B-1", 100_000L, 4_000L);

        refund(order, 60_000L, consoleRefund());
        assertThatThrownBy(() -> refund(order, 60_000L, consoleRefund()))
                .as("36 000 of money and 4 000 of points are left, not another 60 000")
                .isInstanceOf(ApiException.class);

        assertThat(remedyStore.remediesOfOrder(TENANT, order))
                .as("a refused refund leaves no remedy behind to be reconciled against nothing")
                .hasSize(1);
        assertThat(remedyStore.moneyRemediedMinor(TENANT, order)).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("a remedy against another tenant's order is not found rather than refused")
    void anOrderIsNeverReachedByItsIdAlone() {
        UUID order = settledSplitOrder("B-2", 100_000L, 4_000L);

        assertThatThrownBy(() -> transactions.execute(status -> remedies.recordRefund(new RefundCommand(
                        OTHER_TENANT,
                        order,
                        10_000L,
                        "UZS",
                        "GOODWILL",
                        "Cold food",
                        ExecutionChannel.CASH_DRAWER,
                        null,
                        "cashier-7",
                        NOW,
                        ActorRef.user("support-1", null),
                        "k-cross",
                        null))))
                .as("keyed on the order id alone this would be a cross-tenant write")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No such order");
    }

    // -------------------------------------------------------- maker-checker

    @Test
    @DisplayName("a refund past the threshold waits for a second approver and moves nothing")
    void anOverThresholdRefundIsPendingAndWritesNothing() {
        UUID order = settledSplitOrder("C-1", 400_000L, 4_000L);
        approvals.answer = new ApprovalOutcome.Pending(UUID.randomUUID());

        RemedyOutcome outcome = refund(order, 300_000L, consoleRefund());

        assertThat(outcome.recorded()).isFalse();
        assertThat(outcome.approval()).isInstanceOf(ApprovalOutcome.Pending.class);
        assertThat(remedyStore.remediesOfOrder(TENANT, order)).isEmpty();
        assertThat(points.reversed)
                .as("nothing was returned while the request waits, so a declined approval has " + "nothing to undo")
                .isEmpty();
        assertThat(refundedOn(order))
                .as("and the tender's cumulative cap is untouched")
                .isZero();
    }

    @Test
    @DisplayName("the threshold is aggregate over the order: two small refunds sum past it")
    void repeatedSmallRefundsCannotWalkAroundTheThreshold() {
        UUID order = settledSplitOrder("C-2", 400_000L, 4_000L);

        RemedyOutcome first = refund(order, 150_000L, consoleRefund());
        assertThat(first.recorded()).isTrue();
        assertThat(approvals.requests).isEmpty();

        approvals.answer = new ApprovalOutcome.Pending(UUID.randomUUID());
        RemedyOutcome second = refund(order, 100_000L, consoleRefund());

        assertThat(second.recorded())
                .as("150 000 and 100 000 in one afternoon are the 250 000 refund the operator "
                        + "was not allowed to make in one go")
                .isFalse();
        assertThat(approvals.requests).hasSize(1);
    }

    @Test
    @DisplayName("an approval is bound to the amount and the remedy type it approved")
    void anApprovalCannotBeReusedForADifferentRemedy() {
        UUID order = settledSplitOrder("C-3", 400_000L, 4_000L);
        approvals.answer = new ApprovalOutcome.Pending(UUID.randomUUID());

        refund(order, 300_000L, consoleRefund());
        deliveryFees.fee = OptionalLong.of(300_000L);
        transactions.execute(
                status -> remedies.recordDeliveryFeeReimbursement(command(order, 300_000L, consoleRefund(), "k-fee")));

        assertThat(approvals.requests).hasSize(2);
        assertThat(approvals.requests.get(0).parametersHash())
                .as("an approval for a 300 000 refund must not be reusable as a 300 000 delivery "
                        + "reimbursement on the same order")
                .isNotEqualTo(approvals.requests.get(1).parametersHash());
    }

    // --------------------------------------------------- delivery fee remedy

    @Test
    @DisplayName("a delivery-fee reimbursement is its own kind and reporting keeps it apart")
    void reportingDoesNotConflateFeeReimbursementWithARefundOfGoods() {
        UUID order = settledSplitOrder("D-1", 100_000L, 4_000L);
        deliveryFees.fee = OptionalLong.of(12_000L);

        refund(order, 30_000L, consoleRefund());
        transactions.execute(
                status -> remedies.recordDeliveryFeeReimbursement(command(order, 12_000L, consoleRefund(), "k-fee")));

        List<RemedyTotals> totals =
                remedies.totalsByType(TENANT, NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1)));

        assertThat(totals)
                .as("two lines, never one: a tenant asking what late delivery cost them is not "
                        + "asking about the refunds beside it")
                .hasSize(2);
        assertThat(totals).anySatisfy(line -> {
            assertThat(line.remedyType()).isEqualTo(RemedyType.ORDER_REFUND);
            assertThat(line.amountMinor()).isEqualTo(30_000L);
        });
        assertThat(totals).anySatisfy(line -> {
            assertThat(line.remedyType()).isEqualTo(RemedyType.DELIVERY_FEE_REIMBURSEMENT);
            assertThat(line.amountMinor()).isEqualTo(12_000L);
            assertThat(line.unverifiedMinor())
                    .as("and within the line, the money nothing has corroborated is its own " + "figure")
                    .isEqualTo(12_000L);
        });
    }

    @Test
    @DisplayName("a reimbursement cannot exceed the delivery fee actually charged")
    void theFeeCeilingHoldsWhenThePlatformCanEstablishIt() {
        UUID order = settledSplitOrder("D-2", 100_000L, 4_000L);
        deliveryFees.fee = OptionalLong.of(12_000L);

        transactions.execute(
                status -> remedies.recordDeliveryFeeReimbursement(command(order, 8_000L, consoleRefund(), "k-fee-1")));

        assertThatThrownBy(() -> transactions.execute(status ->
                        remedies.recordDeliveryFeeReimbursement(command(order, 8_000L, consoleRefund(), "k-fee-2"))))
                .as("16 000 reimbursed against a 12 000 fee is a refund of the food wearing a " + "delivery label")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a reimbursement recorded with no fee ceiling available says so on the row")
    void anUncheckedFeeCeilingIsRecordedRatherThanAssumed() {
        UUID order = settledSplitOrder("D-3", 100_000L, 4_000L);
        deliveryFees.fee = OptionalLong.empty();

        RemedyRow remedy = Objects.requireNonNull(
                transactions
                        .execute(status -> remedies.recordDeliveryFeeReimbursement(
                                command(order, 8_000L, consoleRefund(), "k-fee")))
                        .remedy(),
                "this reimbursement needed no approval");

        assertThat(remedy.deliveryFeeBasisMinor())
                .as("null is not zero: zero would mean free delivery, and this row means nobody "
                        + "could tell us what the fee was")
                .isNull();
        assertThat(remedy.amountMinor())
                .as("the tender cap still bounded it, so the remedy is recorded rather than " + "refused")
                .isEqualTo(8_000L);
    }

    // --------------------------------------------------- future discounts

    @Test
    @DisplayName("a future discount grants uses, costs nothing today, and carries no money " + "columns to be summed")
    void aFutureDiscountIsNotMoney() {
        UUID order = settledSplitOrder("E-1", 100_000L, 4_000L);

        RemedyRow remedy =
                Objects.requireNonNull(grantThreeFreeDeliveries(order).remedy(), "this grant needed no approval");

        assertThat(remedy.remedyType()).isEqualTo(RemedyType.FUTURE_DISCOUNT);
        assertThat(remedy.amountMinor()).isZero();
        assertThat(remedy.settlementBasis()).isEqualTo(SettlementBasis.NOT_MONEY);
        assertThat(refundedOn(order))
                .as("nothing was returned, so the tender cap is untouched")
                .isZero();

        var granted = entitlements.available(TENANT, BRAND, customerId, NOW);
        assertThat(granted).hasSize(1);
        assertThat(granted.get(0).usesRemaining()).isEqualTo(3);
        assertThat(granted.get(0).appliesTo()).isEqualTo(EntitlementScope.DELIVERY_FEE);
    }

    @Test
    @DisplayName("one use is one order: a retried redemption of the same order spends nothing " + "more")
    void aRedemptionIsIdempotentPerOrder() {
        UUID granting = settledSplitOrder("E-2", 100_000L, 4_000L);
        UUID entitlementId = entitlementOf(
                Objects.requireNonNull(grantThreeFreeDeliveries(granting).remedy(), "this grant needed no approval")
                        .id());
        UUID next = order("E-3", 60_000L, 12_000L, customerId);

        RedemptionOutcome first = transactions.execute(status ->
                entitlements.redeem(new RedeemCommand(TENANT, entitlementId, customerId, next, 0L, 10_000L, "UZS")));
        RedemptionOutcome retry = transactions.execute(status ->
                entitlements.redeem(new RedeemCommand(TENANT, entitlementId, customerId, next, 0L, 10_000L, "UZS")));

        assertThat(first.redeemed()).isTrue();
        assertThat(first.usesRemaining()).isEqualTo(2);
        assertThat(retry.redeemed()).isTrue();
        assertThat(retry.usesRemaining())
                .as("the unique index on (entitlement, order) is what makes a retried placement a "
                        + "retry instead of a second use")
                .isEqualTo(2);
        assertThat(remedyStore.redemptionsOf(TENANT, entitlementId)).hasSize(1);
    }

    @Test
    @DisplayName("uses run out, and the last one closes the grant")
    void anEntitlementIsExhaustedByItsGrantedUses() {
        UUID granting = settledSplitOrder("E-4", 100_000L, 4_000L);
        UUID entitlementId = entitlementOf(
                Objects.requireNonNull(grantThreeFreeDeliveries(granting).remedy(), "this grant needed no approval")
                        .id());

        for (int use = 1; use <= 3; use++) {
            UUID next = order("E-4-" + use, 60_000L, 12_000L, customerId);
            assertThat(transactions
                            .execute(status -> entitlements.redeem(
                                    new RedeemCommand(TENANT, entitlementId, customerId, next, 0L, 5_000L, "UZS")))
                            .redeemed())
                    .isTrue();
        }

        UUID fourth = order("E-4-4", 60_000L, 12_000L, customerId);
        RedemptionOutcome refused = transactions.execute(status ->
                entitlements.redeem(new RedeemCommand(TENANT, entitlementId, customerId, fourth, 0L, 5_000L, "UZS")));

        assertThat(refused.redeemed()).isFalse();
        assertThat(refused.refusalCode()).isEqualTo(EntitlementStatus.EXHAUSTED.name());
        assertThat(remedyStore
                        .findEntitlement(TENANT, entitlementId)
                        .orElseThrow()
                        .status())
                .isEqualTo(EntitlementStatus.EXHAUSTED);
    }

    @Test
    @DisplayName("a grant refuses to be spent outside what it applies to, past its per-use "
            + "maximum, by another customer, or after it expires")
    void theBoundsOfAGrantAreEnforcedWhereTheMoneyIsTaken() {
        UUID granting = settledSplitOrder("E-5", 100_000L, 4_000L);
        UUID entitlementId = entitlementOf(
                Objects.requireNonNull(grantThreeFreeDeliveries(granting).remedy(), "this grant needed no approval")
                        .id());
        UUID next = order("E-6", 60_000L, 12_000L, customerId);

        assertThat(transactions
                        .execute(status -> entitlements.redeem(
                                new RedeemCommand(TENANT, entitlementId, customerId, next, 5_000L, 0L, "UZS")))
                        .refusalCode())
                .as("a delivery-fee grant does not discount the food")
                .isEqualTo("SCOPE_NOT_COVERED");

        RedemptionOutcome overCap = transactions.execute(status ->
                entitlements.redeem(new RedeemCommand(TENANT, entitlementId, customerId, next, 0L, 20_000L, "UZS")));
        assertThat(overCap.refusalCode())
                .as("the per-use maximum is checked here, because a cap only the caller " + "enforces is not a cap")
                .isEqualTo("EXCEEDS_MAXIMUM");
        assertThat(overCap.usesRemaining())
                .as("a refusal about the amount spends nothing: reporting zero here would tell "
                        + "pricing to stop offering a grant nothing had touched")
                .isEqualTo(3);

        UUID strangersOrder = order("E-7", 60_000L, 12_000L, otherCustomerId);
        RedemptionOutcome stranger = transactions.execute(status -> entitlements.redeem(
                new RedeemCommand(TENANT, entitlementId, otherCustomerId, strangersOrder, 0L, 5_000L, "UZS")));
        assertThat(stranger.refusalCode())
                .as("an entitlement is one brand's promise to one person, and answering anything "
                        + "else to a stranger is an enumeration oracle")
                .isEqualTo("NOT_FOUND");

        clock.advance(Duration.ofDays(31));
        assertThat(transactions
                        .execute(status -> entitlements.redeem(
                                new RedeemCommand(TENANT, entitlementId, customerId, next, 0L, 5_000L, "UZS")))
                        .refusalCode())
                .isEqualTo("OUTSIDE_WINDOW");
        assertThat(entitlements.available(TENANT, BRAND, customerId, clock.instant()))
                .isEmpty();
    }

    @Test
    @DisplayName("a percentage grant without a per-use maximum is refused")
    void anUncappedPercentageIsRefused() {
        UUID order = settledSplitOrder("E-8", 100_000L, 4_000L);

        assertThatThrownBy(() -> transactions.execute(status -> remedies.grantFutureDiscount(new FutureDiscountCommand(
                        TENANT,
                        order,
                        EntitlementScope.BOTH,
                        EntitlementBenefit.PERCENT,
                        2_000,
                        null,
                        null,
                        3,
                        Duration.ofDays(30),
                        "SERVICE_FAILURE",
                        "Very late",
                        ActorRef.user("support-1", null),
                        "k-uncapped",
                        null))))
                .as("20% off is 2 000 som on a delivery fee and 400 000 on a catering order, and "
                        + "the person apologising for a cold pizza meant the first")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the exposure of a grant is what the approver weighs, not its zero cost today")
    void aLargeGrantNeedsASecondApprover() {
        UUID order = settledSplitOrder("E-9", 100_000L, 4_000L);
        approvals.answer = new ApprovalOutcome.Pending(UUID.randomUUID());

        // Ten uses worth 30 000 each: 300 000 of liability from one console click.
        RemedyOutcome outcome = transactions.execute(status -> remedies.grantFutureDiscount(new FutureDiscountCommand(
                TENANT,
                order,
                EntitlementScope.BOTH,
                EntitlementBenefit.FIXED_AMOUNT,
                null,
                30_000L,
                null,
                10,
                Duration.ofDays(30),
                "SERVICE_FAILURE",
                "Very late",
                ActorRef.user("support-1", null),
                "k-big",
                null)));

        assertThat(outcome.recorded()).isFalse();
        assertThat(remedyStore.spendableEntitlements(TENANT, BRAND, customerId, NOW))
                .isEmpty();
    }

    @Test
    @DisplayName("a future discount cannot be granted on a guest order")
    void aGuestOrderHasNobodyToGrantTo() {
        UUID guestOrder = order("E-10", 60_000L, 12_000L, null);

        assertThatThrownBy(() -> transactions.execute(status -> remedies.grantFutureDiscount(new FutureDiscountCommand(
                        TENANT,
                        guestOrder,
                        EntitlementScope.BOTH,
                        EntitlementBenefit.FIXED_AMOUNT,
                        null,
                        5_000L,
                        null,
                        1,
                        Duration.ofDays(30),
                        "SERVICE_FAILURE",
                        "Late",
                        ActorRef.user("support-1", null),
                        "k-guest",
                        null))))
                .as("inventing an identity here is how a remedy becomes spendable by whoever " + "next uses the device")
                .isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------- evidence

    @Test
    @DisplayName("every remedy writes an audit fact naming the split it recorded")
    void aRemedyIsAuditEvidence() {
        UUID order = settledSplitOrder("F-1", 100_000L, 4_000L);
        refund(order, 100_000L, consoleRefund());

        assertThat(audit.facts).hasSize(1);
        AuditFact fact = audit.facts.get(0);
        assertThat(fact.actionCode()).isEqualTo("payments.remedy.record");
        assertThat(fact.changeDocument())
                .containsEntry("attestedMoneyMinor", 96_000L)
                .containsEntry("platformSettledMinor", 4_000L)
                .containsEntry("settlementBasis", SettlementBasis.MIXED.name());
    }

    // ------------------------------------ money that arrives after the order

    /**
     * A cancelled {@code PAYMENT_AUTHORIZING} order whose payment lands anyway.
     *
     * <p>The sequence is ordinary and the outcome was not. An operator cancels an
     * order whose customer is still on Payme's page; ordering calls
     * {@code recordTerminalOutcome}, which fails the settlement and drives the
     * money tender {@code PLANNED -> FAILED}. Nothing in {@code payments} listened
     * for the order ending and neither provider offers a void for an uncaptured
     * transaction, so the customer's redirect completes inside its twelve-hour
     * window and captures. Real money, in the tenant's account, against an order
     * the platform had written off.
     *
     * <p>Before the fix that capture reached nothing: {@code recordConfirmation}
     * would never fire again for an order that will never confirm, the tender
     * stayed {@code FAILED}, and every refund answered "a refund cannot exceed
     * what the tenders settled". The money was the customer's and there was no
     * path to give it back.
     */
    @Test
    @DisplayName(
            "a payment that lands after the order was cancelled leaves the money " + "refundable rather than stranded")
    void aCaptureThatArrivesAfterTheOrderEndedIsStillRefundable() {
        UUID order = plannedSplitOrder("G-1", 100_000L, 4_000L);

        // ordering's OrderStateService on any terminal status but COMPLETED.
        transactions.executeWithoutResult(
                status -> planner.recordTerminalOutcome(TENANT, order, "CUSTOMER_UNREACHABLE", "support-1"));

        assertThat(settlementStore.findSettlement(TENANT, order).orElseThrow().status())
                .as("the platform has given up on the money, which is a statement about its "
                        + "own expectation and not about Payme's behaviour")
                .isEqualTo(SettlementStatus.FAILED);

        // The redirect the customer completed an hour later. In production this
        // arrives through PaymentAttemptService.applyToIntent on a CAPTURED
        // attempt; called directly here because this suite wires no context.
        transactions.executeWithoutResult(status -> planner.recordCapture(TENANT, order, "payment-capture"));

        var settlement = settlementStore.findSettlement(TENANT, order).orElseThrow();
        assertThat(settlement.settledMinor())
                .as("settled_minor names exactly the money the platform has, and it has the "
                        + "96 000 the card captured")
                .isEqualTo(96_000L);
        assertThat(settlement.status())
                .as("and it says short rather than SETTLED, because the 4 000 points leg was "
                        + "released when the order ended and is the customer's again")
                .isEqualTo(SettlementStatus.PARTIALLY_SETTLED);

        RemedyOutcome outcome = refund(order, 96_000L, consoleRefund());
        assertThat(outcome.recorded())
                .as("the whole point: the customer's money can be given back")
                .isTrue();
        assertThat(Objects.requireNonNull(outcome.remedy(), "just asserted recorded()")
                        .attestedMoneyMinor())
                .isEqualTo(96_000L);
        assertThat(points.reversed)
                .as("and no points are returned, because they were already released; refunding "
                        + "them here would pay the customer twice for one leg")
                .isEmpty();
    }

    @Test
    @DisplayName("a late capture cannot resurrect a released balance leg, and the refund "
            + "ceiling is the money that actually arrived")
    void aLateCaptureSettlesOnlyTheMoneyThatArrived() {
        UUID order = plannedSplitOrder("G-2", 100_000L, 4_000L);
        transactions.executeWithoutResult(status -> planner.recordTerminalOutcome(TENANT, order, "EXPIRED", "system"));
        transactions.executeWithoutResult(status -> planner.recordCapture(TENANT, order, "payment-capture"));

        UUID settlementId =
                settlementStore.findSettlement(TENANT, order).orElseThrow().id();
        assertThat(settlementStore.tendersOf(TENANT, settlementId))
                .filteredOn(uz.horecaos.platform.payments.settlement.JdbcSettlementStore.TenderRow::settlesFromBalance)
                .allSatisfy(tender -> assertThat(tender.status())
                        .as("points given back to a customer are theirs; settling them out of "
                                + "RELEASED would spend a hold that no longer exists")
                        .isEqualTo(TenderStatus.RELEASED));
        assertThat(settlementStore.tendersOf(TENANT, settlementId))
                .filteredOn(tender -> !tender.settlesFromBalance())
                .allSatisfy(tender -> assertThat(tender.status())
                        .as("the money leg took the capture out of FAILED, which is the only "
                                + "status from which a refund can reach it")
                        .isEqualTo(TenderStatus.SETTLED));

        assertThatThrownBy(() -> refund(order, 100_000L, consoleRefund()))
                .as("96 000 arrived and 96 000 is the ceiling, not the order total")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot exceed what the tenders settled");
    }

    // ------------------------------------------- orders with nothing to settle

    /**
     * The zero-paid aggregator order, at the level the remedy is decided.
     *
     * <p>An aggregator push the customer paid nothing for now plans no settlement
     * at all — see {@code MarketplaceIngestionService}. This asserts what that
     * means where an operator is standing: no cash refund, because there is no
     * money on the order to give back, and a goodwill remedy exactly as before,
     * because {@code grantFutureDiscount} makes no settlement call and never did.
     *
     * <p>The order carries a customer account here, which a real aggregator order
     * does not. That is deliberate and is the honest boundary of this test: the
     * settlement layer is what this fix changed, and the separate rule that a
     * guest order has nobody to grant an entitlement to is untouched and still
     * refuses. See {@link #aGuestOrderHasNobodyToGrantTo}.
     */
    @Test
    @DisplayName("an order the customer paid nothing for can take a goodwill remedy and " + "cannot take a cash refund")
    void aZeroPaidOrderIsRemediedWithGoodwillAndNeverWithCash() {
        // Zero total, zero fee: the shape a hundred-percent-off aggregator push
        // writes, and no settlement, because nothing is owed and there is nothing
        // to tender against.
        UUID order = order("H-1", 0L, 0L, customerId);

        assertThat(settlementStore.findSettlement(TENANT, order)).isEmpty();

        assertThatThrownBy(() -> refund(order, 1L, consoleRefund()))
                .as("one som of cash to a customer who paid none is the defect, and the "
                        + "promotion's value was a goodwill ceiling being read as a cash one")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no settlement");
        assertThat(remedyStore.remediesOfOrder(TENANT, order))
                .as("and the refused refund leaves nothing behind to be reconciled against " + "nothing")
                .isEmpty();

        RemedyOutcome goodwill = grantThreeFreeDeliveries(order);
        assertThat(goodwill.recorded())
                .as("the apology an operator actually owes a customer whose free meal was cold "
                        + "costs the tenant nothing today and is not bounded by a tender")
                .isTrue();
        RemedyRow goodwillRemedy = Objects.requireNonNull(goodwill.remedy(), "just asserted recorded()");
        assertThat(goodwillRemedy.settlementBasis()).isEqualTo(SettlementBasis.NOT_MONEY);
        assertThat(goodwillRemedy.amountMinor())
                .as("carrying no money columns at all, it cannot be summed into a refund figure "
                        + "by a query that forgot to filter")
                .isZero();
    }

    /**
     * The two order shapes that reach the end of their life with no settlement.
     *
     * <p>Neither is hypothetical. An order placed before the settlement seam
     * existed has none, and one naming a payment method this build cannot tender
     * against is refused a settlement on purpose — {@code CheckoutSettlementPlanner}
     * answers empty rather than fabricating a cash tender nobody agreed to. Both
     * then reach {@code recordHandover} or {@code recordTerminalOutcome}, and if
     * either refused, an operator's completion or cancellation would become an
     * error they cannot clear.
     */
    @Test
    @DisplayName("an order with no settlement completes, cancels and refuses a refund, and "
            + "none of the three is an error in the wrong place")
    void anOrderWithNoSettlementEndsCleanly() {
        UUID order = order("H-2", 60_000L, 12_000L, customerId);

        transactions.executeWithoutResult(status -> planner.recordHandover(TENANT, order, "counter-1"));
        transactions.executeWithoutResult(
                status -> planner.recordTerminalOutcome(TENANT, order, "CANCELLED", "support-1"));

        assertThat(settlementStore.findSettlement(TENANT, order))
                .as("neither call invented one")
                .isEmpty();
        assertThatThrownBy(() -> refund(order, 1_000L, consoleRefund()))
                .as("and the money question is refused where it is asked, with the reason")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no settlement");
    }

    // ------------------------------------------------- the books, as one sum

    /**
     * Every settlement is for exactly what its order says it is worth.
     *
     * <p>Asserted over the shapes this suite produces rather than argued about,
     * because a settlement whose total disagrees with the order's is a refund
     * ceiling that disagrees with what the customer paid — and which way it
     * disagrees decides whether the tenant loses money or the customer does.
     */
    @Test
    @DisplayName("a settlement's total, its tenders and its order all agree")
    void theBooksBalanceOnEveryOrderThatHasASettlement() {
        settledSplitOrder("I-1", 100_000L, 4_000L);
        plannedSplitOrder("I-2", 80_000L, 5_000L);
        UUID cancelled = plannedSplitOrder("I-3", 70_000L, 1_000L);
        transactions.executeWithoutResult(
                status -> planner.recordTerminalOutcome(TENANT, cancelled, "CANCELLED", "support-1"));
        UUID late = plannedSplitOrder("I-4", 90_000L, 2_000L);
        transactions.executeWithoutResult(
                status -> planner.recordTerminalOutcome(TENANT, late, "CANCELLED", "support-1"));
        transactions.executeWithoutResult(status -> planner.recordCapture(TENANT, late, "payment-capture"));

        assertThat(jdbc.sql("""
                SELECT o.public_order_number
                  FROM ordering.orders o
                  JOIN payments.order_settlements s
                    ON s.order_id = o.id AND s.tenant_id = o.tenant_id
                 WHERE o.tenant_id = :tenantId AND s.total_due_minor <> o.total_minor
                """).param("tenantId", TENANT).query(String.class).list())
                .as("the settlement total is the order total, on every path an order can take")
                .isEmpty();

        assertThat(jdbc.sql("""
                SELECT s.id FROM payments.order_settlements s
                 WHERE s.tenant_id = :tenantId
                   AND s.total_due_minor <> (
                       SELECT COALESCE(SUM(t.amount_minor), 0) FROM payments.tenders t
                        WHERE t.settlement_id = s.id AND t.tenant_id = s.tenant_id)
                """).param("tenantId", TENANT).query(UUID.class).list())
                .as("and the tenders sum to it, which is what makes the refund ceiling and the "
                        + "order total the same number")
                .isEmpty();

        assertThat(jdbc.sql("""
                SELECT s.id FROM payments.order_settlements s
                 WHERE s.tenant_id = :tenantId
                   AND s.settled_minor <> (
                       SELECT COALESCE(SUM(t.amount_minor), 0) FROM payments.tenders t
                        WHERE t.settlement_id = s.id AND t.tenant_id = s.tenant_id
                          AND t.status IN ('SETTLED', 'REVERSED'))
                """).param("tenantId", TENANT).query(UUID.class).list())
                .as("settled_minor is the tenders that actually settled and nothing else, "
                        + "including on the settlement a late capture reopened")
                .isEmpty();
    }

    // --------------------------------------------------------------- helpers

    private record RefundEvidence(
            @Nullable ExecutionChannel channel,
            @Nullable String providerReference,
            @Nullable String executedBy,
            @Nullable Instant executedAt) {}

    private static RefundEvidence consoleRefund() {
        return new RefundEvidence(ExecutionChannel.PROVIDER_CONSOLE, "CLICK-REV-1", "cashier-7", NOW);
    }

    private RemedyOutcome refund(UUID orderId, long amountMinor, RefundEvidence evidence) {
        return transactions.execute(
                status -> remedies.recordRefund(command(orderId, amountMinor, evidence, "k-" + UUID.randomUUID())));
    }

    private RefundCommand command(UUID orderId, long amountMinor, RefundEvidence evidence, String idempotencyKey) {
        return new RefundCommand(
                TENANT,
                orderId,
                amountMinor,
                "UZS",
                "GOODWILL",
                "Cold food, customer called",
                evidence.channel(),
                evidence.providerReference(),
                evidence.executedBy(),
                evidence.executedAt(),
                ActorRef.user("support-1", null),
                idempotencyKey,
                null);
    }

    private RemedyOutcome grantThreeFreeDeliveries(UUID orderId) {
        return transactions.execute(status -> remedies.grantFutureDiscount(new FutureDiscountCommand(
                TENANT,
                orderId,
                EntitlementScope.DELIVERY_FEE,
                EntitlementBenefit.FIXED_AMOUNT,
                null,
                12_000L,
                null,
                3,
                Duration.ofDays(30),
                "SERVICE_FAILURE",
                "Ninety minutes late",
                ActorRef.user("support-1", null),
                "k-" + UUID.randomUUID(),
                null)));
    }

    private UUID entitlementOf(UUID remedyId) {
        return jdbc.sql("SELECT id FROM payments.remedy_entitlements "
                        + "WHERE tenant_id = :tenantId AND remedy_id = :remedyId")
                .param("tenantId", TENANT)
                .param("remedyId", remedyId)
                .query(UUID.class)
                .single();
    }

    /** What the money tender of this order has already given back. */
    private long refundedOn(UUID orderId) {
        Long refunded = jdbc.sql("""
                SELECT COALESCE(SUM(t.refunded_minor), 0)
                  FROM payments.tenders t
                  JOIN payments.order_settlements s ON s.id = t.settlement_id
                 WHERE s.tenant_id = :tenantId AND s.order_id = :orderId
                """)
                .param("tenantId", TENANT)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        return refunded == null ? 0L : refunded;
    }

    /** An order settled by one points tender and one card tender, both settled. */
    private UUID settledSplitOrder(String number, long totalMinor, long pointsMinor) {
        UUID orderId = order(number, totalMinor, 12_000L, customerId);
        transactions.execute(status -> settlements.plan(new SettlementPlan(
                TENANT,
                BRAND,
                orderId,
                customerId,
                "UZS",
                totalMinor,
                List.of(
                        new PlannedTender(pointsMethod, pointsMinor),
                        new PlannedTender(clickMethod, totalMinor - pointsMinor)),
                "plan-" + orderId,
                "test")));

        UUID settlementId =
                settlementStore.findSettlement(TENANT, orderId).orElseThrow().id();
        settlementStore
                .tendersOf(TENANT, settlementId)
                .forEach(tender -> transactions.executeWithoutResult(
                        status -> settlements.recordTenderSettled(TENANT, orderId, tender.id(), "test")));
        return orderId;
    }

    /**
     * An order whose settlement is planned and whose money has not arrived: the
     * state a card order sits in while the customer is still on the provider's
     * page, which is where a cancellation catches it.
     */
    private UUID plannedSplitOrder(String number, long totalMinor, long pointsMinor) {
        UUID orderId = order(number, totalMinor, 12_000L, customerId);
        transactions.execute(status -> settlements.plan(new SettlementPlan(
                TENANT,
                BRAND,
                orderId,
                customerId,
                "UZS",
                totalMinor,
                List.of(
                        new PlannedTender(pointsMethod, pointsMinor),
                        new PlannedTender(clickMethod, totalMinor - pointsMinor)),
                "plan-" + orderId,
                "test")));
        return orderId;
    }

    private UUID order(String number, long totalMinor, long feeMinor, @Nullable UUID customer) {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        // ordering.orders carries exactly one of the two identities, so a guest
        // order needs its hash rather than two nulls.
        String guestHash = customer == null ? "guest-" + orderId : null;

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        :total, 0, :total, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", locationId)
                .param("publicationId", publicationId)
                .param("total", totalMinor)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, customer_account_id,
                    guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DELIVERY', 'UZS',
                        'ACTIVE', :customer, :guest, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", locationId)
                .param("channelId", channelId)
                .param("customer", customer)
                .param("guest", guestHash)
                .update();

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", orderId);
        parameters.put("number", number);
        parameters.put("tenantId", TENANT);
        parameters.put("brandId", BRAND);
        parameters.put("locationId", locationId);
        parameters.put("channelId", channelId);
        parameters.put("quoteId", quoteId);
        parameters.put("cartId", cartId);
        parameters.put("publicationId", publicationId);
        parameters.put("customer", customer);
        parameters.put("guest", guestHash);
        parameters.put("total", totalMinor);
        parameters.put("fee", feeMinor);
        parameters.put("subtotal", totalMinor - feeMinor);
        parameters.put("key", "idem-" + orderId);

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, customer_account_id,
                    guest_reference_hash, fulfillment_mode, acceptance_mode_snapshot,
                    acceptance_policy_id, acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor,
                    fee_minor, total_minor, pricing_quote_id, pricing_context_hash,
                    catalog_publication_id, cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'WEB',
                    :customer, :guest, 'DELIVERY', 'AUTO_CONFIRM', NULL, 0, 'NONE', NULL,
                    'CONFIRMED', 'UZS', :subtotal, 0, :fee, :total, :quoteId, 'hash',
                    :publicationId, :cartId, :key, 1, now())
                """).params(parameters).update();

        orders.register(new OrderDirectory.OrderSummary(
                orderId, TENANT, BRAND, locationId, number, customer, guestHash, "CONFIRMED", "UZS", totalMinor, 1));
        return orderId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'remedy-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        locationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
                VALUES (:id, :tenantId, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channelId).param("tenantId", TENANT).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();

        customerId = insertCustomer();
        otherCustomerId = insertCustomer();
    }

    private UUID insertCustomer() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status,
                    identity_policy_version, version)
                VALUES (:id, :tenantId, 'ACTIVE', 1, 1)
                """).param("id", id).param("tenantId", TENANT).update();
        return id;
    }

    private void seedPaymentMethods() {
        Instant now = clock.instant();
        clickMethod = settlementStore.registerMethod(TENANT, "CLICK", "Click", "PARTNER", false, now);
        pointsMethod = settlementStore.registerMethod(TENANT, "LOYALTY_POINTS", "Баллы", "OPERATOR", true, now);
    }

    // --------------------------------------------------------------- doubles

    /**
     * The loyalty ledger, reduced to what a settlement asks of it.
     *
     * <p>A double rather than the real service because the points ledger has its
     * own suite: what matters here is that the reversal happened inside the
     * platform, which is what makes that part of a refund provable.
     */
    private static final class RecordingPoints implements PointsRedemptionPort {

        private final List<Long> reversed = new ArrayList<>();

        @Override
        public RedemptionOffer quote(RedemptionQuery query) {
            return new RedemptionOffer(UUID.randomUUID(), 100_000L, 100_000L, query.currency(), null);
        }

        @Override
        public PointsHold reserve(ReserveCommand command) {
            return new PointsHold(UUID.randomUUID(), UUID.randomUUID(), command.amountMinor(), 0L, 1);
        }

        @Override
        public void settle(UUID tenantId, UUID tenderId) {}

        @Override
        public void release(UUID tenantId, UUID tenderId, String reasonCode, String actor) {}

        @Override
        public void reverse(UUID tenantId, UUID tenderId, long amountMinor, String reasonCode, String actor) {
            reversed.add(amountMinor);
        }
    }

    /** An order directory over the rows this fixture wrote, tenant predicate included. */
    private static final class StubOrders implements OrderDirectory {

        private final Map<UUID, OrderSummary> known = new HashMap<>();

        void register(OrderSummary summary) {
            known.put(summary.orderId(), summary);
        }

        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return Optional.ofNullable(known.get(orderId))
                    .filter(order -> order.tenantId().equals(tenantId));
        }
    }

    private static final class StubDeliveryFees implements DeliveryFeeBasisPort {

        private OptionalLong fee = OptionalLong.empty();

        @Override
        public OptionalLong deliveryFeeMinor(UUID tenantId, UUID orderId) {
            return fee;
        }
    }

    /** Approves by default; switched to pending for the threshold tests. */
    private static final class SwitchableApprovals implements ApprovalService {

        private final List<ApprovalRequestCommand> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger consumed = new AtomicInteger();
        private ApprovalOutcome answer =
                new ApprovalOutcome.Approved(UUID.randomUUID(), "checker-1", consumed::incrementAndGet);

        @Override
        public ApprovalOutcome requireApproval(ApprovalRequestCommand command) {
            requests.add(command);
            return answer;
        }

        @Override
        public void decide(UUID requestId, Decision decision, ActorRef approver, String reason) {}

        @Override
        public int expireOverdue() {
            return 0;
        }
    }

    private static final class RecordingAudit implements AuditRecorder {

        private final List<AuditFact> facts = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
