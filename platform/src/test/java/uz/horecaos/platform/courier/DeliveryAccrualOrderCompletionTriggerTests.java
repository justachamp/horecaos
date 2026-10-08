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
import uz.horecaos.platform.courier.domain.LedgerEntryType;
import uz.horecaos.platform.courier.domain.OnTimeOutcome;
import uz.horecaos.platform.courier.domain.RateComponent;
import uz.horecaos.platform.courier.domain.RateComponentType;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.LedgerEntryRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
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
                (tenantId, at) -> at.atZone(ZoneId.of("Asia/Tashkent")).toLocalDate());
        var rateCards = new CourierRateCardService(rateCardStore, audit, clock);
        deliveryCompletion = new JdbcDeliveryCompletionAdapter(jdbc);
        cashDue = new FakeCashDueLookupPort();

        trigger = new DeliveryAccrualOrderCompletionTrigger(deliveryCompletion, accruals, shifts, cashDue);

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
        var realTrigger = new DeliveryAccrualOrderCompletionTrigger(deliveryCompletion, accruals, shifts, realCashDue);

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
        var realTrigger = new DeliveryAccrualOrderCompletionTrigger(deliveryCompletion, accruals, shifts, realCashDue);
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
                    source_type, courier_id, assigned_at)
                SELECT :id, :tenantId, :brandId, :locationId, :orderId, :planId, 'ASSIGNED',
                       'INTERNAL', :courierId, anchor + interval '5 minutes'
                  FROM (SELECT CAST(:anchor AS timestamptz) AS anchor) AS moment
                """)
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
                    requested_at, accepted_at)
                SELECT :id, :tenantId, :planId, :shipmentId, 1, 'INTERNAL', :courierId, 'ACCEPTED',
                       :idempotencyKey, 'FLEET_AVAILABLE', 'ADVISORY',
                       anchor + interval '1 minute', anchor + interval '5 minutes'
                  FROM (SELECT CAST(:anchor AS timestamptz) AS anchor) AS moment
                """)
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
}
