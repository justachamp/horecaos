package uz.horecaos.platform.courier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.api.BusinessDayWindows;
import uz.horecaos.platform.courier.application.AdjustmentRuleEvaluator;
import uz.horecaos.platform.courier.application.CourierAccrualService;
import uz.horecaos.platform.courier.application.CourierAdjustmentService;
import uz.horecaos.platform.courier.application.CourierEngagementService;
import uz.horecaos.platform.courier.application.CourierLedgerService;
import uz.horecaos.platform.courier.application.CourierPolicyResolver;
import uz.horecaos.platform.courier.application.CourierRateCardService;
import uz.horecaos.platform.courier.application.CourierShiftService;
import uz.horecaos.platform.courier.application.DeliveryAccrualOrderCompletionTrigger;
import uz.horecaos.platform.courier.application.port.LegalEntityResolver;
import uz.horecaos.platform.courier.domain.AccrualCalculator;
import uz.horecaos.platform.courier.domain.GateMode;
import uz.horecaos.platform.courier.domain.GateRefusal;
import uz.horecaos.platform.courier.domain.LedgerEntryType;
import uz.horecaos.platform.courier.domain.OnTimeOutcome;
import uz.horecaos.platform.courier.domain.RateCard;
import uz.horecaos.platform.courier.domain.RateComponent;
import uz.horecaos.platform.courier.domain.RateComponentType;
import uz.horecaos.platform.courier.domain.StepCheck;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.LedgerEntryRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStepCheckStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierTypeRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore;
import uz.horecaos.platform.fulfillment.infrastructure.sourcing.JdbcDeliveryCompletionAdapter;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.api.OrderCompleted;
import uz.horecaos.platform.payments.api.CashDueLookupPort;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore;
import uz.horecaos.platform.payments.settlement.OrderSettlementService;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.TestProtection;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * T11's root cause, before its reports (ADR 0042, ADR 0125): {@code
 * CourierAccrualService.recordDelivery} had no production caller. Against a
 * real PostgreSQL, for the reason {@code OrderCompletionAccrualTriggerTests}
 * already gives for loyalty's own trigger — whether a replay accrues twice is
 * a property of a real unique constraint, and whether the shipment actually
 * closes is a property of a real compare-and-set, neither of which a mock
 * stands in for.
 *
 * <p>Drives {@link DeliveryAccrualOrderCompletionTrigger#onOrderingEvent}
 * directly with a constructed {@link OrderCompleted}, the same shape {@code
 * OrderCompletionAccrualTriggerTests} uses for loyalty — {@code
 * OrderStateService}'s own suite already proves the event is published
 * correctly; this proves what happens once it is.
 */
class DeliveryAccrualOrderCompletionTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String UZS = "UZS";
    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCourierStore courierStore;
    private JdbcCourierLedgerStore ledgerStore;
    private JdbcCourierStepCheckStore stepChecks;
    private DeliveryAccrualOrderCompletionTrigger trigger;
    private FakeCashDueLookupPort cashDue;
    private JdbcDeliveryCompletionAdapter deliveryCompletion;
    private CourierAccrualService accruals;
    private CourierShiftService shifts;
    private Clock clock;

    private UUID branch;
    private UUID channelId;
    private UUID publicationId;
    private UUID courierId;
    private int chainSequence;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for courier tests");
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

        jdbc.sql("""
                TRUNCATE TABLE fulfillment.courier_ledger_entries,
                    fulfillment.courier_assignment_earnings,
                    fulfillment.courier_settlement_periods,
                    fulfillment.courier_engagements,
                    fulfillment.couriers,
                    fulfillment.courier_rate_components,
                    fulfillment.courier_rate_cards,
                    fulfillment.courier_types,
                    fulfillment.assignment_attempts,
                    fulfillment.shipments,
                    fulfillment.delivery_plans,
                    ordering.orders,
                    ordering.carts,
                    pricing.quotes,
                    catalog.publications,
                    catalog.catalogs,
                    tenant.sales_channels CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var protection = TestProtection.envelope();
        AuditRecorder audit = fact -> {};
        LegalEntityResolver legalEntities = (tenantId, locationId, businessDate) -> Optional.empty();
        var policyResolver = new CourierPolicyResolver(new PolicyResolver() {
            @Override
            public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
                return Optional.empty();
            }

            @Override
            public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
                throw new UnsupportedOperationException("not exercised by this suite");
            }
        });

        courierStore = new JdbcCourierStore(jdbc);
        var shiftStore = new JdbcCourierShiftStore(jdbc);
        ledgerStore = new JdbcCourierLedgerStore(jdbc);
        var rateCardStore = new JdbcCourierRateCardStore(jdbc);
        var costStore = new JdbcDeliveryCostStore(jdbc);

        var ledger = new CourierLedgerService(ledgerStore, courierStore, policyResolver, legalEntities, clock);
        var engagements = new CourierEngagementService(
                courierStore, protection, audit, policyResolver, (tenantId, assetIds) -> false, clock);
        var approvals = new ApprovalService() {
            @Override
            public ApprovalOutcome requireApproval(ApprovalRequestCommand command) {
                return new ApprovalOutcome.NotRequired();
            }

            @Override
            public void decide(UUID requestId, Decision decision, ActorRef approver, String reason) {
                throw new UnsupportedOperationException("not exercised by this suite");
            }

            @Override
            public int expireOverdue() {
                throw new UnsupportedOperationException("not exercised by this suite");
            }
        };
        var adjustments = new CourierAdjustmentService(courierStore, ledger, approvals, audit, policyResolver, clock);
        var adjustmentRules = new AdjustmentRuleEvaluator(courierStore, ledgerStore, adjustments);
        shifts = new CourierShiftService(
                shiftStore,
                courierStore,
                ledgerStore,
                rateCardStore,
                ledger,
                policyResolver,
                protection,
                audit,
                adjustmentRules,
                clock);
        accruals = new CourierAccrualService(
                ledgerStore,
                rateCardStore,
                shiftStore,
                courierStore,
                costStore,
                ledger,
                policyResolver,
                legalEntities,
                protection,
                new BusinessDayWindows() {
                    @Override
                    public LocalDate businessDateOf(UUID tenantId, Instant at) {
                        return at.atZone(ZoneId.of("Asia/Tashkent")).toLocalDate();
                    }

                    @Override
                    public Optional<LocalDate> lastClosedBusinessDate(UUID tenantId) {
                        return Optional.empty();
                    }
                });
        var rateCards = new CourierRateCardService(rateCardStore, audit, clock);
        deliveryCompletion = new JdbcDeliveryCompletionAdapter(jdbc);
        cashDue = new FakeCashDueLookupPort();

        stepChecks = new JdbcCourierStepCheckStore(jdbc);
        trigger = new DeliveryAccrualOrderCompletionTrigger(deliveryCompletion, accruals, shifts, cashDue, stepChecks);

        seedTenancy();
        seedCourier(engagements);
        seedRateCard(rateCards);
    }

    @Test
    @DisplayName("an order completed with an internal delivery accrues the courier and collects cash")
    void anInternalDeliveryAccruesOnOrderCompletion() {
        Fixture fixture = seedAssignedShipment("NOT_REQUIRED");

        trigger.onOrderingEvent(orderCompleted(fixture.orderId(), 45_000));

        Map<String, Object> shipment = shipmentRow(fixture.shipmentId());
        assertThat(shipment).containsEntry("status", "DELIVERED");
        assertThat(shipment.get("delivered_at")).isNotNull();

        Optional<JdbcCourierLedgerStore.EarningRow> earning =
                ledgerStore.findEarningByAttempt(TENANT, fixture.attemptId());
        assertThat(earning).isPresent();
        assertThat(earning.orElseThrow().courierId()).isEqualTo(courierId);
        // Gap map row 3.4b: an activated rate card must actually earn, against
        // its own ladder -- not merely a positive number. seedRateCard's card
        // is PER_ORDER 3_000 plus a single unbounded PER_KM_BAND at 2_000/km,
        // and seedAssignedShipment's plan is 4_200m: 3_000 + (4_200 * 2_000 /
        // 1_000) = 11_400, exactly.
        assertThat(earning.orElseThrow().totalMinor()).isEqualTo(11_400L);

        List<LedgerEntryRow> cashEntries = entriesOfType(LedgerEntryType.CASH_COLLECTED);
        assertThat(cashEntries).hasSize(1);
        // A CASH_COLLECTED entry is a liability the ledger owes back, hence negative.
        assertThat(cashEntries.getFirst().amountMinor()).isEqualTo(-45_000L);
    }

    @Test
    @DisplayName("a CASH order part-settled from the loyalty balance collects only the cash actually due")
    void aCashAndBalanceSplitCollectsOnlyTheCashLeg() {
        // H11: DeliveryAccrualOrderCompletionTrigger used to compute
        // cashToCollectMinor from the order's whole total, ignoring that a
        // split-tender order settles part of itself from the customer's
        // loyalty balance. A 94,000 order with 12,000 redeemed from points
        // leaves the courier owed only 82,000 in cash.
        Fixture fixture = seedAssignedShipment("NOT_REQUIRED");
        OrderCompleted event = orderCompleted(fixture.orderId(), 94_000);
        cashDue.set(fixture.orderId(), 82_000L);

        trigger.onOrderingEvent(event);

        List<LedgerEntryRow> cashEntries = entriesOfType(LedgerEntryType.CASH_COLLECTED);
        assertThat(cashEntries).hasSize(1);
        assertThat(cashEntries.getFirst().amountMinor())
                .as("the ledger must record the settlement's actual cash tender, not the order total")
                .isEqualTo(-82_000L);
    }

    @Test
    @DisplayName("a prepaid order accrues the courier but collects no cash")
    void aPrepaidOrderCollectsNoCash() {
        Fixture fixture = seedAssignedShipment("CAPTURED");

        trigger.onOrderingEvent(orderCompleted(fixture.orderId(), 45_000));

        assertThat(ledgerStore.findEarningByAttempt(TENANT, fixture.attemptId()))
                .isPresent();
        assertThat(entriesOfType(LedgerEntryType.CASH_COLLECTED)).isEmpty();
    }

    @Test
    @DisplayName("a replayed OrderCompleted accrues exactly once and closes the shipment exactly once")
    void aReplayedEventDoesNotDoubleAccrue() {
        Fixture fixture = seedAssignedShipment("NOT_REQUIRED");
        OrderCompleted event = orderCompleted(fixture.orderId(), 20_000);

        trigger.onOrderingEvent(event);
        trigger.onOrderingEvent(event);

        assertThat(ledgerStore.entriesOfCourier(TENANT, courierId, 500).stream()
                        .filter(entry -> entry.entryType() == LedgerEntryType.DELIVERY_EARNING)
                        .count())
                .isEqualTo(1);
        assertThat(entriesOfType(LedgerEntryType.CASH_COLLECTED)).hasSize(1);
    }

    @Test
    @DisplayName("an order with no shipment (pickup) is a safe no-op")
    void aPickupOrderIsANoOp() {
        UUID orderId = seedOrder(++chainSequence, "NOT_REQUIRED");

        trigger.onOrderingEvent(orderCompleted(orderId, 30_000));

        assertThat(ledgerStore.entriesOfCourier(TENANT, courierId, 500)).isEmpty();
    }

    @Test
    @DisplayName("a missing rate card never blocks the shipment from closing")
    void aMissingRateCardStillClosesTheShipment() {
        // No rate card for this courier type at this branch: archive the one
        // seedRateCard activated, matching "a tenant still mid-onboarding".
        jdbc.sql("UPDATE fulfillment.courier_rate_cards SET status = 'ARCHIVED' WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        Fixture fixture = seedAssignedShipment("NOT_REQUIRED");

        trigger.onOrderingEvent(orderCompleted(fixture.orderId(), 45_000));

        Map<String, Object> shipment = shipmentRow(fixture.shipmentId());
        assertThat(shipment).containsEntry("status", "DELIVERED");
        assertThat(ledgerStore.findEarningByAttempt(TENANT, fixture.attemptId()))
                .isEmpty();
    }

    @Test
    @DisplayName("a completed order with no settlement row falls back to the order total, not a silent zero")
    void aMissingSettlementRowFallsBackToTheOrderTotalNotASilentZero() {
        // 2026-09-21 audit (major): OrderSettlementService.cashDueMinor(UUID,
        // UUID) -- the CashDueLookupPort implementation -- used to catch its
        // own "no settlement" ApiException and answer a plain 0L. That meant
        // this trigger's catch(RuntimeException) fallback to
        // completed.totalMinor() (see the class javadoc) could never run for
        // the very case its comment names: "a settlement read this trigger
        // did not anticipate". Every other test in this suite drives the
        // trigger with FakeCashDueLookupPort, which never has a settlement
        // row to miss in the first place (see its own javadoc) -- this test
        // wires the REAL OrderSettlementService instead, against an order
        // with no payments.order_settlements row at all, exactly what
        // CheckoutSettlementPlanner leaves behind when it does not recognise
        // the checkout's payment method code.
        var realCashDue = new OrderSettlementService(new JdbcSettlementStore(jdbc), unsupportedPointsPort(), clock);
        var realTrigger = new DeliveryAccrualOrderCompletionTrigger(
                deliveryCompletion, accruals, shifts, realCashDue, stepChecks);

        Fixture fixture = seedAssignedShipment("NOT_REQUIRED");
        // Deliberately no row inserted into payments.order_settlements.

        realTrigger.onOrderingEvent(new OrderCompleted(
                UUID.randomUUID(), new TenantId(TENANT), fixture.orderId(), NOW, BRAND, branch, NOW, UZS, 45_000L, 1));

        List<LedgerEntryRow> cashEntries = entriesOfType(LedgerEntryType.CASH_COLLECTED);
        assertThat(cashEntries)
                .as("the trigger's own documented fallback to the order total must run, never a silent zero")
                .hasSize(1);
        assertThat(cashEntries.getFirst().amountMinor()).isEqualTo(-45_000L);
    }

    @Test
    @DisplayName("ADR 0125: a cash-and-loyalty-balance order collects only the cash leg, read from the real settlement")
    void aRealSplitSettlementCollectsOnlyTheCashLeg() {
        // The record's open input -- whether cash-to-collect subtracts a settled loyalty-balance
        // tender -- is closed on subtracting it. The test above drives a fake that is simply told
        // the answer; this one wires the real OrderSettlementService over a real two-tender
        // settlement, so a regression in either half (the SQL that nets the balance leg out, or
        // the trigger asking for it) turns it red.
        var realCashDue = new OrderSettlementService(new JdbcSettlementStore(jdbc), unsupportedPointsPort(), clock);
        var realTrigger = new DeliveryAccrualOrderCompletionTrigger(
                deliveryCompletion, accruals, shifts, realCashDue, stepChecks);
        Fixture fixture = seedAssignedShipment("NOT_REQUIRED");
        seedCashAndBalanceSettlement(fixture.orderId(), 45_000L, 12_000L);

        realTrigger.onOrderingEvent(new OrderCompleted(
                UUID.randomUUID(), new TenantId(TENANT), fixture.orderId(), NOW, BRAND, branch, NOW, UZS, 45_000L, 1));

        List<LedgerEntryRow> cashEntries = entriesOfType(LedgerEntryType.CASH_COLLECTED);
        assertThat(cashEntries).hasSize(1);
        assertThat(cashEntries.getFirst().amountMinor())
                .as("12 000 of the 45 000 was redeemed from the balance; the courier collects 33 000")
                .isEqualTo(-33_000L);
    }

    @Test
    @DisplayName("ADR 0125: a delivery the courier already closed in the app keeps the courier's own delivered_at")
    void aDeliveryTheCourierClosedInTheAppKeepsItsOwnDeliveredAt() {
        // The courier app (gap map 3.9) writes shipments.delivered_at the moment the courier hands
        // the order over. The operator's «completed» can come hours later, at the end of a shift;
        // stamping the accrual with that instant turned an on-time delivery into a late one.
        Fixture fixture = seedAssignedShipment("NOT_REQUIRED");
        Instant pickedUpAt = NOW.plus(Duration.ofMinutes(20)); // inside the pickup window (NOW + 25 min)
        Instant deliveredAt = NOW.plus(Duration.ofMinutes(40)); // inside the promise (NOW + 45 min)
        captureInApp(fixture.shipmentId(), "DELIVERED", pickedUpAt, deliveredAt);

        trigger.onOrderingEvent(orderCompletedAt(fixture.orderId(), 45_000, NOW.plus(Duration.ofHours(3))));

        JdbcCourierLedgerStore.EarningRow earning =
                ledgerStore.findEarningByAttempt(TENANT, fixture.attemptId()).orElseThrow();
        assertThat(earning.deliveredAt()).isEqualTo(deliveredAt);
        assertThat(earning.onTimeOutcome()).isEqualTo(OnTimeOutcome.ON_TIME);
        assertThat(jdbc.sql("SELECT delivered_at FROM fulfillment.shipments WHERE tenant_id = :t AND id = :id")
                        .param("t", TENANT)
                        .param("id", fixture.shipmentId())
                        .query(OffsetDateTime.class)
                        .single()
                        .toInstant())
                .as("the shipment keeps the instant the courier recorded, not the operator's")
                .isEqualTo(deliveredAt);
    }

    @Test
    @DisplayName("ADR 0125: the courier's pickup is the kitchen handover, so a late delivery behind a late kitchen"
            + " is LATE_EXCUSED")
    void theCouriersPickupIsTheKitchenHandover() {
        Fixture fixture = seedAssignedShipment("NOT_REQUIRED");
        // The plan said the bag would be ready by NOW + 25 min; the courier collected it at +35.
        Instant pickedUpAt = NOW.plus(Duration.ofMinutes(35));
        captureInApp(fixture.shipmentId(), "PICKED_UP", pickedUpAt, null);

        // Delivered, and the operator completes the order, at +65 min: twenty minutes past the promise.
        trigger.onOrderingEvent(orderCompletedAt(fixture.orderId(), 45_000, NOW.plus(Duration.ofMinutes(65))));

        JdbcCourierLedgerStore.EarningRow earning =
                ledgerStore.findEarningByAttempt(TENANT, fixture.attemptId()).orElseThrow();
        assertThat(earning.kitchenHandoverAt()).isEqualTo(pickedUpAt);
        assertThat(earning.onTimeOutcome())
                .as("late, but the kitchen handed the bag over after the window the plan promised")
                .isEqualTo(OnTimeOutcome.LATE_EXCUSED);
    }

    @Test
    @DisplayName(
            "ADR 0125: with no capture in the app the order's completion still stands in, and a late delivery is LATE")
    void withNoCaptureInTheAppAnOrderCompletionStillStandsIn() {
        Fixture fixture = seedAssignedShipment("NOT_REQUIRED");

        trigger.onOrderingEvent(orderCompletedAt(fixture.orderId(), 45_000, NOW.plus(Duration.ofMinutes(65))));

        JdbcCourierLedgerStore.EarningRow earning =
                ledgerStore.findEarningByAttempt(TENANT, fixture.attemptId()).orElseThrow();
        assertThat(earning.deliveredAt()).isEqualTo(NOW.plus(Duration.ofMinutes(65)));
        assertThat(earning.kitchenHandoverAt())
                .as("nothing was captured, so nothing is guessed")
                .isNull();
        assertThat(earning.onTimeOutcome()).isEqualTo(OnTimeOutcome.LATE);
    }

    // ------------------------------------------- ADR 0178: GEO_UNVERIFIED reads what the steps recorded

    @Test
    @DisplayName("ADR 0178: a delivery whose pickup and handover both recorded a passed position check is verified")
    void aDeliveryMeasuredAtBothEndsIsVerified() {
        Fixture fixture = seedAssignedShipment("NOT_REQUIRED");
        recordGeo(fixture.shipmentId(), JdbcCourierStepCheckStore.Step.PICKED_UP, StepCheck.passed(StepCheck.Kind.GEO));
        recordGeo(fixture.shipmentId(), JdbcCourierStepCheckStore.Step.DELIVERED, StepCheck.passed(StepCheck.Kind.GEO));

        trigger.onOrderingEvent(orderCompleted(fixture.orderId(), 45_000));

        assertThat(ledgerStore
                        .findEarningByAttempt(TENANT, fixture.attemptId())
                        .orElseThrow()
                        .geoUnverified())
                .as("the courier app measured both ends and found the courier in place")
                .isFalse();
    }

    @Test
    @DisplayName("ADR 0178: an order an operator completed with no courier-app trail stays unverified")
    void anOperatorClosedDeliveryIsUnverified() {
        Fixture fixture = seedAssignedShipment("NOT_REQUIRED");

        trigger.onOrderingEvent(orderCompleted(fixture.orderId(), 45_000));

        assertThat(ledgerStore
                        .findEarningByAttempt(TENANT, fixture.attemptId())
                        .orElseThrow()
                        .geoUnverified())
                .as("nothing was measured, and claiming a verification that never ran is the worse answer")
                .isTrue();
    }

    @Test
    @DisplayName("ADR 0178: a position check that failed, or was never asked for, at either end leaves it unverified")
    void aFailedOrUnaskedCheckAtEitherEndIsUnverified() {
        Fixture failedAtHandover = seedAssignedShipment("NOT_REQUIRED");
        recordGeo(
                failedAtHandover.shipmentId(),
                JdbcCourierStepCheckStore.Step.PICKED_UP,
                StepCheck.passed(StepCheck.Kind.GEO));
        recordGeo(
                failedAtHandover.shipmentId(),
                JdbcCourierStepCheckStore.Step.DELIVERED,
                StepCheck.failed(StepCheck.Kind.GEO, GateRefusal.TOO_FAR_FROM_DROPOFF));
        Fixture unaskedAtPickup = seedAssignedShipment("NOT_REQUIRED");
        recordGeo(
                unaskedAtPickup.shipmentId(),
                JdbcCourierStepCheckStore.Step.PICKED_UP,
                StepCheck.skipped(StepCheck.Kind.GEO));
        recordGeo(
                unaskedAtPickup.shipmentId(),
                JdbcCourierStepCheckStore.Step.DELIVERED,
                StepCheck.passed(StepCheck.Kind.GEO));
        Fixture onlyOneEnd = seedAssignedShipment("NOT_REQUIRED");
        recordGeo(
                onlyOneEnd.shipmentId(),
                JdbcCourierStepCheckStore.Step.DELIVERED,
                StepCheck.passed(StepCheck.Kind.GEO));

        trigger.onOrderingEvent(orderCompleted(failedAtHandover.orderId(), 45_000));
        trigger.onOrderingEvent(orderCompleted(unaskedAtPickup.orderId(), 45_000));
        trigger.onOrderingEvent(orderCompleted(onlyOneEnd.orderId(), 45_000));

        for (Fixture fixture : List.of(failedAtHandover, unaskedAtPickup, onlyOneEnd)) {
            assertThat(ledgerStore
                            .findEarningByAttempt(TENANT, fixture.attemptId())
                            .orElseThrow()
                            .geoUnverified())
                    .isTrue();
        }
    }

    // ----------------------------------------------------- ADR 0170: pay for a run of several orders

    @Test
    @DisplayName("ADR 0170: the first order of a run accrues as it would alone; a later order keeps its per-order"
            + " amount and pays per kilometre on the shorter distance, and both distances are on the earning")
    void aRunsLaterOrderIsPaidOnTheShorterDistance() {
        UUID run = UUID.randomUUID();
        Fixture lead = seedAssignedShipment("NOT_REQUIRED", RunSeed.lead(run));
        Fixture follower = seedAssignedShipment("NOT_REQUIRED", RunSeed.follower(run, lead.shipmentId(), 600));

        trigger.onOrderingEvent(orderCompleted(lead.orderId(), 45_000));
        trigger.onOrderingEvent(orderCompleted(follower.orderId(), 30_000));

        JdbcCourierLedgerStore.EarningRow first =
                ledgerStore.findEarningByAttempt(TENANT, lead.attemptId()).orElseThrow();
        assertThat(first.totalMinor())
                .as(
                        "the first order of a run pays byte-identically to an order delivered alone: 3 000 + 4.2 km x 2 000")
                .isEqualTo(11_400L);
        assertThat(first.distanceMeters()).isEqualTo(4_200);
        assertThat(first.payBasis()).isEqualTo("DIRECT");
        assertThat(first.directDistanceMeters()).isNull();
        assertThat(first.runKey()).isNull();

        JdbcCourierLedgerStore.EarningRow second =
                ledgerStore.findEarningByAttempt(TENANT, follower.attemptId()).orElseThrow();
        assertThat(second.perOrderMinor())
                .as("the per-order component of a later order is paid in full")
                .isEqualTo(3_000L);
        assertThat(second.perKmMinor())
                .as("only the per-kilometre line falls: 600 m x 2 000 per km")
                .isEqualTo(1_200L);
        assertThat(second.totalMinor()).isEqualTo(4_200L);
        assertThat(second.distanceMeters())
                .as("distance_meters keeps meaning the distance paid")
                .isEqualTo(600);
        assertThat(second.directDistanceMeters())
                .as("the order's own distance, beside it")
                .isEqualTo(4_200);
        assertThat(second.payBasis()).isEqualTo("RUN_INCREMENT");
        assertThat(second.runKey()).isEqualTo(run);

        // Cash and the cost line stay per order: each order is collected at its own door, and the two cost
        // lines are the incremental amounts, which sum to what the run cost.
        assertThat(entriesOfType(LedgerEntryType.CASH_COLLECTED))
                .extracting(LedgerEntryRow::amountMinor)
                .containsExactlyInAnyOrder(-45_000L, -30_000L);
        assertThat(jdbc.sql("""
                                SELECT amount_minor FROM fulfillment.delivery_cost_lines
                                 WHERE tenant_id = :t AND shipment_id = :shipment
                                """)
                        .param("t", TENANT)
                        .param("shipment", follower.shipmentId())
                        .query(Long.class)
                        .single())
                .as("the follower's cost line is the incremental amount, not the order's own card")
                .isEqualTo(4_200L);
    }

    @Test
    @DisplayName("ADR 0170: a run of three under a three-band card with a floor pays exactly what the calculator"
            + " says for each distance paid")
    void aRunOfThreeUnderAThreeBandCardWithAFloor() {
        replaceCardWith(List.of(
                new RateComponent(UUID.randomUUID(), RateComponentType.PER_ORDER, 0, 2_000, null, null, null),
                new RateComponent(UUID.randomUUID(), RateComponentType.PER_KM_BAND, 0, 1_000, 0, 1_000, null),
                new RateComponent(UUID.randomUUID(), RateComponentType.PER_KM_BAND, 0, 2_000, 1_000, 3_000, null),
                new RateComponent(UUID.randomUUID(), RateComponentType.PER_KM_BAND, 0, 3_000, 3_000, null, null),
                new RateComponent(UUID.randomUUID(), RateComponentType.PER_ORDER_MINIMUM, 0, 5_000, null, null, null)));
        UUID run = UUID.randomUUID();
        Fixture lead = seedAssignedShipment("CAPTURED", RunSeed.lead(run));
        Fixture second = seedAssignedShipment("CAPTURED", RunSeed.follower(run, lead.shipmentId(), 800));
        Fixture third = seedAssignedShipment("CAPTURED", RunSeed.follower(run, lead.shipmentId(), 300));

        for (Fixture delivered : List.of(lead, second, third)) {
            trigger.onOrderingEvent(orderCompleted(delivered.orderId(), 20_000));
        }

        RateCard card = rateCardFor(lead);
        // 4 200 m under the three bands: 1 000 + 2 000 x 2 + 3 000 x 1.2 = 8 600 per-km, 2 000 per order.
        assertThat(ledgerStore
                        .findEarningByAttempt(TENANT, lead.attemptId())
                        .orElseThrow()
                        .totalMinor())
                .isEqualTo(AccrualCalculator.forDelivery(card, 4_200).totalMinor())
                .isEqualTo(10_600L);
        // 800 m: 800 per-km + 2 000 = 2 800, lifted to the 5 000 floor, which a later order is still owed in full.
        JdbcCourierLedgerStore.EarningRow secondEarning =
                ledgerStore.findEarningByAttempt(TENANT, second.attemptId()).orElseThrow();
        assertThat(secondEarning.totalMinor())
                .isEqualTo(AccrualCalculator.forDelivery(card, 800).totalMinor())
                .isEqualTo(5_000L);
        assertThat(secondEarning.minimumTopUpMinor()).isEqualTo(2_200L);
        JdbcCourierLedgerStore.EarningRow thirdEarning =
                ledgerStore.findEarningByAttempt(TENANT, third.attemptId()).orElseThrow();
        assertThat(thirdEarning.totalMinor())
                .isEqualTo(AccrualCalculator.forDelivery(card, 300).totalMinor())
                .isEqualTo(5_000L);
        assertThat(thirdEarning.distanceMeters()).isEqualTo(300);
        assertThat(thirdEarning.directDistanceMeters()).isEqualTo(4_200);
        assertThat(entriesOfType(LedgerEntryType.CASH_COLLECTED))
                .as("every order here is prepaid: no cash is collected for any of them")
                .isEmpty();
    }

    @Test
    @DisplayName("ADR 0170: a merge distance longer than the order's own distance never raises the pay")
    void aLongerMergeDistanceNeverRaisesThePay() {
        UUID run = UUID.randomUUID();
        Fixture lead = seedAssignedShipment("NOT_REQUIRED", RunSeed.lead(run));
        // The plan is 4 200 m; the stamp says 6 000 m (a lead on the far side). min(direct, merge) = direct.
        Fixture follower = seedAssignedShipment("NOT_REQUIRED", RunSeed.follower(run, lead.shipmentId(), 6_000));

        trigger.onOrderingEvent(orderCompleted(follower.orderId(), 20_000));

        JdbcCourierLedgerStore.EarningRow earning =
                ledgerStore.findEarningByAttempt(TENANT, follower.attemptId()).orElseThrow();
        assertThat(earning.distanceMeters()).isEqualTo(4_200);
        assertThat(earning.totalMinor()).isEqualTo(11_400L);
        assertThat(earning.directDistanceMeters()).isEqualTo(4_200);
    }

    @Test
    @DisplayName("ADR 0170: a lead cancelled before anyone picked it up makes the follower DIRECT -- it only improves")
    void aLeadCancelledBeforePickupMakesTheFollowerDirect() {
        UUID run = UUID.randomUUID();
        Fixture lead = seedAssignedShipment("NOT_REQUIRED", RunSeed.lead(run));
        Fixture follower = seedAssignedShipment("NOT_REQUIRED", RunSeed.follower(run, lead.shipmentId(), 600));
        cancelShipment(lead.shipmentId(), null);

        trigger.onOrderingEvent(orderCompleted(follower.orderId(), 20_000));

        JdbcCourierLedgerStore.EarningRow earning =
                ledgerStore.findEarningByAttempt(TENANT, follower.attemptId()).orElseThrow();
        assertThat(earning.payBasis()).isEqualTo("DIRECT");
        assertThat(earning.totalMinor()).as("a run of one is an order alone").isEqualTo(11_400L);
        assertThat(earning.distanceMeters()).isEqualTo(4_200);
        assertThat(earning.directDistanceMeters()).isNull();
        assertThat(earning.runKey()).isNull();
    }

    @Test
    @DisplayName("ADR 0170: a lead cancelled after pickup still made a run -- the courier carried its food")
    void aLeadCancelledAfterPickupStillMadeARun() {
        UUID run = UUID.randomUUID();
        Fixture lead = seedAssignedShipment("NOT_REQUIRED", RunSeed.lead(run));
        Fixture follower = seedAssignedShipment("NOT_REQUIRED", RunSeed.follower(run, lead.shipmentId(), 600));
        cancelShipment(lead.shipmentId(), NOW.plus(Duration.ofMinutes(20)));

        trigger.onOrderingEvent(orderCompleted(follower.orderId(), 20_000));

        JdbcCourierLedgerStore.EarningRow earning =
                ledgerStore.findEarningByAttempt(TENANT, follower.attemptId()).orElseThrow();
        assertThat(earning.payBasis()).isEqualTo("RUN_INCREMENT");
        assertThat(earning.totalMinor()).isEqualTo(4_200L);
    }

    @Test
    @DisplayName("ADR 0170: a lead cancelled after the follower was delivered leaves the follower on its quoted basis")
    void aLeadCancelledAfterTheFollowerWasDeliveredChangesNothing() {
        UUID run = UUID.randomUUID();
        Fixture lead = seedAssignedShipment("NOT_REQUIRED", RunSeed.lead(run));
        Fixture follower = seedAssignedShipment("NOT_REQUIRED", RunSeed.follower(run, lead.shipmentId(), 600));
        OrderCompleted delivered = orderCompleted(follower.orderId(), 20_000);
        trigger.onOrderingEvent(delivered);

        cancelShipment(lead.shipmentId(), null);
        trigger.onOrderingEvent(delivered);

        JdbcCourierLedgerStore.EarningRow earning =
                ledgerStore.findEarningByAttempt(TENANT, follower.attemptId()).orElseThrow();
        assertThat(earning.payBasis()).isEqualTo("RUN_INCREMENT");
        assertThat(earning.totalMinor()).as("the quote is the pay").isEqualTo(4_200L);
    }

    @Test
    @DisplayName("ADR 0170: a manual assignment, a PER_ORDER tenant and an offer made while the bias found nothing"
            + " are DIRECT and accrue as ever")
    void everythingWithoutAStampIsDirect() {
        Fixture plain = seedAssignedShipment("NOT_REQUIRED");

        trigger.onOrderingEvent(orderCompleted(plain.orderId(), 20_000));

        JdbcCourierLedgerStore.EarningRow earning =
                ledgerStore.findEarningByAttempt(TENANT, plain.attemptId()).orElseThrow();
        assertThat(earning.payBasis()).isEqualTo("DIRECT");
        assertThat(earning.totalMinor()).isEqualTo(11_400L);
        assertThat(earning.directDistanceMeters()).isNull();
        // A stamp whose shipment never joined a run (the lead had no key to lend, or the key was lost) cannot
        // reduce pay: with no run there is nothing to be an increment of.
        Fixture lead = seedAssignedShipment("NOT_REQUIRED");
        Fixture stampedOnly = seedAssignedShipment("NOT_REQUIRED", RunSeed.stampedOnly(lead.shipmentId(), 600));
        trigger.onOrderingEvent(orderCompleted(stampedOnly.orderId(), 20_000));
        assertThat(ledgerStore
                        .findEarningByAttempt(TENANT, stampedOnly.attemptId())
                        .orElseThrow()
                        .totalMinor())
                .isEqualTo(11_400L);
    }

    @Test
    @DisplayName("ADR 0170: a duplicate delivery event for a run order still accrues once")
    void aReplayedRunDeliveryAccruesOnce() {
        UUID run = UUID.randomUUID();
        Fixture lead = seedAssignedShipment("NOT_REQUIRED", RunSeed.lead(run));
        Fixture follower = seedAssignedShipment("NOT_REQUIRED", RunSeed.follower(run, lead.shipmentId(), 600));
        OrderCompleted event = orderCompleted(follower.orderId(), 20_000);

        trigger.onOrderingEvent(event);
        trigger.onOrderingEvent(event);

        assertThat(ledgerStore.entriesOfCourier(TENANT, courierId, 500).stream()
                        .filter(entry -> entry.entryType() == LedgerEntryType.DELIVERY_EARNING)
                        .map(LedgerEntryRow::amountMinor))
                .containsExactly(4_200L);
        assertThat(entriesOfType(LedgerEntryType.CASH_COLLECTED)).hasSize(1);
    }

    @Test
    @DisplayName("ADR 0170: the pay stamp on an offer cannot be rewritten once it is made -- the quote is the pay")
    void aPayStampCannotBeLowered() {
        UUID run = UUID.randomUUID();
        Fixture lead = seedAssignedShipment("NOT_REQUIRED", RunSeed.lead(run));
        Fixture follower = seedAssignedShipment("NOT_REQUIRED", RunSeed.follower(run, lead.shipmentId(), 600));
        Fixture alone = seedAssignedShipment("NOT_REQUIRED");

        // Lowering a stamped offer, and stamping a plain one after the fact, are both refused by the database.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                                UPDATE fulfillment.assignment_attempts SET run_merge_distance_m = 100
                                 WHERE tenant_id = :t AND id = :id
                                """)
                        .param("t", TENANT)
                        .param("id", follower.attemptId())
                        .update())
                .hasMessageContaining("pay stamp");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                                UPDATE fulfillment.assignment_attempts
                                   SET pay_basis = 'RUN_INCREMENT', run_merge_distance_m = 100, run_lead_shipment_id = :lead
                                 WHERE tenant_id = :t AND id = :id
                                """)
                        .param("t", TENANT)
                        .param("id", alone.attemptId())
                        .param("lead", lead.shipmentId())
                        .update())
                .hasMessageContaining("pay stamp");
        // ... while an ordinary change to the same row (the acceptance, the lapse) is untouched by it.
        assertThat(jdbc.sql("""
                                UPDATE fulfillment.assignment_attempts SET decision_reason = 'FLEET_AVAILABLE'
                                 WHERE tenant_id = :t AND id = :id
                                """)
                        .param("t", TENANT)
                        .param("id", follower.attemptId())
                        .update())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ADR 0170: the earning's own constraints tie the basis to the second distance")
    void anEarningRowCannotDisagreeWithItsBasis() {
        UUID run = UUID.randomUUID();
        Fixture lead = seedAssignedShipment("NOT_REQUIRED", RunSeed.lead(run));
        Fixture follower = seedAssignedShipment("NOT_REQUIRED", RunSeed.follower(run, lead.shipmentId(), 600));
        trigger.onOrderingEvent(orderCompleted(follower.orderId(), 20_000));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                                UPDATE fulfillment.courier_assignment_earnings SET direct_distance_meters = NULL
                                 WHERE tenant_id = :t AND assignment_attempt_id = :attempt
                                """)
                        .param("t", TENANT)
                        .param("attempt", follower.attemptId())
                        .update())
                .as("RUN_INCREMENT without the order's own distance beside it")
                .hasMessageContaining("ck_earning_direct_distance");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                                UPDATE fulfillment.courier_assignment_earnings SET direct_distance_meters = 100
                                 WHERE tenant_id = :t AND assignment_attempt_id = :attempt
                                """)
                        .param("t", TENANT)
                        .param("attempt", follower.attemptId())
                        .update())
                .as("a distance paid that exceeds the order's own distance")
                .hasMessageContaining("ck_earning_distance_paid");
    }

    /** Every method throws: the no-settlement path under test never reaches points. */
    private static uz.horecaos.platform.loyalty.api.PointsRedemptionPort unsupportedPointsPort() {
        return new uz.horecaos.platform.loyalty.api.PointsRedemptionPort() {
            @Override
            public RedemptionOffer quote(RedemptionQuery query) {
                throw new UnsupportedOperationException("not exercised by this suite");
            }

            @Override
            public PointsHold reserve(ReserveCommand command) {
                throw new UnsupportedOperationException("not exercised by this suite");
            }

            @Override
            public void settle(UUID tenantId, UUID tenderId) {
                throw new UnsupportedOperationException("not exercised by this suite");
            }

            @Override
            public void release(UUID tenantId, UUID tenderId, String reasonCode, String actor) {
                throw new UnsupportedOperationException("not exercised by this suite");
            }

            @Override
            public void reverse(UUID tenantId, UUID tenderId, long amountMinor, String reasonCode, String actor) {
                throw new UnsupportedOperationException("not exercised by this suite");
            }
        };
    }

    // --------------------------------------------------------------- fixtures

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'accrual-trigger-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        branch = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """)
                .param("id", branch)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
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
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
    }

    private void seedCourier(CourierEngagementService engagements) {
        UUID courierTypeId = UUID.randomUUID();
        courierStore.insertType(new CourierTypeRow(
                courierTypeId, TENANT, "SCOOTER", "Scooter", "SCOOTER", 0, 15_000, 2, 60, 0, "SHIFT", "ACTIVE", 1));

        CourierEngagementService.Registration registration =
                engagements.register(new CourierEngagementService.NewCourier(
                        TENANT,
                        courierTypeId,
                        "keycloak-courier",
                        "K-001",
                        "Alisher Karimov",
                        LocalDate.ofInstant(NOW, ZoneOffset.UTC),
                        manager(),
                        "onboarding a rider",
                        "corr"));
        courierId = registration.courierId();

        engagements.verify(new CourierEngagementService.VerifyRegistration(
                TENANT,
                registration.engagementId(),
                "312345678901",
                LocalDate.ofInstant(NOW, ZoneOffset.UTC).plusYears(1),
                VerificationMethod.MANUAL_ATTESTATION,
                null,
                manager(),
                "sighted the registration certificate",
                "corr"));
    }

    private void seedRateCard(CourierRateCardService rateCards) {
        UUID rateCardId = rateCards.author(new CourierRateCardService.NewRateCard(
                TENANT,
                BRAND,
                null,
                null,
                "STANDARD",
                1,
                UZS,
                List.of(
                        new RateComponent(UUID.randomUUID(), RateComponentType.PER_ORDER, 0, 3_000, null, null, null),
                        new RateComponent(UUID.randomUUID(), RateComponentType.PER_KM_BAND, 0, 2_000, 0, null, null))));
        rateCards.activate(TENANT, rateCardId, manager(), "activating the standard card");
    }

    /** An INTERNAL shipment ASSIGNED (not yet DELIVERED) to the fixture courier, on a real order. */
    private Fixture seedAssignedShipment(String paymentStatusProjection) {
        return seedAssignedShipment(paymentStatusProjection, RunSeed.NONE);
    }

    /**
     * The same, as it stands after the courier accepted an offer made under the ADR 0170 grouping bias: the
     * attempt carries the pay stamp the offer was made with and the shipment carries the run it joined.
     */
    private Fixture seedAssignedShipment(String paymentStatusProjection, RunSeed run) {
        int sequence = ++chainSequence;
        UUID orderId = seedOrder(sequence, paymentStatusProjection);
        UUID planId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime anchor = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (
                    id, tenant_id, brand_id, location_id, order_id, status, sourcing_mode,
                    service_level, customer_delivery_fee_minor, currency, confirmed_at,
                    preparation_seconds, estimated_ready_at, pickup_window_start,
                    pickup_window_end, promised_delivery_start, promised_delivery_end,
                    source_at, latest_assignment_at, branch_zone, distance_meters, distance_source)
                SELECT :id, :tenantId, :brandId, :locationId, :orderId, 'ASSIGNED', 'FLEET_FIRST',
                       'STANDARD', 12000, 'UZS', anchor, 900,
                       anchor + interval '15 minutes', anchor + interval '15 minutes',
                       anchor + interval '25 minutes', anchor + interval '30 minutes',
                       anchor + interval '45 minutes', anchor, anchor + interval '25 minutes',
                       'Asia/Tashkent', 4200, 'RADIUS'
                  FROM (SELECT CAST(:anchor AS timestamptz) AS anchor) AS moment
                """)
                .param("id", planId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("orderId", orderId)
                .param("anchor", anchor)
                .update();

        jdbc.sql("""
                INSERT INTO fulfillment.shipments (
                    id, tenant_id, brand_id, location_id, order_id, delivery_plan_id, status,
                    source_type, courier_id, assigned_at, run_key, run_merge_distance_m)
                SELECT :id, :tenantId, :brandId, :locationId, :orderId, :planId, 'ASSIGNED',
                       'INTERNAL', :courierId, anchor + interval '5 minutes', :runKey, :runMergeDistance
                  FROM (SELECT CAST(:anchor AS timestamptz) AS anchor) AS moment
                """)
                .param("runKey", run.runKey())
                .param("runMergeDistance", run.shipmentMergeDistance())
                .param("id", shipmentId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("orderId", orderId)
                .param("planId", planId)
                .param("courierId", courierId)
                .param("anchor", anchor)
                .update();

        jdbc.sql("""
                INSERT INTO fulfillment.assignment_attempts (
                    id, tenant_id, delivery_plan_id, shipment_id, sequence_number, source_type,
                    courier_id, status, idempotency_key, decision_reason, shift_enforcement_mode,
                    requested_at, accepted_at, pay_basis, run_merge_distance_m, run_lead_shipment_id)
                SELECT :id, :tenantId, :planId, :shipmentId, 1, 'INTERNAL', :courierId, 'ACCEPTED',
                       :idempotencyKey, 'FLEET_AVAILABLE', 'ADVISORY',
                       anchor + interval '1 minute', anchor + interval '5 minutes',
                       :payBasis, :attemptMergeDistance, :leadShipmentId
                  FROM (SELECT CAST(:anchor AS timestamptz) AS anchor) AS moment
                """)
                .param("payBasis", run.payBasis())
                .param("attemptMergeDistance", run.attemptMergeDistance())
                .param("leadShipmentId", run.leadShipmentId())
                .param("id", attemptId)
                .param("tenantId", TENANT)
                .param("planId", planId)
                .param("shipmentId", shipmentId)
                .param("courierId", courierId)
                .param("idempotencyKey", "trigger-offer-" + sequence)
                .param("anchor", anchor)
                .update();

        return new Fixture(orderId, shipmentId, attemptId);
    }

    private UUID seedOrder(int sequence, String paymentStatusProjection) {
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String reference = "trigger-order-" + sequence;

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        45000, 0, 45000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("publicationId", publicationId)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DELIVERY', 'UZS',
                        'ACTIVE', :reference, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_version,
                    approval_channel_snapshot, status, payment_status_projection, currency,
                    subtotal_minor, tax_minor, total_minor, pricing_quote_id, pricing_context_hash,
                    catalog_publication_id, cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'STOREFRONT',
                        :reference, 'DELIVERY', 'AUTO_CONFIRM', 0, 'NONE', 'COMPLETED', :paymentStatus,
                        'UZS', 45000, 0, 45000, :quoteId, 'hash', :publicationId, :cartId, :reference,
                        1, now())
                """)
                .param("id", orderId)
                .param("number", "T-" + sequence)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .param("paymentStatus", paymentStatusProjection)
                .param("quoteId", quoteId)
                .param("publicationId", publicationId)
                .param("cartId", cartId)
                .update();

        return orderId;
    }

    /**
     * The default {@link #cashDue} figure for this order is the same total
     * that used to flow straight into {@code cashToCollectMinor} before this
     * fix -- correct for every test here that carries no balance tender, and
     * a test with one overrides it afterward with {@link
     * FakeCashDueLookupPort#set}.
     */
    private OrderCompleted orderCompleted(UUID orderId, long totalMinor) {
        cashDue.set(orderId, totalMinor);
        return new OrderCompleted(
                UUID.randomUUID(), new TenantId(TENANT), orderId, NOW, BRAND, branch, NOW, UZS, totalMinor, 1);
    }

    /**
     * A settlement-free {@link CashDueLookupPort} test double: this suite
     * drives {@link DeliveryAccrualOrderCompletionTrigger} directly against a
     * constructed {@link OrderCompleted} (see the class javadoc) rather than
     * through a real checkout, so there is no real {@code
     * payments.order_settlements} row for {@link
     * uz.horecaos.platform.payments.settlement.OrderSettlementService}'s own
     * implementation to read. A test names what a given order is actually
     * still owed in cash directly, the same way it already names the order's
     * total.
     */
    private static final class FakeCashDueLookupPort implements CashDueLookupPort {

        private final Map<UUID, Long> dueByOrder = new HashMap<>();

        void set(UUID orderId, long cashDueMinor) {
            dueByOrder.put(orderId, cashDueMinor);
        }

        @Override
        public long cashDueMinor(UUID tenantId, UUID orderId) {
            Long due = dueByOrder.get(orderId);
            return due == null ? 0L : due;
        }

        @Override
        public OptionalLong cashDueMinorIfSettled(UUID tenantId, UUID orderId) {
            Long due = dueByOrder.get(orderId);
            return due == null ? OptionalLong.empty() : OptionalLong.of(due);
        }
    }

    /**
     * What the courier app leaves on the shipment when the courier taps «picked up» or «delivered»
     * ({@code JdbcCourierJobStore.advance}): the status and the two timestamps, nothing else.
     */
    private void recordGeo(UUID shipmentId, JdbcCourierStepCheckStore.Step step, StepCheck check) {
        stepChecks.record(
                TENANT, shipmentId, courierId, step, List.of(check), GateMode.SOFT, UUID.randomUUID(), 1, NOW);
    }

    private void captureInApp(UUID shipmentId, String status, Instant pickedUpAt, @Nullable Instant deliveredAt) {
        jdbc.sql("""
                UPDATE fulfillment.shipments
                   SET status = :status, picked_up_at = :pickedUpAt, delivered_at = :deliveredAt
                 WHERE tenant_id = :t AND id = :id
                """)
                .param("status", status)
                .param("pickedUpAt", OffsetDateTime.ofInstant(pickedUpAt, ZoneOffset.UTC))
                .param(
                        "deliveredAt",
                        deliveredAt == null ? null : OffsetDateTime.ofInstant(deliveredAt, ZoneOffset.UTC))
                .param("t", TENANT)
                .param("id", shipmentId)
                .update();
    }

    private OrderCompleted orderCompletedAt(UUID orderId, long totalMinor, Instant completedAt) {
        cashDue.set(orderId, totalMinor);
        return new OrderCompleted(
                UUID.randomUUID(),
                new TenantId(TENANT),
                orderId,
                completedAt,
                BRAND,
                branch,
                completedAt,
                UZS,
                totalMinor,
                1);
    }

    /** A settlement of one cash tender and one loyalty-balance tender that has already settled. */
    private void seedCashAndBalanceSettlement(UUID orderId, long totalDueMinor, long balanceMinor) {
        UUID cashMethod = UUID.randomUUID();
        UUID balanceMethod = UUID.randomUUID();
        UUID settlementId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.payment_methods (
                    id, tenant_id, code, display_name, responsibility, settles_from_balance, status, version)
                VALUES (:cash, :t, 'CASH', 'Cash', 'OPERATOR', false, 'ACTIVE', 1),
                       (:balance, :t, 'POINTS', 'Points', 'OPERATOR', true, 'ACTIVE', 1)
                """)
                .param("cash", cashMethod)
                .param("balance", balanceMethod)
                .param("t", TENANT)
                .update();
        jdbc.sql("""
                INSERT INTO payments.order_settlements (
                    id, tenant_id, order_id, currency, total_due_minor, settled_minor, status, version)
                VALUES (:id, :t, :order, 'UZS', :total, :balance, 'PARTIALLY_SETTLED', 1)
                """)
                .param("id", settlementId)
                .param("t", TENANT)
                .param("order", orderId)
                .param("total", totalDueMinor)
                .param("balance", balanceMinor)
                .update();
        jdbc.sql("""
                INSERT INTO payments.tenders (
                    id, tenant_id, settlement_id, sequence, payment_method_id, settles_from_balance,
                    amount_minor, refunded_minor, currency, status, settled_at, idempotency_key, version)
                VALUES (gen_random_uuid(), :t, :settlement, 1, :balanceMethod, true,
                        :balance, 0, 'UZS', 'SETTLED', now(), :balanceKey, 1),
                       (gen_random_uuid(), :t, :settlement, 2, :cashMethod, false,
                        :cash, 0, 'UZS', 'PLANNED', NULL, :cashKey, 1)
                """)
                .param("t", TENANT)
                .param("settlement", settlementId)
                .param("balanceMethod", balanceMethod)
                .param("balance", balanceMinor)
                .param("cashMethod", cashMethod)
                .param("cash", totalDueMinor - balanceMinor)
                .param("balanceKey", "tender-balance-" + settlementId)
                .param("cashKey", "tender-cash-" + settlementId)
                .update();
    }

    private RateCard rateCardFor(Fixture fixture) {
        UUID typeId = jdbc.sql("SELECT courier_type_id FROM fulfillment.couriers WHERE tenant_id = :t AND id = :id")
                .param("t", TENANT)
                .param("id", courierId)
                .query(UUID.class)
                .single();
        return new JdbcCourierRateCardStore(jdbc)
                .resolve(TENANT, BRAND, branch, typeId, NOW)
                .orElseThrow();
    }

    /** Archives the standard card and activates one with the given components, so a test can name its own ladder. */
    private void replaceCardWith(List<RateComponent> components) {
        jdbc.sql("UPDATE fulfillment.courier_rate_cards SET status = 'ARCHIVED' WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        var rateCards = new CourierRateCardService(new JdbcCourierRateCardStore(jdbc), fact -> {}, clock);
        UUID cardId = rateCards.author(
                new CourierRateCardService.NewRateCard(TENANT, BRAND, null, null, "STANDARD", 2, UZS, components));
        rateCards.activate(TENANT, cardId, manager(), "a three-band card for the run test");
    }

    /** The shipment is cancelled; {@code pickedUpAt} says whether anyone had collected it first. */
    private void cancelShipment(UUID shipmentId, @Nullable Instant pickedUpAt) {
        jdbc.sql("""
                UPDATE fulfillment.shipments
                   SET status = 'CANCELLED', cancelled_at = :at, picked_up_at = :pickedUpAt,
                       cancellation_reason_code = 'TEST'
                 WHERE tenant_id = :t AND id = :id
                """)
                .param("at", OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC))
                .param("pickedUpAt", pickedUpAt == null ? null : OffsetDateTime.ofInstant(pickedUpAt, ZoneOffset.UTC))
                .param("t", TENANT)
                .param("id", shipmentId)
                .update();
    }

    private Map<String, Object> shipmentRow(UUID shipmentId) {
        return jdbc.sql("SELECT status, delivered_at FROM fulfillment.shipments WHERE tenant_id = :t AND id = :id")
                .param("t", TENANT)
                .param("id", shipmentId)
                .query()
                .singleRow();
    }

    private List<LedgerEntryRow> entriesOfType(LedgerEntryType type) {
        return ledgerStore.entriesOfCourier(TENANT, courierId, 500).stream()
                .filter(entry -> entry.entryType() == type)
                .toList();
    }

    private static ActorRef manager() {
        return ActorRef.user("keycloak-manager", "Branch manager");
    }

    private record Fixture(UUID orderId, UUID shipmentId, UUID attemptId) {}

    /**
     * What a run leaves on the rows (ADR 0170): the offer's pay stamp on the attempt, and the run a shipment
     * joined. A lead has a key and no stamp; a follower has the key, the merge distance and the lead it was
     * measured to.
     */
    private record RunSeed(
            String payBasis,
            @Nullable Integer attemptMergeDistance,
            @Nullable UUID leadShipmentId,
            @Nullable UUID runKey,
            @Nullable Integer shipmentMergeDistance) {

        static final RunSeed NONE = new RunSeed("DIRECT", null, null, null, null);

        /** The first order of a run: paid exactly as it would be alone, but carrying the run's key. */
        static RunSeed lead(UUID runKey) {
            return new RunSeed("DIRECT", null, null, runKey, null);
        }

        /** A later order of a run, offered with the quote «paid on {@code mergeMetres}». */
        static RunSeed follower(UUID runKey, UUID leadShipmentId, int mergeMetres) {
            return new RunSeed("RUN_INCREMENT", mergeMetres, leadShipmentId, runKey, mergeMetres);
        }

        /** An offer stamped for a run that no shipment ever joined (the lead had no key to lend). */
        static RunSeed stampedOnly(UUID leadShipmentId, int mergeMetres) {
            return new RunSeed("RUN_INCREMENT", mergeMetres, leadShipmentId, null, null);
        }
    }
}
