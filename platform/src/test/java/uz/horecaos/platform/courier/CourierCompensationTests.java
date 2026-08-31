package uz.horecaos.platform.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.application.ConfirmationPointRetentionJob;
import uz.horecaos.platform.courier.application.CourierAccrualService;
import uz.horecaos.platform.courier.application.CourierAdjustmentService;
import uz.horecaos.platform.courier.application.CourierCashService;
import uz.horecaos.platform.courier.application.CourierDispatchGate;
import uz.horecaos.platform.courier.application.CourierEngagementService;
import uz.horecaos.platform.courier.application.CourierLedgerService;
import uz.horecaos.platform.courier.application.CourierPolicyResolver;
import uz.horecaos.platform.courier.application.CourierRateCardService;
import uz.horecaos.platform.courier.application.CourierSettlementService;
import uz.horecaos.platform.courier.application.CourierShiftService;
import uz.horecaos.platform.courier.application.DeliveryCostQueryService;
import uz.horecaos.platform.courier.application.PartnerInvoiceService;
import uz.horecaos.platform.courier.application.RegistrationComplianceSweeper;
import uz.horecaos.platform.courier.application.port.CourierNotificationPort;
import uz.horecaos.platform.courier.application.port.LegalEntityResolver;
import uz.horecaos.platform.courier.domain.AccrualCalculator;
import uz.horecaos.platform.courier.domain.AdjustmentOrigin;
import uz.horecaos.platform.courier.domain.CostBasis;
import uz.horecaos.platform.courier.domain.CourierAccrual;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.domain.DistanceSource;
import uz.horecaos.platform.courier.domain.EngagementStatus;
import uz.horecaos.platform.courier.domain.LedgerEntryType;
import uz.horecaos.platform.courier.domain.OnTimeOutcome;
import uz.horecaos.platform.courier.domain.PartnerChargeType;
import uz.horecaos.platform.courier.domain.PayoutMethod;
import uz.horecaos.platform.courier.domain.RateCard;
import uz.horecaos.platform.courier.domain.RateComponent;
import uz.horecaos.platform.courier.domain.RateComponentType;
import uz.horecaos.platform.courier.domain.SettlementPeriodStatus;
import uz.horecaos.platform.courier.domain.ShiftActor;
import uz.horecaos.platform.courier.domain.ShiftEnforcement;
import uz.horecaos.platform.courier.domain.ShiftStatus;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.EarningRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.LedgerEntryRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.PeriodRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierTypeRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.EngagementRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.CostLineRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Courier compensation, shifts, and settlement (ADR 0042).
 *
 * <p>Against a real PostgreSQL, because most of what ADR 0042 promises is a
 * property of the database rather than of the Java. Whether a delivered order
 * can accrue twice is a unique constraint; whether the application can rewrite a
 * ledger line is a grant; whether a statement's transfer amount can disagree with
 * its own components is a CHECK. None of those can be tested against a mock, and
 * each is a way somebody gets paid the wrong amount.
 */
class CourierCompensationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String UZS = "UZS";

    /** A Tuesday, 12:00 in Tashkent. */
    private static final Instant NOON = Instant.parse("2026-08-25T07:00:00Z");

    private static TestDatabase.Handle db;
    private static String username;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private MutableClock clock;

    private JdbcCourierStore courierStore;
    private JdbcCourierShiftStore shiftStore;
    private JdbcCourierLedgerStore ledgerStore;
    private JdbcCourierRateCardStore rateCardStore;
    private JdbcDeliveryCostStore costStore;

    private CourierEngagementService engagements;
    private CourierShiftService shifts;
    private CourierLedgerService ledger;
    private CourierAccrualService accruals;
    private CourierSettlementService settlement;
    private CourierCashService cash;
    private CourierAdjustmentService adjustments;
    private CourierDispatchGate gate;
    private CourierRateCardService rateCards;
    private DeliveryCostQueryService deliveryCosts;
    private PartnerInvoiceService partnerInvoices;
    private RegistrationComplianceSweeper sweeper;
    private ConfirmationPointRetentionJob retention;

    private RecordingAudit audit;
    private ConfigurableApprovals approvals;
    private ConfigurablePolicies policies;
    private RecordingNotifications notifications;

    private UUID branch;
    private UUID channelId;
    private UUID publicationId;
    private UUID courierTypeId;
    private UUID courierId;
    private UUID engagementId;
    private UUID rateCardId;

    /** Keeps the per-order unique keys apart when one test builds several chains. */
    private int chainSequence;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for courier compensation tests");
        db = TestDatabase.migrated();
        username = db.username();
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

        jdbc.sql("""
                TRUNCATE TABLE fulfillment.courier_ledger_entries,
                    fulfillment.courier_assignment_earnings,
                    fulfillment.courier_settlement_statements,
                    fulfillment.courier_payouts,
                    fulfillment.courier_cash_handovers,
                    fulfillment.courier_shift_breaks,
                    fulfillment.courier_shifts,
                    fulfillment.courier_settlement_periods,
                    fulfillment.courier_registration_notices,
                    fulfillment.courier_engagements,
                    fulfillment.couriers,
                    fulfillment.courier_rate_components,
                    fulfillment.courier_rate_cards,
                    fulfillment.courier_adjustment_reasons,
                    fulfillment.courier_types,
                    fulfillment.delivery_cost_lines,
                    fulfillment.partner_delivery_invoice_lines,
                    fulfillment.partner_delivery_invoices,
                    fulfillment.delivery_exceptions,
                    fulfillment.delivery_sourcing_jobs,
                    fulfillment.assignment_attempts,
                    fulfillment.delivery_quotes,
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

        clock = new MutableClock(NOON);
        audit = new RecordingAudit();
        approvals = new ConfigurableApprovals();
        policies = new ConfigurablePolicies();
        notifications = new RecordingNotifications();
        FieldProtection protection = new ReversibleProtection();
        LegalEntityResolver legalEntities = (tenantId, locationId, businessDate) -> Optional.empty();
        ObjectMapper objectMapper = JsonMapper.builder().build();

        courierStore = new JdbcCourierStore(jdbc);
        shiftStore = new JdbcCourierShiftStore(jdbc);
        ledgerStore = new JdbcCourierLedgerStore(jdbc);
        rateCardStore = new JdbcCourierRateCardStore(jdbc);
        costStore = new JdbcDeliveryCostStore(jdbc);

        CourierPolicyResolver policyResolver = new CourierPolicyResolver(policies);
        ledger = new CourierLedgerService(ledgerStore, courierStore, policyResolver, legalEntities, clock);
        // Every engagement seeded here attests with no evidence media, so the
        // availability port is never asked. It answers false rather than true so
        // that a future test which does pass an id fails loudly instead of
        // quietly accepting whatever uuid it invented; the tenant scoping of
        // evidence is proved in CourierEvidenceMediaTenantScopeTests.
        engagements = new CourierEngagementService(
                courierStore, protection, audit, policyResolver, (tenantId, assetIds) -> false, clock);
        shifts = new CourierShiftService(
                shiftStore, courierStore, ledgerStore, rateCardStore, ledger, policyResolver, protection, audit, clock);
        accruals = new CourierAccrualService(
                ledgerStore,
                rateCardStore,
                shiftStore,
                courierStore,
                costStore,
                ledger,
                policyResolver,
                legalEntities,
                protection);
        settlement = new CourierSettlementService(
                ledgerStore, courierStore, costStore, approvals, audit, objectMapper, clock);
        cash = new CourierCashService(shiftStore, ledger, audit, clock);
        adjustments = new CourierAdjustmentService(courierStore, ledger, approvals, audit, policyResolver, clock);
        gate = new CourierDispatchGate(courierStore, shiftStore, policyResolver);
        rateCards = new CourierRateCardService(rateCardStore, audit, clock);
        deliveryCosts = new DeliveryCostQueryService(costStore);
        partnerInvoices = new PartnerInvoiceService(costStore, audit, clock);
        sweeper = new RegistrationComplianceSweeper(courierStore, notifications, policyResolver, audit, clock);
        retention = new ConfirmationPointRetentionJob(ledgerStore, clock);

        seedTenancy();
        seedCourier();
        seedRateCard();
        seedAdjustmentReasons();
    }

    // ------------------------------------------------------------ the accrual

    @Test
    @DisplayName("the accrual cannot see the customer's delivery charge, so nothing about the " + "charge can move it")
    void theAccrualIsIndependentOfWhatTheCustomerPaid() {
        RateCard card = rateCardStore.findCard(TENANT, rateCardId).orElseThrow();

        // 6.4 km: 3000 flat plus 2000 per km to 3 km, then 1500 beyond.
        CourierAccrual first = AccrualCalculator.forDelivery(card, 6400);
        CourierAccrual again = AccrualCalculator.forDelivery(card, 6400);

        assertThat(first).isEqualTo(again);
        assertThat(first.perOrderMinor()).isEqualTo(3_000);
        assertThat(first.perKmMinor()).isEqualTo(2000L * 3000 / 1000 + 1500L * 3400 / 1000);
        // The calculator takes a rate card and a distance and nothing else; there
        // is no parameter through which a free-delivery promotion could reach it.
        assertThat(AccrualCalculator.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("forDelivery"))
                .allSatisfy(method -> assertThat(method.getParameterCount()).isEqualTo(2));
    }

    @Test
    @DisplayName("per-kilometre money is rounded once, so band boundaries do not create drift")
    void perKilometreMoneyIsRoundedOnce() {
        RateCard oneBand = new RateCard(
                UUID.randomUUID(),
                1,
                UZS,
                List.of(new RateComponent(UUID.randomUUID(), RateComponentType.PER_KM_BAND, 0, 1000, 0, null, null)));
        RateCard threeBands = new RateCard(
                UUID.randomUUID(),
                1,
                UZS,
                List.of(
                        new RateComponent(UUID.randomUUID(), RateComponentType.PER_KM_BAND, 0, 1000, 0, 1500, null),
                        new RateComponent(UUID.randomUUID(), RateComponentType.PER_KM_BAND, 1, 1000, 1500, 3300, null),
                        new RateComponent(
                                UUID.randomUUID(), RateComponentType.PER_KM_BAND, 2, 1000, 3300, null, null)));

        assertThat(AccrualCalculator.forDelivery(oneBand, 4750).perKmMinor())
                .isEqualTo(AccrualCalculator.forDelivery(threeBands, 4750).perKmMinor());
    }

    @Test
    @DisplayName("a rate card with a gap or an overlap between distance bands fails activation")
    void aRateCardWithAGapOrAnOverlapFailsActivation() {
        UUID gapped = rateCards.author(new CourierRateCardService.NewRateCard(
                TENANT,
                BRAND,
                null,
                null,
                "GAPPED",
                1,
                UZS,
                List.of(
                        band(0, 2000, 1000),
                        // Nothing covers 2000 to 3000: an order at 2500 metres
                        // earns nothing for its distance.
                        band(3000, null, 800))));

        Throwable gapFailure = catchThrowable(() -> rateCards.activate(TENANT, gapped, manager(), "activating"));
        assertThat(gapFailure).isInstanceOf(ApiException.class);
        assertThat(gapFailure).hasMessageContaining("gap");

        UUID overlapping = rateCards.author(new CourierRateCardService.NewRateCard(
                TENANT, BRAND, null, null, "OVERLAP", 1, UZS, List.of(band(0, 3000, 1000), band(2000, null, 800))));

        assertThat(catchThrowable(() -> rateCards.activate(TENANT, overlapping, manager(), "x")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("overlap");

        // And an unbounded top band is required, or the longest delivery of the
        // week is the one that pays nothing.
        UUID stopsShort = rateCards.author(new CourierRateCardService.NewRateCard(
                TENANT, BRAND, null, null, "SHORT", 1, UZS, List.of(band(0, 5000, 1000))));
        assertThat(catchThrowable(() -> rateCards.activate(TENANT, stopsShort, manager(), "x")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("unbounded");
    }

    @Test
    @DisplayName("a delivered order accrues exactly once under duplicate delivery events")
    void aDeliveredOrderAccruesExactlyOnce() {
        DeliveredShipment carried = deliveredShipment();

        EarningRow first = accruals.recordDelivery(delivery(carried, 4000, 0));
        EarningRow replay = accruals.recordDelivery(delivery(carried, 4000, 0));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(ledgerStore.entriesOf(TENANT, first.settlementPeriodId()))
                .filteredOn(entry -> entry.entryType() == LedgerEntryType.DELIVERY_EARNING)
                .hasSize(1);
    }

    @Test
    @DisplayName("a late delivery whose handover fell outside the pickup window is LATE_EXCUSED")
    void aLateDeliveryAfterALateKitchenIsExcused() {
        Instant promised = NOON.plus(Duration.ofMinutes(30));
        Instant pickupWindowEnd = NOON.plus(Duration.ofMinutes(10));

        DeliveredShipment afterALateKitchen = deliveredShipment();
        EarningRow lateKitchen = accruals.recordDelivery(new CourierAccrualService.DeliveredAssignment(
                TENANT,
                BRAND,
                branch,
                courierId,
                null,
                afterALateKitchen.shipmentId(),
                afterALateKitchen.attemptId(),
                4000,
                DistanceSource.ROUTING,
                NOON,
                NOON.plus(Duration.ofMinutes(50)),
                promised,
                300,
                1,
                NOON.plus(Duration.ofMinutes(25)),
                pickupWindowEnd,
                0,
                false,
                null,
                null));
        assertThat(lateKitchen.onTimeOutcome()).isEqualTo(OnTimeOutcome.LATE_EXCUSED);

        DeliveredShipment afterAPromptKitchen = deliveredShipment();
        EarningRow lateCourier = accruals.recordDelivery(new CourierAccrualService.DeliveredAssignment(
                TENANT,
                BRAND,
                branch,
                courierId,
                null,
                afterAPromptKitchen.shipmentId(),
                afterAPromptKitchen.attemptId(),
                4000,
                DistanceSource.ROUTING,
                NOON,
                NOON.plus(Duration.ofMinutes(50)),
                promised,
                300,
                1,
                NOON.plus(Duration.ofMinutes(5)),
                pickupWindowEnd,
                0,
                false,
                null,
                null));
        assertThat(lateCourier.onTimeOutcome()).isEqualTo(OnTimeOutcome.LATE);

        // An absent promise is the platform's failure. Neutral pay, not a guess.
        DeliveredShipment unpromised = deliveredShipment();
        EarningRow noPromise = accruals.recordDelivery(new CourierAccrualService.DeliveredAssignment(
                TENANT,
                BRAND,
                branch,
                courierId,
                null,
                unpromised.shipmentId(),
                unpromised.attemptId(),
                4000,
                DistanceSource.ROUTING,
                NOON,
                NOON.plus(Duration.ofMinutes(50)),
                null,
                300,
                1,
                null,
                null,
                0,
                false,
                null,
                null));
        assertThat(noPromise.onTimeOutcome()).isEqualTo(OnTimeOutcome.UNKNOWN);
        assertThat(noPromise.totalMinor()).isEqualTo(lateCourier.totalMinor());
    }

    // ------------------------------------------------------ registration lapse

    @Test
    @DisplayName("a lapsed registration stops new offers and shift opening, and reverses nothing")
    void aLapsedRegistrationStopsOffersAndShiftsAndReversesNothing() {
        // The night before: an assignment accepted and delivered while valid.
        EarningRow beforeLapse = accruals.recordDelivery(delivery(deliveredShipment(), 5000, 0));
        long balanceBefore = ledgerStore.balanceMinor(TENANT, courierId);
        assertThat(balanceBefore).isEqualTo(beforeLapse.totalMinor());

        // Morning: the registration runs out and the sweeper notices.
        expireRegistration();
        clock.set(NOON.plus(Duration.ofDays(2)));
        RegistrationComplianceSweeper.SweepResult result = sweeper.sweep();

        assertThat(result.engagementsSuspended()).isEqualTo(1);
        assertThat(notifications.lapses).hasSize(1);
        assertThat(courierStore
                        .findEngagement(TENANT, engagementId)
                        .orElseThrow()
                        .status())
                .isEqualTo(EngagementStatus.SUSPENDED_COMPLIANCE);

        // Afternoon: no offer, and no shift.
        CourierDispatchGate.Eligibility eligibility = gate.evaluate(TENANT, BRAND, branch, courierId, 4000);
        assertThat(eligibility.eligible()).isFalse();
        assertThat(eligibility.refusals()).contains("REGISTRATION_LAPSED");

        Throwable refused = catchThrowable(() -> shifts.open(new CourierShiftService.OpenShift(
                TENANT, BRAND, branch, courierId, ShiftActor.COURIER, courier(), "opening", null, UZS)));
        assertThat(refused).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refused).errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE);

        // And the money from last night is exactly where it was.
        assertThat(ledgerStore.balanceMinor(TENANT, courierId)).isEqualTo(balanceBefore);
    }

    @Test
    @DisplayName("work done after a lapse is still paid, and the statement says so")
    void workDoneAfterALapseIsStillPaidAndFlagged() {
        expireRegistration();
        clock.set(NOON.plus(Duration.ofDays(2)));
        sweeper.sweep();

        // An assignment accepted before the lapse finishes afterwards. Nothing
        // strands an order mid-delivery.
        EarningRow afterLapse = accruals.recordDelivery(delivery(deliveredShipment(), 5000, 0));
        assertThat(afterLapse.totalMinor()).isPositive();

        PeriodRow period = ledgerStore.findOpenPeriod(TENANT, courierId).orElseThrow();
        CourierSettlementService.Statement statement =
                settlement.close(TENANT, period.id(), manager(), "closing the period");

        assertThat(statement.complianceFlag()).isTrue();
        assertThat(statement.totals().grossEarningsMinor()).isEqualTo(afterLapse.totalMinor());

        @SuppressWarnings("unchecked")
        Map<String, Object> compliance = (Map<String, Object>)
                Objects.requireNonNull(statement.document().get("compliance"));
        assertThat(compliance.get("flag")).isEqualTo(true);
        assertThat((List<?>) compliance.get("affectedEntryIds")).hasSize(1);
    }

    @Test
    @DisplayName("a payout for a period carrying the compliance flag is refused without approval")
    void aFlaggedPeriodsPayoutNeedsFourEyes() {
        expireRegistration();
        clock.set(NOON.plus(Duration.ofDays(2)));
        sweeper.sweep();
        accruals.recordDelivery(delivery(deliveredShipment(), 5000, 0));

        PeriodRow period = ledgerStore.findOpenPeriod(TENANT, courierId).orElseThrow();
        settlement.close(TENANT, period.id(), manager(), "closing");

        approvals.answer = new ApprovalOutcome.Pending(UUID.randomUUID());
        CourierSettlementService.PayoutOutcome pending =
                settlement.authorisePayout(TENANT, period.id(), PayoutMethod.CASH_AT_BRANCH, manager(), "paying");

        assertThat(pending.authorised()).isFalse();
        assertThat(ledgerStore.findPayout(TENANT, period.id())).isEmpty();
        assertThat(ledgerStore.findPeriod(TENANT, period.id()).orElseThrow().status())
                .isEqualTo(SettlementPeriodStatus.CLOSED);

        approvals.answer =
                new ApprovalOutcome.Approved(UUID.randomUUID(), "another-manager", approvals.consumed::incrementAndGet);
        CourierSettlementService.PayoutOutcome authorised =
                settlement.authorisePayout(TENANT, period.id(), PayoutMethod.CASH_AT_BRANCH, manager(), "paying");

        assertThat(authorised.authorised()).isTrue();
        assertThat(ledgerStore.findPayout(TENANT, period.id())).isPresent();
    }

    @Test
    @DisplayName("the ladder rings once per rung and escalates to the manager at fourteen days")
    void theWarningLadderRingsOncePerRung() {
        // Twenty days left: inside the thirty-day window, outside fourteen.
        setReverificationDue(LocalDate.ofInstant(NOON, ZoneOffset.UTC).plusDays(20));
        sweeper.sweep();
        sweeper.sweep();

        assertThat(notifications.warnings).hasSize(1);
        assertThat(notifications.warnings.getFirst().audience()).isEqualTo(CourierNotificationPort.Audience.COURIER);

        setReverificationDue(LocalDate.ofInstant(NOON, ZoneOffset.UTC).plusDays(10));
        sweeper.sweep();
        assertThat(notifications.warnings).hasSize(3);
        assertThat(notifications.warnings)
                .anyMatch(warning -> warning.audience() == CourierNotificationPort.Audience.MANAGER);
    }

    // ----------------------------------------------------------------- shifts

    @Test
    @DisplayName("a manager cannot open a shift and cannot end a break")
    void aManagerCannotOpenAShiftOrEndABreak() {
        Throwable opening = catchThrowable(() -> shifts.open(new CourierShiftService.OpenShift(
                TENANT, BRAND, branch, courierId, ShiftActor.MANAGER, manager(), "covering", null, UZS)));
        assertThat(opening).isInstanceOf(ApiException.class);
        assertThat(((ApiException) opening).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CAPABILITY);

        ShiftRow shift = openShift();
        shifts.startBreak(TENANT, shift.id(), ShiftActor.COURIER, courier(), "lunch");

        Throwable endingBreak = catchThrowable(
                () -> shifts.endBreak(TENANT, shift.id(), ShiftActor.MANAGER, manager(), "get back to work"));
        assertThat(endingBreak).isInstanceOf(ApiException.class);
        assertThat(((ApiException) endingBreak).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CAPABILITY);

        // And the database refuses it too, so a future refactor cannot lose it.
        Throwable directInsert = catchThrowable(() -> jdbc.sql("""
                INSERT INTO fulfillment.courier_shifts (
                    id, tenant_id, brand_id, location_id, courier_id, engagement_id, status,
                    duty_state, opened_at, open_source, enforcement_mode, version)
                VALUES (:id, :tenantId, :brandId, :locationId, :courierId, :engagementId, 'OPEN',
                    'AVAILABLE', now(), 'MANAGER', 'ADVISORY', 1)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("courierId", courierId)
                .param("engagementId", engagementId)
                .update());
        assertThat(directInsert).hasMessageContaining("ck_shift_open_source");
    }

    @Test
    @DisplayName("a manager close records a reason and an audit fact, and the hours await approval")
    void aManagerCloseRecordsAReasonAndAwaitsApproval() {
        ShiftRow shift = openShift();
        clock.set(NOON.plus(Duration.ofHours(4)));

        CourierShiftService.CloseOutcome outcome = shifts.close(new CourierShiftService.CloseShift(
                TENANT,
                shift.id(),
                ShiftActor.MANAGER,
                manager(),
                "PREMISES_CLOSING",
                "The branch closed early",
                null,
                UZS));

        assertThat(outcome.status()).isEqualTo(ShiftStatus.AWAITING_APPROVAL);
        assertThat(outcome.paidSeconds()).isEqualTo(Duration.ofHours(4).toSeconds());
        assertThat(shiftStore.findShift(TENANT, shift.id()).orElseThrow().closeReasonCode())
                .isEqualTo("PREMISES_CLOSING");
        assertThat(audit.facts).anyMatch(fact -> fact.actionCode().equals("courier.shift.closed"));

        // Nothing is paid for the shift until a manager approves the hours.
        assertThat(entriesOfType(LedgerEntryType.SHIFT_EARNING)).isEmpty();
        shifts.approveHours(TENANT, shift.id(), UUID.randomUUID(), manager(), "hours look right");
        assertThat(entriesOfType(LedgerEntryType.SHIFT_EARNING)).hasSize(1);
    }

    @Test
    @DisplayName("break seconds are not paid, and a shift spent on break earns no fixed component")
    void breakSecondsAreNotPaid() {
        ShiftRow shift = openShift();
        shifts.startBreak(TENANT, shift.id(), ShiftActor.COURIER, courier(), "lunch");
        clock.set(NOON.plus(Duration.ofHours(3)));
        shifts.endBreak(TENANT, shift.id(), ShiftActor.COURIER, courier(), "back");
        clock.set(NOON.plus(Duration.ofHours(3).plusMinutes(10)));

        CourierShiftService.CloseOutcome outcome = shifts.close(new CourierShiftService.CloseShift(
                TENANT, shift.id(), ShiftActor.COURIER, courier(), null, "done", null, UZS));

        assertThat(outcome.breakSeconds()).isEqualTo(Duration.ofHours(3).toSeconds());
        assertThat(outcome.paidSeconds()).isEqualTo(Duration.ofMinutes(10).toSeconds());
        // The fixed component demands 3600 paid seconds; ten minutes is not it.
        assertThat(entriesOfType(LedgerEntryType.SHIFT_EARNING)).isEmpty();
    }

    @Test
    @DisplayName("an off-shift courier is refused under ENFORCED and allowed under OFF, and the "
            + "stored snapshot does not move when the policy does")
    void shiftEnforcementGatesOffersAndIsSnapshotted() {
        policies.enforcement = ShiftEnforcement.ENFORCED;
        assertThat(gate.evaluate(TENANT, BRAND, branch, courierId, 4000).refusals())
                .contains("NO_OPEN_SHIFT");
        assertThat(gate.evaluate(TENANT, BRAND, branch, courierId, 4000).eligible())
                .isFalse();

        policies.enforcement = ShiftEnforcement.OFF;
        assertThat(gate.evaluate(TENANT, BRAND, branch, courierId, 4000).eligible())
                .isTrue();

        policies.enforcement = ShiftEnforcement.ADVISORY;
        ShiftRow shift = openShift();
        assertThat(shift.enforcementMode()).isEqualTo(ShiftEnforcement.ADVISORY);

        // Tightening the policy afterwards must not restate what already happened.
        policies.enforcement = ShiftEnforcement.ENFORCED;
        assertThat(shiftStore.findShift(TENANT, shift.id()).orElseThrow().enforcementMode())
                .isEqualTo(ShiftEnforcement.ADVISORY);
    }

    // ------------------------------------------------------------------- cash

    @Test
    @DisplayName("a courier cannot declare another courier's cash handover")
    void aCourierCannotDeclareAnotherCouriersCash() {
        ShiftRow shift = openShift();
        accruals.recordDelivery(deliveryOnShift(shift.id(), 4000, 120_000));
        clock.set(NOON.plus(Duration.ofHours(5)));

        CourierShiftService.CloseOutcome outcome = shifts.close(new CourierShiftService.CloseShift(
                TENANT, shift.id(), ShiftActor.COURIER, courier(), null, "done", null, UZS));
        UUID handoverId = Objects.requireNonNull(outcome.cashHandoverId());

        Throwable refused =
                catchThrowable(() -> cash.declare(TENANT, handoverId, UUID.randomUUID(), 120_000, courier()));

        assertThat(refused).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refused).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(shiftStore.findHandover(TENANT, handoverId).orElseThrow().status())
                .as("the ownership refusal happens before the handover is mutated")
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("cash collected, declared, and confirmed reconcile to zero; a mismatch becomes a "
            + "variance entry rather than a silently adjusted figure")
    void cashReconcilesOrRaisesAnExplicitVariance() {
        ShiftRow shift = openShift();
        accruals.recordDelivery(deliveryOnShift(shift.id(), 4000, 120_000));
        clock.set(NOON.plus(Duration.ofHours(5)));

        CourierShiftService.CloseOutcome outcome = shifts.close(new CourierShiftService.CloseShift(
                TENANT, shift.id(), ShiftActor.COURIER, courier(), null, "done", null, UZS));
        assertThat(outcome.cashHandoverId()).isNotNull();
        UUID handoverId = Objects.requireNonNull(outcome.cashHandoverId());

        cash.declare(TENANT, handoverId, courierId, 120_000, courier());
        cash.confirm(TENANT, handoverId, 120_000, null, cashier(), "counted");

        long cashPosition =
                ledgerStore
                        .entriesOf(
                                TENANT,
                                ledgerStore
                                        .findOpenPeriod(TENANT, courierId)
                                        .orElseThrow()
                                        .id())
                        .stream()
                        .filter(entry -> entry.entryType().isCash())
                        .mapToLong(LedgerEntryRow::amountMinor)
                        .sum();
        assertThat(cashPosition).isZero();

        // Now a shift that comes up short.
        ShiftRow second = openShiftAt(NOON.plus(Duration.ofHours(6)));
        accruals.recordDelivery(deliveryOnShift(second.id(), 4000, 90_000));
        clock.set(NOON.plus(Duration.ofHours(10)));
        CourierShiftService.CloseOutcome shortfall = shifts.close(new CourierShiftService.CloseShift(
                TENANT, second.id(), ShiftActor.COURIER, courier(), null, "done", null, UZS));

        UUID shortfallHandoverId = Objects.requireNonNull(shortfall.cashHandoverId());
        cash.declare(TENANT, shortfallHandoverId, courierId, 85_000, courier());
        cash.confirm(TENANT, shortfallHandoverId, 85_000, "SHORT_AT_COUNT", cashier(), "five thousand short");

        List<LedgerEntryRow> variances = entriesOfType(LedgerEntryType.CASH_VARIANCE);
        assertThat(variances).hasSize(1);
        assertThat(variances.getFirst().amountMinor()).isEqualTo(5_000);
        assertThat(variances.getFirst().reasonCode()).isEqualTo("SHORT_AT_COUNT");
    }

    @Test
    @DisplayName("a cash variance without a reason code is refused")
    void aCashVarianceNeedsAReasonCode() {
        ShiftRow shift = openShift();
        accruals.recordDelivery(deliveryOnShift(shift.id(), 4000, 50_000));
        clock.set(NOON.plus(Duration.ofHours(2)));
        CourierShiftService.CloseOutcome outcome = shifts.close(new CourierShiftService.CloseShift(
                TENANT, shift.id(), ShiftActor.COURIER, courier(), null, "done", null, UZS));

        UUID handoverId = Objects.requireNonNull(outcome.cashHandoverId());
        cash.declare(TENANT, handoverId, courierId, 40_000, courier());
        assertThat(catchThrowable(() -> cash.confirm(TENANT, handoverId, 40_000, null, cashier(), "counted")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reason code");
    }

    // ------------------------------------------------------------ adjustments

    @Test
    @DisplayName("a manual penalty without approval is refused, and the ledger refuses it too")
    void aManualPenaltyWithoutApprovalIsRefused() {
        approvals.answer = new ApprovalOutcome.Pending(UUID.randomUUID());

        CourierAdjustmentService.Outcome outcome = adjustments.request(new CourierAdjustmentService.AdjustmentCommand(
                TENANT,
                courierId,
                branch,
                -50_000,
                UZS,
                "ORDER_UNDELIVERED",
                AdjustmentOrigin.MANUAL,
                "penalty-1",
                manager(),
                "the order never arrived",
                "corr"));

        assertThat(outcome.written()).isFalse();
        assertThat(entriesOfType(LedgerEntryType.PENALTY)).isEmpty();

        // And a caller going round the service still cannot write one.
        Throwable direct = catchThrowable(() -> ledger.append(new CourierLedgerService.NewEntry(
                TENANT,
                courierId,
                branch,
                LedgerEntryType.PENALTY,
                -50_000,
                UZS,
                "courier_adjustment",
                null,
                AdjustmentOrigin.MANUAL,
                "ORDER_UNDELIVERED",
                clock.instant(),
                "penalty-direct",
                null,
                null,
                "manager")));
        assertThat(direct).isInstanceOf(ApiException.class);

        approvals.answer =
                new ApprovalOutcome.Approved(UUID.randomUUID(), "another-manager", approvals.consumed::incrementAndGet);
        CourierAdjustmentService.Outcome approved = adjustments.request(new CourierAdjustmentService.AdjustmentCommand(
                TENANT,
                courierId,
                branch,
                -50_000,
                UZS,
                "ORDER_UNDELIVERED",
                AdjustmentOrigin.MANUAL,
                "penalty-2",
                manager(),
                "the order never arrived",
                "corr"));
        assertThat(approved.written()).isTrue();
        assertThat(Objects.requireNonNull(approved.entry()).approvalRequestId()).isNotNull();
    }

    @Test
    @DisplayName("every adjustment reason names a delivery outcome, and free text cannot be one")
    void everyAdjustmentReasonNamesADeliveryOutcome() {
        Throwable behaviouralReason = catchThrowable(() -> courierStore.insertAdjustmentReason(
                UUID.randomUUID(), TENANT, "RUDE_TO_CUSTOMER", "PENALTY", "ATTITUDE", "Rude to the customer"));

        assertThat(behaviouralReason).hasMessageContaining("ck_adjustment_reason_basis");
    }

    // ------------------------------------------------------------- settlement

    @Test
    @DisplayName(
            "the statement carries gross only, and its transfer amount is gross plus " + "adjustments less cash held")
    void theStatementCarriesGrossOnly() {
        ShiftRow shift = openShift();
        accruals.recordDelivery(deliveryOnShift(shift.id(), 6000, 80_000));
        clock.set(NOON.plus(Duration.ofHours(5)));
        CourierShiftService.CloseOutcome outcome = shifts.close(new CourierShiftService.CloseShift(
                TENANT, shift.id(), ShiftActor.COURIER, courier(), null, "done", null, UZS));
        cash.declare(TENANT, Objects.requireNonNull(outcome.cashHandoverId()), courierId, 80_000, courier());
        // The courier keeps the cash: nothing is confirmed, so he is still holding it.

        PeriodRow period = ledgerStore.findOpenPeriod(TENANT, courierId).orElseThrow();
        CourierSettlementService.Statement statement = settlement.close(TENANT, period.id(), manager(), "closing");

        Map<String, Object> document = statement.document();
        assertThat(document).containsKey("grossTotalMinor");
        assertThat(document).containsKey("amountToTransferMinor");
        assertThat(flattenKeys(document))
                .as("no field named or labelled as withholding or net of tax")
                .noneMatch(key -> key.toLowerCase(Locale.ROOT).contains("withhold")
                        || key.toLowerCase(Locale.ROOT).equals("net")
                        || key.toLowerCase(Locale.ROOT).startsWith("net")
                        || key.toLowerCase(Locale.ROOT).contains("payslip"));

        PeriodRow closed = ledgerStore.findPeriod(TENANT, period.id()).orElseThrow();
        assertThat(closed.amountPayableMinor())
                .isEqualTo(closed.grossEarningsMinor() + closed.adjustmentsMinor() - closed.cashHeldMinor());
        assertThat(closed.cashHeldMinor()).isEqualTo(80_000);

        // Every figure is the sum of its ledger lines, with no rounding remainder.
        long ledgerGross = ledgerStore.entriesOf(TENANT, period.id()).stream()
                .filter(entry -> entry.entryType().isGrossEarning())
                .mapToLong(LedgerEntryRow::amountMinor)
                .sum();
        assertThat(closed.grossEarningsMinor()).isEqualTo(ledgerGross);
        assertThat(closed.statementHash()).hasSize(64);
    }

    @Test
    @DisplayName("a statement document that used tax language would be refused before it is hashed")
    void aStatementUsingTaxLanguageIsRefused() {
        assertThat(catchThrowable(
                        () -> uz.horecaos.platform.courier.domain.StatementVocabulary.assertCarriesNoTaxLanguage(
                                Map.of("netPayableMinor", 1))))
                .isInstanceOf(uz.horecaos.platform.courier.domain.StatementVocabulary.TaxLanguageException.class);
        assertThat(catchThrowable(
                        () -> uz.horecaos.platform.courier.domain.StatementVocabulary.assertCarriesNoTaxLanguage(
                                Map.of("withholdingMinor", 1))))
                .isInstanceOf(uz.horecaos.platform.courier.domain.StatementVocabulary.TaxLanguageException.class);
    }

    @Test
    @DisplayName("a closed period's totals are unchanged by an entry recorded afterwards, which "
            + "lands in the next period as a prior-period adjustment")
    void aClosedPeriodIsNeverRestated() {
        EarningRow earning = accruals.recordDelivery(delivery(deliveredShipment(), 4000, 0));
        PeriodRow period = ledgerStore.findOpenPeriod(TENANT, courierId).orElseThrow();
        settlement.close(TENANT, period.id(), manager(), "closing");
        long payableAtClose =
                ledgerStore.findPeriod(TENANT, period.id()).orElseThrow().amountPayableMinor();

        UUID originalEntry = ledgerStore.entriesOf(TENANT, period.id()).stream()
                .filter(entry -> entry.entryType() == LedgerEntryType.DELIVERY_EARNING)
                .findFirst()
                .orElseThrow()
                .id();

        clock.set(NOON.plus(Duration.ofDays(1)));
        LedgerEntryRow adjustment = ledger.appendPriorPeriodAdjustment(
                TENANT, originalEntry, 2_500, "CORRECTED_DISTANCE", "operations", "ppa-1");

        assertThat(adjustment.entryType()).isEqualTo(LedgerEntryType.PRIOR_PERIOD_ADJUSTMENT);
        assertThat(adjustment.settlementPeriodId()).isNotEqualTo(period.id());
        // The original occurrence instant travels with it, so a report over
        // business dates still attributes the work to the day it happened.
        assertThat(adjustment.occurredAt()).isEqualTo(earning.deliveredAt());
        assertThat(ledgerStore.findPeriod(TENANT, period.id()).orElseThrow().amountPayableMinor())
                .isEqualTo(payableAtClose);
    }

    // ------------------------------------------------------------ cost paths

    @Test
    @DisplayName("a shipment booked with a partner, cancelled at a fee, and delivered in-house "
            + "carries two cost lines and its total is their sum")
    void oneShipmentCarriesBothCostPaths() {
        DeliveredShipment carried = deliveredShipment();
        partnerInvoices.recordPartnerCost(
                TENANT,
                carried.shipmentId(),
                "NOOR",
                18_000,
                UZS,
                LocalDate.ofInstant(NOON, ZoneOffset.UTC),
                null,
                PartnerChargeType.CANCELLATION,
                "sourcing");
        EarningRow inHouse = accruals.recordDelivery(delivery(carried, 4000, 0));

        List<CostLineRow> lines = deliveryCosts.linesOf(TENANT, carried.shipmentId());
        assertThat(lines).hasSize(2);
        assertThat(lines)
                .extracting(CostLineRow::costPath)
                .containsExactlyInAnyOrder(
                        uz.horecaos.platform.courier.domain.CostPath.PARTNER,
                        uz.horecaos.platform.courier.domain.CostPath.INTERNAL);
        assertThat(lines.stream().mapToLong(CostLineRow::amountMinor).sum()).isEqualTo(18_000 + inHouse.totalMinor());
    }

    @Test
    @DisplayName("a delivery-cost query without a basis is rejected, and one at INVOICED excludes "
            + "internal accruals and counts them rather than omitting them")
    void aDeliveryCostQueryMustStateItsBasis() {
        LocalDate today = LocalDate.ofInstant(NOON, ZoneOffset.UTC);
        accruals.recordDelivery(delivery(deliveredShipment(), 4000, 0));

        UUID partnerShipment = deliveredShipment().shipmentId();
        partnerInvoices.recordPartnerCost(
                TENANT, partnerShipment, "NOOR", 22_000, UZS, today, null, PartnerChargeType.DELIVERY, "sourcing");

        assertThat(catchThrowable(() -> deliveryCosts.report(TENANT, null, today, today)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("single basis");

        DeliveryCostQueryService.CostReport accrued = deliveryCosts.report(TENANT, CostBasis.ACCRUED, today, today);
        assertThat(accrued.internalMinor()).isPositive();
        assertThat(accrued.partnerMinor()).isEqualTo(22_000);
        assertThat(accrued.totalMinor()).isEqualTo(accrued.internalMinor() + accrued.partnerMinor());

        // The invoice arrives, so the partner line exists at INVOICED and the
        // internal accrual does not.
        UUID invoiceId = partnerInvoices.importInvoice(new PartnerInvoiceService.ImportInvoice(
                TENANT,
                "NOOR",
                "INV-1",
                null,
                today,
                today,
                22_000,
                UZS,
                List.of(new PartnerInvoiceService.ImportedLine("NOOR-1", 22_000, PartnerChargeType.DELIVERY)),
                manager(),
                "importing"));
        partnerInvoices.match(TENANT, invoiceId, Map.of("NOOR-1", partnerShipment), manager(), "matching");

        DeliveryCostQueryService.CostReport invoiced = deliveryCosts.report(TENANT, CostBasis.INVOICED, today, today);
        assertThat(invoiced.internalMinor()).isZero();
        assertThat(invoiced.partnerMinor()).isEqualTo(22_000);
        assertThat(invoiced.shipmentsWithoutThisBasis())
                .as("the internal accrual is counted, not silently dropped")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a partner invoice line with no matching shipment surfaces as UNMATCHED_LINE and "
            + "is not netted into any total")
    void anUnmatchedPartnerLineIsNeverNetted() {
        LocalDate today = LocalDate.ofInstant(NOON, ZoneOffset.UTC);
        UUID known = deliveredShipment().shipmentId();
        partnerInvoices.recordPartnerCost(
                TENANT, known, "NOOR", 20_000, UZS, today, null, PartnerChargeType.DELIVERY, "sourcing");

        UUID invoiceId = partnerInvoices.importInvoice(new PartnerInvoiceService.ImportInvoice(
                TENANT,
                "NOOR",
                "INV-2",
                null,
                today,
                today,
                45_000,
                UZS,
                List.of(
                        new PartnerInvoiceService.ImportedLine("NOOR-KNOWN", 20_000, PartnerChargeType.DELIVERY),
                        new PartnerInvoiceService.ImportedLine("NOOR-PHANTOM", 25_000, PartnerChargeType.DELIVERY)),
                manager(),
                "importing"));

        PartnerInvoiceService.MatchReport report =
                partnerInvoices.match(TENANT, invoiceId, Map.of("NOOR-KNOWN", known), manager(), "matching");

        assertThat(report.matchedLines()).isEqualTo(1);
        assertThat(report.unmatchedLineIds()).hasSize(1);

        DeliveryCostQueryService.CostReport invoiced = deliveryCosts.report(TENANT, CostBasis.INVOICED, today, today);
        assertThat(invoiced.partnerMinor())
                .as("the phantom line is reported, never added")
                .isEqualTo(20_000);
    }

    // --------------------------------------------------- privacy and retention

    @Test
    @DisplayName("no confirmation coordinate is readable after the retention window, while every "
            + "figure computed from one is unchanged")
    void confirmationCoordinatesAreDeletedAfterSettlement() {
        DeliveredShipment carried = deliveredShipment();
        EarningRow earning = accruals.recordDelivery(new CourierAccrualService.DeliveredAssignment(
                TENANT,
                BRAND,
                branch,
                courierId,
                null,
                carried.shipmentId(),
                carried.attemptId(),
                4200,
                DistanceSource.ROUTING,
                NOON,
                NOON.plus(Duration.ofMinutes(20)),
                NOON.plus(Duration.ofMinutes(40)),
                300,
                1,
                NOON.plus(Duration.ofMinutes(5)),
                NOON.plus(Duration.ofMinutes(10)),
                0,
                true,
                "41.31,69.24",
                "41.32,69.27"));

        assertThat(earning.protectedDeliveryPoint()).isNotNull();
        long total = earning.totalMinor();

        PeriodRow period = ledgerStore.findOpenPeriod(TENANT, courierId).orElseThrow();
        settlement.close(TENANT, period.id(), manager(), "closing");
        approvals.answer = new ApprovalOutcome.NotRequired();
        settlement.authorisePayout(TENANT, period.id(), PayoutMethod.BANK_TRANSFER, manager(), "paying");

        // Twenty-nine days later: still readable, because a dispute may still arrive.
        clock.set(NOON.plus(Duration.ofDays(29)));
        assertThat(retention.purge(CourierCompensationPolicy.DEFAULTS)).isZero();

        clock.set(NOON.plus(Duration.ofDays(31)));
        assertThat(retention.purge(CourierCompensationPolicy.DEFAULTS)).isEqualTo(1);

        EarningRow afterPurge = ledgerStore
                .findEarningByAttempt(TENANT, earning.assignmentAttemptId())
                .orElseThrow();
        assertThat(afterPurge.protectedPickupPoint()).isNull();
        assertThat(afterPurge.protectedDeliveryPoint()).isNull();
        assertThat(afterPurge.pointsPurgedAt()).isNotNull();

        // Everything the accrual was computed from survives.
        assertThat(afterPurge.distanceMeters()).isEqualTo(4200);
        assertThat(afterPurge.distanceSource()).isEqualTo(DistanceSource.ROUTING);
        assertThat(afterPurge.onTimeOutcome()).isEqualTo(OnTimeOutcome.ON_TIME);
        assertThat(afterPurge.geoUnverified()).isTrue();
        assertThat(afterPurge.totalMinor()).isEqualTo(total);
    }

    @Test
    @DisplayName("the registration identifier is never in an ordinary projection and its reveal is " + "audited")
    void theRegistrationIdentifierIsOnlyReachableThroughAnAuditedReveal() {
        EngagementRow engagement =
                courierStore.findEngagement(TENANT, engagementId).orElseThrow();
        assertThat(engagement.protectedRegistrationRef())
                .as("the ordinary projection does not select the ciphertext at all")
                .isNull();

        String revealed = engagements.revealRegistrationIdentifier(
                TENANT, engagementId, "Accountant export for the August settlement", manager(), "corr");

        assertThat(revealed).isEqualTo("312345678901");
        assertThat(audit.facts).anyMatch(fact -> fact.actionCode().equals("courier.registration.revealed"));
        assertThat(audit.facts)
                .as("no audit change document carries the identifier")
                .noneMatch(fact -> fact.changeDocument().values().stream()
                        .map(String::valueOf)
                        .anyMatch(value -> value.contains("312345678901")));
    }

    // ------------------------------------------------------- ledger integrity

    @Test
    @DisplayName("the application role can insert and select ledger entries and fails on update " + "and delete")
    void theLedgerIsAppendOnlyForTheApplicationRole() {
        accruals.recordDelivery(delivery(deliveredShipment(), 4000, 0));

        // A role membership belongs to the cluster, not to this suite's database,
        // and the cluster now outlives the class — so what this line grants would
        // otherwise stay granted for the rest of the JVM, in the one process where
        // another suite's whole subject is what a role may do. The finally below
        // puts it back.
        jdbc.sql("GRANT horecaos_application TO " + username).update();
        try {
            // One connection for the whole check. Every JdbcClient call takes its own
            // connection from the pool, so a SET ROLE issued through it would be
            // discarded before the next statement ran — and the test would pass by
            // never having dropped privileges at all.
            try (java.sql.Connection connection = dataSource.getConnection();
                    java.sql.Statement statement = connection.createStatement()) {

                statement.execute("SET ROLE horecaos_application");
                try {

                    assertThat(catchThrowable(() -> statement.executeUpdate(
                                    "UPDATE fulfillment.courier_ledger_entries SET amount_minor = 1")))
                            .as("a history that can be edited is not one")
                            .hasMessageContaining("permission denied");
                    assertThat(catchThrowable(
                                    () -> statement.executeUpdate("DELETE FROM fulfillment.courier_ledger_entries")))
                            .hasMessageContaining("permission denied");

                    try (java.sql.ResultSet counted =
                            statement.executeQuery("SELECT count(*) FROM fulfillment.courier_ledger_entries")) {
                        assertThat(counted.next()).isTrue();
                        assertThat(counted.getInt(1)).isPositive();
                    }

                    // And the statement it produced is equally beyond reach.
                    assertThat(catchThrowable(() ->
                                    statement.executeUpdate("DELETE FROM fulfillment.courier_settlement_statements")))
                            .hasMessageContaining("permission denied");
                } finally {
                    // The pool hands this connection to whoever asks next, exactly
                    // as it is. SET ROLE is session state and survives being
                    // returned, so without this the following test runs as
                    // horecaos_application and fails on a table it never meant to
                    // touch — which is precisely what happened when the suite moved
                    // off DriverManagerDataSource, where every close was a physical
                    // disconnect and the role went with it.
                    statement.execute("RESET ROLE");
                }
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("Could not run as the application role", failure);
            }
        } finally {
            jdbc.sql("REVOKE horecaos_application FROM " + username).update();
        }
    }

    @Test
    @DisplayName("a courier's ledger query cannot reach another courier's entries")
    void oneCouriersLedgerIsNotAnothers() {
        accruals.recordDelivery(delivery(deliveredShipment(), 4000, 0));

        UUID otherCourier = UUID.randomUUID();
        courierStore.insertCourier(new JdbcCourierStore.CourierRow(
                otherCourier, TENANT, courierTypeId, "keycloak-other", "K-002", "protected", "ACTIVE", 1));

        assertThat(ledgerStore.entriesOfCourier(TENANT, courierId, 100)).isNotEmpty();
        assertThat(ledgerStore.entriesOfCourier(TENANT, otherCourier, 100)).isEmpty();
        assertThat(ledgerStore.balanceMinor(TENANT, otherCourier)).isZero();

        // And another tenant asking about this courier gets nothing.
        assertThat(ledgerStore.entriesOfCourier(UUID.randomUUID(), courierId, 100))
                .isEmpty();
    }

    @Test
    @DisplayName("the settlement period arithmetic is a database constraint, not a convention")
    void theTransferAmountCannotDisagreeWithItsComponents() {
        PeriodRow period = ledger.currentPeriod(TENANT, courierId, UZS, LocalDate.ofInstant(NOON, ZoneOffset.UTC));

        Throwable inconsistent =
                catchThrowable(() -> jdbc.sql("""
                UPDATE fulfillment.courier_settlement_periods
                   SET gross_earnings_minor = 100000, amount_payable_minor = 999999
                 WHERE id = :id
                """).param("id", period.id()).update());

        assertThat(inconsistent).hasMessageContaining("ck_period_payable");
    }

    // ------------------------------------------------------------------ setup

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'courier-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
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

        // A channel and a published menu, because a delivery plan hangs off an
        // order and an order hangs off both. Nothing here is asserted on; it is
        // the shortest chain that ends in an order a shipment may belong to.
        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status)
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
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash',
                        now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
    }

    private void seedCourier() {
        courierTypeId = UUID.randomUUID();
        courierStore.insertType(
                new CourierTypeRow(courierTypeId, TENANT, "SCOOTER", "Scooter", "SCOOTER", 0, 15_000, 2, 60, "ACTIVE"));

        CourierEngagementService.Registration registration =
                engagements.register(new CourierEngagementService.NewCourier(
                        TENANT,
                        courierTypeId,
                        "keycloak-courier",
                        "K-001",
                        "Alisher Karimov",
                        LocalDate.ofInstant(NOON, ZoneOffset.UTC),
                        manager(),
                        "onboarding a rider",
                        "corr"));
        courierId = registration.courierId();
        engagementId = registration.engagementId();

        engagements.verify(new CourierEngagementService.VerifyRegistration(
                TENANT,
                engagementId,
                "312345678901",
                LocalDate.ofInstant(NOON, ZoneOffset.UTC).plusYears(1),
                VerificationMethod.MANUAL_ATTESTATION,
                null,
                manager(),
                "sighted the registration certificate",
                "corr"));
    }

    private void seedRateCard() {
        rateCardId = rateCards.author(new CourierRateCardService.NewRateCard(
                TENANT,
                BRAND,
                null,
                null,
                "STANDARD",
                1,
                UZS,
                List.of(
                        new RateComponent(UUID.randomUUID(), RateComponentType.PER_ORDER, 0, 3_000, null, null, null),
                        band(0, 3000, 2000),
                        band(3000, null, 1500),
                        new RateComponent(
                                UUID.randomUUID(), RateComponentType.PER_SHIFT_FIXED, 0, 10_000, null, null, 3600))));
        rateCards.activate(TENANT, rateCardId, manager(), "activating the standard card");
    }

    private void seedAdjustmentReasons() {
        courierStore.insertAdjustmentReason(
                UUID.randomUUID(),
                TENANT,
                "ORDER_UNDELIVERED",
                "PENALTY",
                "ORDER_UNDELIVERED",
                "The order was not delivered");
        courierStore.insertAdjustmentReason(
                UUID.randomUUID(),
                TENANT,
                "ON_TIME_STREAK",
                "BONUS",
                "ON_TIME_RATE",
                "Ten consecutive on-time deliveries");
    }

    private static RateComponent band(int from, @Nullable Integer to, long perKmMinor) {
        return new RateComponent(UUID.randomUUID(), RateComponentType.PER_KM_BAND, from, perKmMinor, from, to, null);
    }

    private ShiftRow openShift() {
        return shifts.open(new CourierShiftService.OpenShift(
                TENANT, BRAND, branch, courierId, ShiftActor.COURIER, courier(), "opening", null, UZS));
    }

    private ShiftRow openShiftAt(Instant at) {
        clock.set(at);
        return openShift();
    }

    private CourierAccrualService.DeliveredAssignment delivery(
            DeliveredShipment carried, int distanceMeters, long cashMinor) {

        return new CourierAccrualService.DeliveredAssignment(
                TENANT,
                BRAND,
                branch,
                courierId,
                null,
                carried.shipmentId(),
                carried.attemptId(),
                distanceMeters,
                DistanceSource.ROUTING,
                clock.instant(),
                clock.instant().plus(Duration.ofMinutes(20)),
                clock.instant().plus(Duration.ofMinutes(40)),
                300,
                1,
                clock.instant().plus(Duration.ofMinutes(5)),
                clock.instant().plus(Duration.ofMinutes(10)),
                cashMinor,
                false,
                null,
                null);
    }

    private CourierAccrualService.DeliveredAssignment deliveryOnShift(
            UUID shiftId, int distanceMeters, long cashMinor) {

        DeliveredShipment carried = deliveredShipment();
        return new CourierAccrualService.DeliveredAssignment(
                TENANT,
                BRAND,
                branch,
                courierId,
                shiftId,
                carried.shipmentId(),
                carried.attemptId(),
                distanceMeters,
                DistanceSource.ROUTING,
                clock.instant(),
                clock.instant().plus(Duration.ofMinutes(20)),
                clock.instant().plus(Duration.ofMinutes(40)),
                300,
                1,
                clock.instant().plus(Duration.ofMinutes(5)),
                clock.instant().plus(Duration.ofMinutes(10)),
                cashMinor,
                false,
                null,
                null);
    }

    /**
     * A delivery plan, the shipment that carried it, and the accepted attempt
     * that produced the shipment (ADR 0014, V0054).
     *
     * <p>Fabricated ids did until V0054 turned
     * {@code courier_assignment_earnings.shipment_id} and its attempt column
     * into real references: an earning is earned by delivering a shipment, and
     * one naming a shipment nobody can find is a payment for nothing.
     *
     * <p>Each call builds its own order, because ADR 0014's single-winner
     * indexes permit one live plan per order, one uncancelled shipment per plan
     * and one accepted attempt per plan. Two chains sharing any of them would be
     * two couriers sent to one address, which is the thing those indexes exist
     * to make unwritable.
     */
    private DeliveredShipment deliveredShipment() {
        int sequence = ++chainSequence;
        UUID orderId = seedDeliveryOrder(sequence);
        UUID planId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime confirmedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        // Every instant is derived from one anchor so the plan's window, promise
        // and latest-assignment ordering hold whatever the clock has been moved
        // to by the test that is asking.
        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (
                    id, tenant_id, brand_id, location_id, order_id, status, sourcing_mode,
                    service_level, customer_delivery_fee_minor, currency, confirmed_at,
                    preparation_seconds, estimated_ready_at, pickup_window_start,
                    pickup_window_end, promised_delivery_start, promised_delivery_end,
                    source_at, latest_assignment_at, branch_zone)
                SELECT :id, :tenantId, :brandId, :locationId, :orderId, 'COMPLETED', 'FLEET_FIRST',
                       'STANDARD', 12000, 'UZS', anchor, 900,
                       anchor + interval '15 minutes', anchor + interval '15 minutes',
                       anchor + interval '25 minutes', anchor + interval '30 minutes',
                       anchor + interval '45 minutes', anchor, anchor + interval '25 minutes',
                       'Asia/Tashkent'
                  FROM (SELECT CAST(:anchor AS timestamptz) AS anchor) AS moment
                """)
                .param("id", planId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("orderId", orderId)
                .param("anchor", confirmedAt)
                .update();

        jdbc.sql("""
                INSERT INTO fulfillment.shipments (
                    id, tenant_id, brand_id, location_id, order_id, delivery_plan_id, status,
                    source_type, courier_id, assigned_at, picked_up_at, delivered_at)
                SELECT :id, :tenantId, :brandId, :locationId, :orderId, :planId, 'DELIVERED',
                       'INTERNAL', :courierId, anchor + interval '5 minutes',
                       anchor + interval '20 minutes', anchor + interval '40 minutes'
                  FROM (SELECT CAST(:anchor AS timestamptz) AS anchor) AS moment
                """)
                .param("id", shipmentId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("orderId", orderId)
                .param("planId", planId)
                .param("courierId", courierId)
                .param("anchor", confirmedAt)
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
                .param("idempotencyKey", "fleet-offer-" + sequence)
                .param("anchor", confirmedAt)
                .update();

        return new DeliveredShipment(shipmentId, attemptId);
    }

    /**
     * The confirmed delivery order a plan belongs to. Checkout is ADR 0019's own
     * suite; what a delivery plan needs from an order is that it exists, is this
     * branch's, and was confirmed.
     */
    private UUID seedDeliveryOrder(int sequence) {
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String reference = "delivery-" + sequence;

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        50000, 0, 50000, now() + interval '1 hour')
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
                    approval_channel_snapshot, status, currency, subtotal_minor, tax_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'STOREFRONT',
                        :reference, 'DELIVERY', 'AUTO_CONFIRM', 0, 'NONE', 'COMPLETED', 'UZS',
                        50000, 0, 50000, :quoteId, 'hash', :publicationId, :cartId, :reference,
                        1, now())
                """)
                .param("id", orderId)
                .param("number", "D-" + sequence)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("channelId", channelId)
                .param("reference", reference)
                .param("quoteId", quoteId)
                .param("publicationId", publicationId)
                .param("cartId", cartId)
                .update();

        return orderId;
    }

    private record DeliveredShipment(UUID shipmentId, UUID attemptId) {}

    private List<LedgerEntryRow> entriesOfType(LedgerEntryType type) {
        return ledgerStore.entriesOfCourier(TENANT, courierId, 500).stream()
                .filter(entry -> entry.entryType() == type)
                .toList();
    }

    private void expireRegistration() {
        jdbc.sql("""
                UPDATE fulfillment.courier_engagements
                   SET registration_valid_until = :date, reverification_due_on = :date
                 WHERE id = :id
                """)
                .param("id", engagementId)
                .param("date", LocalDate.ofInstant(NOON, ZoneOffset.UTC).plusDays(1))
                .update();
    }

    private void setReverificationDue(LocalDate date) {
        jdbc.sql("""
                UPDATE fulfillment.courier_engagements
                   SET registration_valid_until = :date, reverification_due_on = :date
                 WHERE id = :id
                """).param("id", engagementId).param("date", date).update();
    }

    private static List<String> flattenKeys(Object node) {
        List<String> keys = new java.util.ArrayList<>();
        collectKeys(node, keys);
        return keys;
    }

    private static void collectKeys(Object node, List<String> keys) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                keys.add(String.valueOf(key));
                collectKeys(value, keys);
            });
        } else if (node instanceof Iterable<?> items) {
            items.forEach(item -> collectKeys(item, keys));
        }
    }

    private static ActorRef manager() {
        return ActorRef.user("keycloak-manager", "Branch manager");
    }

    private static ActorRef courier() {
        return ActorRef.user("keycloak-courier", "Courier");
    }

    private static ActorRef cashier() {
        return ActorRef.user("keycloak-cashier", "Cashier");
    }

    // ------------------------------------------------------------------ fakes

    /** A clock the tests move, because most of ADR 0042 is about elapsed time. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void set(Instant value) {
            this.now = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * Reversible rather than pass-through, so a plaintext accidentally written
     * to a protected column would be visible as plaintext in the assertions.
     */
    private static final class ReversibleProtection implements FieldProtection {

        @Override
        public ProtectedValue protect(UUID tenantId, DataClass dataClass, RecordRef record, String plaintext) {
            byte[] reversed =
                    new StringBuilder(plaintext).reverse().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return new ProtectedValue("test-key", "TEST", new byte[] {1}, reversed, 1);
        }

        @Override
        public String reveal(UUID tenantId, ProtectedValue value, RecordRef record, String purpose) {
            return new StringBuilder(new String(value.ciphertext(), java.nio.charset.StandardCharsets.UTF_8))
                    .reverse()
                    .toString();
        }

        @Override
        public String lookupHash(UUID tenantId, String lookupDomain, String normalizedValue) {
            return Integer.toHexString((tenantId + lookupDomain + normalizedValue).hashCode());
        }
    }

    private static final class RecordingAudit implements AuditRecorder {

        private final List<AuditFact> facts = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }
    }

    private static final class ConfigurableApprovals implements ApprovalService {

        /** Counts the call site spending the grant, which is what makes it single-use. */
        private final AtomicInteger consumed = new AtomicInteger();

        private ApprovalOutcome answer = new ApprovalOutcome.NotRequired();

        @Override
        public ApprovalOutcome requireApproval(ApprovalRequestCommand command) {
            return answer;
        }

        @Override
        public void decide(UUID requestId, Decision decision, ActorRef approver, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int expireOverdue() {
            return 0;
        }
    }

    /** Resolves nothing but the enforcement mode, which several tests move. */
    private final class ConfigurablePolicies implements PolicyResolver {

        private ShiftEnforcement enforcement = ShiftEnforcement.ADVISORY;

        @Override
        @SuppressWarnings("unchecked")
        public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
            CourierCompensationPolicy document = new CourierCompensationPolicy(
                    CourierCompensationPolicy.DEFAULTS.reverificationDays(),
                    CourierCompensationPolicy.DEFAULTS.warningDays(),
                    CourierCompensationPolicy.DEFAULTS.settlementPeriodDays(),
                    CourierCompensationPolicy.DEFAULTS.cashCeilingMinor(),
                    CourierCompensationPolicy.DEFAULTS.penaltyApprovalThresholdMinor(),
                    enforcement,
                    CourierCompensationPolicy.DEFAULTS.graceSeconds(),
                    CourierCompensationPolicy.DEFAULTS.confirmationPointRetentionDays());

            return Optional.of((ResolvedPolicy<P>) new ResolvedPolicy<>(
                    key.code(),
                    UUID.nameUUIDFromBytes("courier-policy".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    3,
                    scope.type(),
                    "test",
                    document));
        }

        @Override
        public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
            return resolve(key, ResourceScope.tenant(TENANT));
        }
    }

    private static final class RecordingNotifications implements CourierNotificationPort {

        private final List<Warning> warnings = new CopyOnWriteArrayList<>();
        private final List<UUID> lapses = new CopyOnWriteArrayList<>();

        @Override
        public void registrationExpiring(
                UUID tenantId, UUID courierId, LocalDate validUntil, int daysRemaining, Audience audience) {
            warnings.add(new Warning(courierId, daysRemaining, audience));
        }

        @Override
        public void registrationLapsed(UUID tenantId, UUID courierId, LocalDate validUntil) {
            lapses.add(courierId);
        }

        private record Warning(UUID courierId, int daysRemaining, Audience audience) {}
    }
}
