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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.application.CourierDispatchGate;
import uz.horecaos.platform.courier.application.CourierEngagementService;
import uz.horecaos.platform.courier.application.CourierLedgerService;
import uz.horecaos.platform.courier.application.CourierPolicyResolver;
import uz.horecaos.platform.courier.application.CourierShiftService;
import uz.horecaos.platform.courier.application.port.LegalEntityResolver;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.domain.ShiftActor;
import uz.horecaos.platform.courier.domain.ShiftEnforcement;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.dispatch.CourierShiftAdapter;
import uz.horecaos.platform.courier.infrastructure.dispatch.InternalFleetAdapter;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierTypeRow;
import uz.horecaos.platform.fulfillment.api.InternalFleetPort;
import uz.horecaos.platform.fulfillment.api.InternalFleetPort.FleetCandidate;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.horecaos.platform.fulfillment.domain.sourcing.PickupPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingDecision;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingMode;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingPlanner;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingProgress;
import uz.horecaos.platform.fulfillment.infrastructure.sourcing.InternalFleetConfiguration;
import uz.horecaos.platform.fulfillment.infrastructure.sourcing.JdbcActiveAssignments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.telemetry.api.CourierProximityPort;
import uz.horecaos.platform.telemetry.api.CourierShiftPort;
import uz.horecaos.platform.telemetry.application.DutySessionService;
import uz.horecaos.platform.telemetry.domain.CollectionGate;
import uz.horecaos.platform.telemetry.domain.LivePositionRules;
import uz.horecaos.platform.telemetry.infrastructure.fulfillment.LivePositionProximity;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.DutySessionRow;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The dispatch half of ADR 0042 — the two ports nothing implemented (ADR 0014,
 * ADR 0042, ADR 0045).
 *
 * <p>ADR 0042 built the courier model and never built dispatch. Two ports had no
 * implementation anywhere in {@code src/main}: {@code InternalFleetPort}, whose
 * stand-in answered no candidates, so every delivery on the platform recorded
 * {@code NO_INTERNAL_CANDIDATE} and paid a partner commission while a fleet sat
 * on shift; and {@code CourierShiftPort}, whose stand-in refused every duty
 * session, so no courier telemetry was collected at all.
 *
 * <p>Against a real PostgreSQL, because the interesting half of both answers is
 * SQL. Whether a courier of another tenant can be enumerated is a tenant
 * predicate; whether a courier at capacity is filtered out is a count against a
 * partial index; whether a distance is metres is PostGIS. None of that can be
 * tested against a mock, and each of them is a courier sent to the wrong place
 * or a person tracked who should not be.
 *
 * <p>Every test that asserts the fleet is now taken also asserts what the
 * stand-in does with the same fixture, so the fall-through this change removed
 * stays visible in the file rather than only in a commit message.
 */
class CourierDispatchPortTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final String UZS = "UZS";

    /** A Tuesday, noon in Tashkent. */
    private static final Instant NOON = Instant.parse("2026-08-25T07:00:00Z");

    /** The branch door: Amir Temur square, near enough. */
    private static final double BRANCH_LATITUDE = 41.311081;

    private static final double BRANCH_LONGITUDE = 69.240562;

    /** About 111 m of latitude, which is what a thousandth of a degree buys. */
    private static final double METRES_PER_LATITUDE_DEGREE = 111_320.0;

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MutableClock clock;
    private ConfigurablePolicies policies;

    private JdbcCourierStore courierStore;
    private JdbcCourierShiftStore shiftStore;
    private JdbcTelemetryStore telemetryStore;

    private CourierEngagementService engagements;
    private CourierShiftService shifts;

    private InternalFleetPort fleet;
    private InternalFleetPort standIn;
    private CourierShiftPort shiftPort;
    private DutySessionService dutySessions;

    private UUID branch;
    private UUID otherBranch;
    private UUID channelId;
    private UUID publicationId;
    private UUID scooterTypeId;
    private UUID alisher;
    private UUID alisherEngagement;

    /** Set only by the tests that call {@link #seedOtherTenant()}. */
    private @Nullable UUID otherTenantCourier;

    private int chainSequence;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for courier dispatch tests");
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
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("""
                TRUNCATE TABLE fulfillment.courier_positions_live,
                    fulfillment.courier_duty_sessions,
                    fulfillment.courier_ledger_entries,
                    fulfillment.courier_assignment_earnings,
                    fulfillment.courier_shift_breaks,
                    fulfillment.courier_shifts,
                    fulfillment.courier_settlement_periods,
                    fulfillment.courier_registration_notices,
                    fulfillment.courier_engagements,
                    fulfillment.couriers,
                    fulfillment.courier_rate_components,
                    fulfillment.courier_rate_cards,
                    fulfillment.courier_types,
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
        policies = new ConfigurablePolicies();
        RecordingAudit audit = new RecordingAudit();
        FieldProtection protection = new ReversibleProtection();
        LegalEntityResolver legalEntities = (tenantId, locationId, businessDate) -> Optional.empty();

        courierStore = new JdbcCourierStore(jdbc);
        shiftStore = new JdbcCourierShiftStore(jdbc);
        telemetryStore = new JdbcTelemetryStore(jdbc);
        JdbcCourierLedgerStore ledgerStore = new JdbcCourierLedgerStore(jdbc);
        JdbcCourierRateCardStore rateCardStore = new JdbcCourierRateCardStore(jdbc);

        CourierPolicyResolver policyResolver = new CourierPolicyResolver(policies);
        CourierLedgerService ledger =
                new CourierLedgerService(ledgerStore, courierStore, policyResolver, legalEntities, clock);
        engagements = new CourierEngagementService(
                courierStore, protection, audit, policyResolver, (tenantId, assetIds) -> false, clock);
        shifts = new CourierShiftService(
                shiftStore, courierStore, ledgerStore, rateCardStore, ledger, policyResolver, protection, audit, clock);

        CourierDispatchGate gate = new CourierDispatchGate(courierStore, shiftStore, policyResolver);
        CourierProximityPort proximity = new LivePositionProximity(telemetryStore, clock);
        fleet = new InternalFleetAdapter(shiftStore, gate, new JdbcActiveAssignments(jdbc), proximity);
        standIn = new InternalFleetConfiguration().unwiredInternalFleetPort();

        shiftPort = new CourierShiftAdapter(shiftStore, courierStore);
        dutySessions = new DutySessionService(telemetryStore, shiftPort, audit, clock);

        seedTenancy();
        scooterTypeId = seedCourierType("SCOOTER", 0, 15_000, 2, 60);
        CourierEngagementService.Registration registration = seedCourier(scooterTypeId, "K-001", "Alisher Karimov");
        alisher = registration.courierId();
        alisherEngagement = registration.engagementId();
    }

    // -------------------------------------------------- the fleet is now taken

    @Test
    @DisplayName("a courier on shift is offered the order; before this port existed the same "
            + "plan fell through to a partner")
    void aDeliverySourcesInHouseInsteadOfFallingThroughToAPartner() {
        shifts.open(openCommand(alisher, branch));

        // What the platform did until this change, on this exact fixture: the
        // stand-in answers nobody, the planner has no fleet to offer to, and the
        // decision is a partner booking whose recorded reason is the fleet being
        // empty. The commission is real and the courier standing at the counter
        // is real too.
        SourcingDecision before = decide(standIn.candidates(TENANT, BRAND, branch, 4_000));
        assertThat(before).isInstanceOf(SourcingDecision.BookPartner.class);
        assertThat(before.reason()).isEqualTo(SourcingDecision.NO_INTERNAL_CANDIDATE);
        assertThat(standIn.isWired()).isFalse();

        List<FleetCandidate> candidates = fleet.candidates(TENANT, BRAND, branch, 4_000);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.courierId()).isEqualTo(alisher);
            assertThat(candidate.offerTtlSeconds()).isEqualTo(60);
            assertThat(candidate.concurrencyCeiling()).isEqualTo(2);
            assertThat(candidate.activeAssignments()).isZero();
            assertThat(candidate.hasCapacity()).isTrue();
        });

        SourcingDecision after = decide(candidates);
        assertThat(after).isInstanceOf(SourcingDecision.OfferInternal.class);
        assertThat(((SourcingDecision.OfferInternal) after).courierId()).isEqualTo(alisher);
        assertThat(after.reason()).isEqualTo(SourcingDecision.FLEET_AVAILABLE);
        // isWired is the difference between "the rota is empty tonight" and
        // "there is a hole where the fleet should be". It is now the former.
        assertThat(fleet.isWired()).isTrue();
    }

    @Test
    @DisplayName(
            "a courier with no open shift is nobody's candidate, because nothing else " + "attaches him to a branch")
    void aCourierWhoHasNotSignedOnIsNotACandidate() {
        assertThat(fleet.candidates(TENANT, BRAND, branch, 4_000)).isEmpty();
        assertThat(decide(fleet.candidates(TENANT, BRAND, branch, 4_000)).reason())
                .isEqualTo(SourcingDecision.NO_INTERNAL_CANDIDATE);
    }

    @Test
    @DisplayName("a courier on break is not offered the order under any enforcement mode")
    void aCourierOnBreakIsNotOffered() {
        ShiftRow shift = shifts.open(openCommand(alisher, branch));
        shifts.startBreak(TENANT, shift.id(), ShiftActor.COURIER, courier(), "lunch");

        // ADVISORY forgives a missing shift and forgives nothing else. A courier
        // on break is not assignable whatever the branch decided about shifts,
        // and ADR 0045 is not even collecting his position.
        policies.enforcement = ShiftEnforcement.ADVISORY;
        assertThat(fleet.candidates(TENANT, BRAND, branch, 4_000)).isEmpty();
        policies.enforcement = ShiftEnforcement.OFF;
        assertThat(fleet.candidates(TENANT, BRAND, branch, 4_000)).isEmpty();

        shifts.endBreak(TENANT, shift.id(), ShiftActor.COURIER, courier(), "back on");
        assertThat(fleet.candidates(TENANT, BRAND, branch, 4_000)).hasSize(1);
    }

    @Test
    @DisplayName("an order outside the vehicle class's distance band is not offered to that class")
    void aCourierOutsideHisDistanceBandIsNotOffered() {
        shifts.open(openCommand(alisher, branch));

        // The scooter's band is 0 to 15 km. Eighteen is a different vehicle.
        assertThat(fleet.candidates(TENANT, BRAND, branch, 18_000)).isEmpty();
        assertThat(fleet.candidates(TENANT, BRAND, branch, 15_000)).hasSize(1);
    }

    @Test
    @DisplayName("a lapsed self-employment registration removes the courier from the fleet and "
            + "leaves his open shift alone")
    void aLapsedRegistrationRemovesTheCourierFromTheFleet() {
        shifts.open(openCommand(alisher, branch));
        assertThat(fleet.candidates(TENANT, BRAND, branch, 4_000)).hasSize(1);

        courierStore.suspend(
                TENANT,
                alisherEngagement,
                uz.horecaos.platform.courier.domain.EngagementStatus.SUSPENDED_COMPLIANCE,
                "REGISTRATION_LAPSED",
                uz.horecaos.platform.courier.domain.RegistrationWarningState.LAPSED,
                clock.instant());

        assertThat(fleet.candidates(TENANT, BRAND, branch, 4_000)).isEmpty();
        // New work stops; the shift he is already on is untouched, because ADR
        // 0042's compliance lever refuses offers and reverses nothing.
        assertThat(shiftStore.findLiveShift(TENANT, alisher)).isPresent();
    }

    @Test
    @DisplayName("another tenant's courier on shift at a same-named branch is never a candidate")
    void anotherTenantsCourierIsNeverACandidate() {
        shifts.open(openCommand(alisher, branch));

        // The other tenant's fleet is on shift at the other tenant's branch, and
        // asking about it with this tenant's id must not reach it. The reverse
        // direction matters just as much: this tenant's courier must not appear
        // when the other tenant asks about its own branch.
        UUID theirBranch = seedOtherTenant();

        // Proved first, or the two emptinesses below would be an empty fixture
        // rather than a tenant predicate.
        assertThat(fleet.candidates(OTHER_TENANT, OTHER_BRAND, theirBranch, 4_000))
                .extracting(FleetCandidate::courierId)
                .containsExactly(otherTenantCourier);

        assertThat(fleet.candidates(TENANT, BRAND, theirBranch, 4_000)).isEmpty();
        assertThat(fleet.candidates(OTHER_TENANT, OTHER_BRAND, branch, 4_000)).isEmpty();
        assertThat(fleet.candidates(TENANT, BRAND, branch, 4_000))
                .extracting(FleetCandidate::courierId)
                .containsExactly(alisher);
    }

    @Test
    @DisplayName("a courier already carrying his ceiling has no capacity, and a delivered order "
            + "stops counting against him")
    void aCourierAtHisCeilingIsFilteredOutAndADeliveredOrderIsNotCarried() {
        shifts.open(openCommand(alisher, branch));

        carryingShipment(alisher, "ASSIGNED");
        assertThat(fleet.candidates(TENANT, BRAND, branch, 4_000))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.activeAssignments()).isEqualTo(1);
                    assertThat(candidate.hasCapacity()).isTrue();
                });

        carryingShipment(alisher, "PICKED_UP");
        FleetCandidate full = fleet.candidates(TENANT, BRAND, branch, 4_000).getFirst();
        assertThat(full.activeAssignments()).isEqualTo(2);
        assertThat(full.hasCapacity()).isFalse();
        // The planner filters on capacity, so a full courier is not an offer that
        // is made and declined; the plan goes to a partner and says why.
        assertThat(decide(List.of(full)).reason()).isEqualTo(SourcingDecision.NO_INTERNAL_CANDIDATE);

        // A delivered order is not being carried. Counting the accepted attempt
        // instead of the shipment would leave him permanently at capacity.
        carryingShipment(alisher, "DELIVERED");
        assertThat(fleet.candidates(TENANT, BRAND, branch, 4_000).getFirst().activeAssignments())
                .isEqualTo(2);
    }

    // --------------------------------------------------- distance, and its lies

    @Test
    @DisplayName("the nearer courier ranks first; a pin older than the staleness bound is not a " + "distance at all")
    void theNearerCourierRanksFirstAndAStalePinRanksLast() {
        UUID bobur = seedCourier(scooterTypeId, "K-002", "Bobur Rashidov").courierId();
        ShiftRow alishersShift = shifts.open(openCommand(alisher, branch));
        ShiftRow bobursShift = shifts.open(openCommand(bobur, branch));

        DutySessionRow alishersSession = openDutySession(alisher);
        DutySessionRow bobursSession = openDutySession(bobur);
        assertThat(alishersSession.shiftId()).isEqualTo(alishersShift.id());
        assertThat(bobursSession.shiftId()).isEqualTo(bobursShift.id());

        // Two hundred metres and two kilometres north of the door, both fixed a
        // moment ago and both precise.
        pin(alishersSession, metresNorth(200), 12.0, NOON.minusSeconds(30));
        pin(bobursSession, metresNorth(2_000), 12.0, NOON.minusSeconds(30));

        List<FleetCandidate> near = fleet.candidates(TENANT, BRAND, branch, 4_000);
        assertThat(candidateFor(near, alisher).metresFromBranch()).isBetween(150, 250);
        assertThat(candidateFor(near, bobur).metresFromBranch()).isBetween(1_900, 2_100);
        assertThat(((SourcingDecision.OfferInternal) decide(near)).courierId()).isEqualTo(alisher);

        // The clock moves past the staleness bound and nothing else changes. A
        // fix from eleven minutes ago describes where the courier was before he
        // went into a lift, and ranking on it sends the order to whoever the
        // telemetry lost. Absent, not far: the comparator sorts null last.
        clock.set(NOON.plus(LivePositionRules.MAXIMUM_STALENESS).plusSeconds(60));

        List<FleetCandidate> stale = fleet.candidates(TENANT, BRAND, branch, 4_000);
        assertThat(stale).extracting(FleetCandidate::metresFromBranch).containsOnlyNulls();
    }

    @Test
    @DisplayName("a fix too coarse for the map is too coarse to rank on")
    void aCoarseFixIsNotADistance() {
        DutySessionRow session = openDutySession(alisherOnShift());

        pin(session, metresNorth(200), 900.0, NOON.minusSeconds(30));
        assertThat(fleet.candidates(TENANT, BRAND, branch, 4_000).getFirst().metresFromBranch())
                .isNull();

        pin(session, metresNorth(200), 40.0, NOON.minusSeconds(10));
        assertThat(fleet.candidates(TENANT, BRAND, branch, 4_000).getFirst().metresFromBranch())
                .isBetween(150, 250);
    }

    @Test
    @DisplayName("a branch nobody pinned produces no distances rather than a distance from the " + "null island")
    void anUngeocodedBranchProducesNoDistances() {
        DutySessionRow session = openDutySession(alisherOnShift());
        pin(session, metresNorth(200), 12.0, NOON.minusSeconds(30));

        jdbc.sql("""
                UPDATE tenant.locations
                   SET latitude = NULL, longitude = NULL, coordinate_source = 'NOT_GEOCODED'
                 WHERE tenant_id = :tenantId AND id = :id
                """).param("tenantId", TENANT).param("id", branch).update();

        assertThat(fleet.candidates(TENANT, BRAND, branch, 4_000).getFirst().metresFromBranch())
                .isNull();
    }

    // ------------------------------------------------- the shift a session opens from

    @Test
    @DisplayName("a courier who opened his own shift can be tracked; before this port existed no "
            + "duty session could open at all")
    void aCourierOpensHisOwnShiftAndTheDutySessionFollowsIt() {
        // What the platform did until this change, on this exact fixture.
        CourierShiftPort unwired =
                new uz.horecaos.platform.telemetry.infrastructure.fulfillment.CourierComplianceConfiguration()
                        .unwiredCourierShiftPort();
        assertThat(unwired.isWired()).isFalse();
        assertThat(unwired.openShift(TENANT, alisher, branch)).isEmpty();

        ShiftRow shift = shifts.open(openCommand(alisher, branch));

        Optional<CourierShiftPort.OpenShift> open = shiftPort.openShift(TENANT, alisher, branch);
        assertThat(open).isPresent();
        assertThat(open.get().shiftId()).isEqualTo(shift.id());
        assertThat(open.get().brandId()).isEqualTo(BRAND);
        assertThat(open.get().locationId()).isEqualTo(branch);
        assertThat(open.get().registrationValidUntil())
                .isEqualTo(LocalDate.ofInstant(NOON, ZoneOffset.UTC).plusYears(1));

        DutySessionRow session = dutySessions.open(openSessionCommand(alisher));
        assertThat(session.shiftId()).isEqualTo(shift.id());
        assertThat(session.registrationValidUntil()).isEqualTo(open.get().registrationValidUntil());
    }

    @Test
    @DisplayName("a manager cannot open a shift, so a manager cannot start collecting a "
            + "self-employed person's location")
    void aManagerCannotOpenAShiftAndThereforeCannotStartCollection() {
        Throwable refusal = catchThrowable(() -> shifts.open(new CourierShiftService.OpenShift(
                TENANT,
                BRAND,
                branch,
                alisher,
                ShiftActor.MANAGER,
                manager(),
                "the rider's phone is flat",
                null,
                UZS)));

        assertThat(refusal).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refusal).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CAPABILITY);

        // And the consequence, which is the point of the rule rather than the
        // rule itself: with no shift there is no window to collect in, and the
        // duty session is refused by name.
        assertThat(shiftPort.openShift(TENANT, alisher, branch)).isEmpty();
        Throwable noSession = catchThrowable(() -> dutySessions.open(openSessionCommand(alisher)));
        assertThat(((ApiException) noSession).properties()).containsEntry("reason", "NO_OPEN_SHIFT");
        assertThat(openSessionCount()).isZero();
    }

    @Test
    @DisplayName("a shift at another branch is not this branch's shift")
    void aShiftAtAnotherBranchIsNotThisBranchesShift() {
        shifts.open(openCommand(alisher, otherBranch));

        assertThat(shiftPort.openShift(TENANT, alisher, branch)).isEmpty();
        assertThat(shiftPort.openShift(TENANT, alisher, otherBranch)).isPresent();
    }

    @Test
    @DisplayName("another tenant's id does not reach this courier's shift")
    void anotherTenantsIdDoesNotReachThisShift() {
        shifts.open(openCommand(alisher, branch));
        UUID theirBranch = seedOtherTenant();
        UUID theirCourier = java.util.Objects.requireNonNull(otherTenantCourier);

        // Both couriers really are on shift, so an empty answer below is the
        // tenant predicate refusing and not a fixture that seeded nothing.
        assertThat(shiftPort.openShift(TENANT, alisher, branch)).isPresent();
        assertThat(shiftPort.openShift(OTHER_TENANT, theirCourier, theirBranch)).isPresent();

        assertThat(shiftPort.openShift(OTHER_TENANT, alisher, branch)).isEmpty();
        assertThat(shiftPort.openShift(TENANT, theirCourier, theirBranch)).isEmpty();
    }

    @Test
    @DisplayName("a registration that lapses overnight refuses the next morning's duty session, "
            + "and the fixture's clock is the one that decides")
    void aRegistrationThatLapsesOvernightRefusesTheNextSession() {
        // The engagement is ACTIVE and the date is in the future, so the port
        // answers. Nothing about the engagement changes below; only the clock
        // moves, which is the whole point: an expiry is a fact that changes by
        // itself overnight and a test asserting it against a fixed instant is
        // asserting nothing.
        LocalDate expiresOn = LocalDate.ofInstant(NOON, ZoneOffset.UTC).plusDays(1);
        jdbc.sql("""
                UPDATE fulfillment.courier_engagements
                   SET registration_valid_until = :date, reverification_due_on = :date
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", TENANT)
                .param("id", alisherEngagement)
                .param("date", expiresOn)
                .update();

        shifts.open(openCommand(alisher, branch));
        DutySessionRow tonight = dutySessions.open(openSessionCommand(alisher));
        assertThat(tonight.registrationValidUntil()).isEqualTo(expiresOn);
        dutySessions.close(TENANT, tonight.id(), "SIGNED_OFF", courier(), "end of shift", "courier.shift.open", "corr");

        clock.set(NOON.plus(Duration.ofDays(2)));

        // The port still answers — the engagement row still says ACTIVE, because
        // the compliance sweeper has not run — and the date it carries is what
        // refuses the session. That is the division of labour ADR 0045 states:
        // ADR 0042 owns the record, this refuses to collect against a stale one.
        assertThat(shiftPort.openShift(TENANT, alisher, branch)).isPresent();
        Throwable refusal = catchThrowable(() -> dutySessions.open(openSessionCommand(alisher)));
        assertThat(((ApiException) refusal).properties())
                .containsEntry("reason", "REGISTRATION_LAPSED")
                .containsEntry("registrationValidUntil", expiresOn.toString());
        assertThat(openSessionCount()).isZero();
    }

    // ------------------------------------------------------- what crosses the seam

    @Test
    @DisplayName("neither port carries a name or a registration number across the boundary")
    void nothingPersonalCrossesEitherBoundary() {
        shifts.open(openCommand(alisher, branch));
        DutySessionRow session = openDutySession(alisher);
        pin(session, metresNorth(200), 12.0, NOON.minusSeconds(30));

        // ADR 0045 requires a stored track or a registration number to be
        // revealed per person, for a declared purpose, with an audit entry. The
        // defence that costs nothing is that neither value is ever selected: the
        // engagement projection omits the ciphertext column outright, and these
        // two records are declared with no field one could be put in.
        assertThat(componentTypes(CourierShiftPort.OpenShift.class))
                .containsExactly("UUID", "UUID", "UUID", "LocalDate");
        assertThat(componentTypes(FleetCandidate.class)).containsExactly("UUID", "int", "int", "int", "Integer", "int");

        // And the values that actually cross carry nothing of the person beyond
        // the identifier the caller already had.
        CourierShiftPort.OpenShift open =
                shiftPort.openShift(TENANT, alisher, branch).orElseThrow();
        FleetCandidate candidate =
                fleet.candidates(TENANT, BRAND, branch, 4_000).getFirst();

        assertThat(open.toString()).doesNotContain("Alisher").doesNotContain("312345678901");
        assertThat(candidate.toString()).doesNotContain("Alisher").doesNotContain("K-001");
        // The registration number is held encrypted and unreadable by any of
        // this: the projection the adapter uses does not select the column.
        assertThat(courierStore
                        .findLiveEngagement(TENANT, alisher)
                        .orElseThrow()
                        .protectedRegistrationRef())
                .isNull();
    }

    // ------------------------------------------------------------------- helpers

    private static List<String> componentTypes(Class<?> record) {
        return java.util.Arrays.stream(record.getRecordComponents())
                .map(component -> component.getType().getSimpleName())
                .toList();
    }

    private static FleetCandidate candidateFor(List<FleetCandidate> candidates, UUID courierId) {
        return candidates.stream()
                .filter(candidate -> candidate.courierId().equals(courierId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No candidate for " + courierId));
    }

    /**
     * One sourcing decision for a plan confirmed now, with one partner configured
     * and nothing tried yet.
     *
     * <p>{@link SourcingPlanner} is a pure function of its arguments, so the only
     * thing that varies between the assertions above is the candidate list — which
     * is exactly the thing these ports produce.
     */
    private SourcingDecision decide(List<FleetCandidate> candidates) {
        DeliverySourcingPolicy policy = DeliverySourcingPolicy.DEFAULTS;
        PickupPlan plan =
                PickupPlan.forOrder(clock.instant(), Duration.ofMinutes(30), ZoneId.of("Asia/Tashkent"), policy);
        PartnerOption partner = new PartnerOption(UUID.randomUUID(), "YANDEX", false, true);

        return SourcingPlanner.decide(
                plan,
                policy,
                SourcingMode.FLEET_FIRST,
                candidates,
                List.of(partner),
                SourcingProgress.starting(clock.instant()),
                clock.instant());
    }

    private UUID alisherOnShift() {
        shifts.open(openCommand(alisher, branch));
        return alisher;
    }

    private CourierShiftService.OpenShift openCommand(UUID courierId, UUID locationId) {
        return new CourierShiftService.OpenShift(
                TENANT, BRAND, locationId, courierId, ShiftActor.COURIER, courier(), "opening my shift", null, UZS);
    }

    private DutySessionService.OpenCommand openSessionCommand(UUID courierId) {
        return new DutySessionService.OpenCommand(
                TENANT,
                courierId,
                branch,
                "handset-1",
                CollectionGate.ON_DUTY,
                courier(),
                "signing on",
                "courier.shift.open",
                "corr");
    }

    private DutySessionRow openDutySession(UUID courierId) {
        return dutySessions.open(openSessionCommand(courierId));
    }

    private long openSessionCount() {
        return jdbc.sql("""
                SELECT count(*) FROM fulfillment.courier_duty_sessions WHERE ended_at IS NULL
                """).query(Long.class).single();
    }

    /** A latitude that many metres north of the branch door. */
    private static double metresNorth(int metres) {
        return BRANCH_LATITUDE + metres / METRES_PER_LATITUDE_DEGREE;
    }

    /**
     * Writes the live row directly.
     *
     * <p>{@code TelemetryIngestService} is ADR 0045's own suite; what these tests
     * need is a pin with a chosen accuracy and a chosen capture instant, which is
     * precisely what the ingest path derives rather than accepts.
     */
    private void pin(DutySessionRow session, double latitude, double accuracyMeters, Instant capturedAt) {

        jdbc.sql("""
                INSERT INTO fulfillment.courier_positions_live (
                    tenant_id, courier_id, duty_session_id, brand_id, location_id, position,
                    accuracy_meters, active_assignment_count, captured_at, received_at)
                VALUES (:tenantId, :courierId, :sessionId, :brandId, :locationId,
                    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                    :accuracy, 0, :capturedAt, :capturedAt)
                ON CONFLICT ON CONSTRAINT pk_courier_positions_live DO UPDATE
                   SET position = excluded.position,
                       accuracy_meters = excluded.accuracy_meters,
                       captured_at = excluded.captured_at,
                       received_at = excluded.received_at
                """)
                .param("tenantId", session.tenantId())
                .param("courierId", session.courierId())
                .param("sessionId", session.id())
                .param("brandId", session.brandId())
                .param("locationId", session.locationId())
                .param("longitude", BRANCH_LONGITUDE)
                .param("latitude", latitude)
                .param("accuracy", accuracyMeters)
                .param("capturedAt", OffsetDateTime.ofInstant(capturedAt, ZoneOffset.UTC))
                .update();
    }

    // --------------------------------------------------------------- the fixture

    private void seedTenancy() {
        insertTenant(TENANT, "dispatch-tenant");
        insertBrand(BRAND, TENANT);

        branch = UUID.randomUUID();
        insertLocation(branch, TENANT, BRAND, "CENTRE", BRANCH_LATITUDE, BRANCH_LONGITUDE);
        otherBranch = UUID.randomUUID();
        insertLocation(otherBranch, TENANT, BRAND, "NORTH", BRANCH_LATITUDE + 0.05, BRANCH_LONGITUDE);

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

    /**
     * A second tenant with a courier on shift at its own branch, reusing this
     * tenant's brand and location ids nowhere.
     *
     * @return that tenant's branch, so a query can be pointed at it deliberately
     */
    private UUID seedOtherTenant() {
        insertTenant(OTHER_TENANT, "other-tenant");
        insertBrand(OTHER_BRAND, OTHER_TENANT);
        UUID theirBranch = UUID.randomUUID();
        insertLocation(theirBranch, OTHER_TENANT, OTHER_BRAND, "CENTRE", BRANCH_LATITUDE, BRANCH_LONGITUDE);

        UUID theirType = UUID.randomUUID();
        courierStore.insertType(new CourierTypeRow(
                theirType, OTHER_TENANT, "SCOOTER", "Scooter", "SCOOTER", 0, 15_000, 2, 60, "ACTIVE"));
        CourierEngagementService.Registration theirs = engagements.register(new CourierEngagementService.NewCourier(
                OTHER_TENANT,
                theirType,
                "keycloak-their-courier",
                "K-900",
                "Sardor Yusupov",
                LocalDate.ofInstant(NOON, ZoneOffset.UTC),
                manager(),
                "onboarding",
                "corr"));
        engagements.verify(new CourierEngagementService.VerifyRegistration(
                OTHER_TENANT,
                theirs.engagementId(),
                "409999999999",
                LocalDate.ofInstant(NOON, ZoneOffset.UTC).plusYears(1),
                VerificationMethod.MANUAL_ATTESTATION,
                null,
                manager(),
                "sighted",
                "corr"));

        shifts.open(new CourierShiftService.OpenShift(
                OTHER_TENANT,
                OTHER_BRAND,
                theirBranch,
                theirs.courierId(),
                ShiftActor.COURIER,
                courier(),
                "opening",
                null,
                UZS));
        otherTenantCourier = theirs.courierId();
        return theirBranch;
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertBrand(UUID id, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", id).param("tenantId", tenantId).update();
    }

    private void insertLocation(UUID id, UUID tenantId, UUID brandId, String code, double latitude, double longitude) {

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version, latitude, longitude, coordinate_source)
                VALUES (:id, :tenantId, :brandId, :code, lower(:code), :code, 'Asia/Tashkent',
                        'ACTIVE', 0, :latitude, :longitude, 'MERCHANT_PIN')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .param("latitude", latitude)
                .param("longitude", longitude)
                .update();
    }

    private UUID seedCourierType(String code, int minMetres, Integer maxMetres, int ceiling, int offerTtlSeconds) {

        UUID id = UUID.randomUUID();
        courierStore.insertType(new CourierTypeRow(
                id, TENANT, code, code, "SCOOTER", minMetres, maxMetres, ceiling, offerTtlSeconds, "ACTIVE"));
        return id;
    }

    private CourierEngagementService.Registration seedCourier(UUID typeId, String reference, String fullName) {

        CourierEngagementService.Registration registration =
                engagements.register(new CourierEngagementService.NewCourier(
                        TENANT,
                        typeId,
                        "keycloak-" + reference.toLowerCase(java.util.Locale.ROOT),
                        reference,
                        fullName,
                        LocalDate.ofInstant(NOON, ZoneOffset.UTC),
                        manager(),
                        "onboarding a rider",
                        "corr"));
        engagements.verify(new CourierEngagementService.VerifyRegistration(
                TENANT,
                registration.engagementId(),
                "312345678901",
                LocalDate.ofInstant(NOON, ZoneOffset.UTC).plusYears(1),
                VerificationMethod.MANUAL_ATTESTATION,
                null,
                manager(),
                "sighted the registration certificate",
                "corr"));
        return registration;
    }

    /**
     * A plan, an order and a shipment this courier is carrying, in the status
     * given.
     *
     * <p>The whole chain each time, because ADR 0014's single-winner indexes
     * permit one live plan per order and one uncancelled shipment per plan. Two
     * shipments sharing a plan is the thing those indexes exist to make
     * unwritable.
     */
    private void carryingShipment(UUID courierId, String status) {
        int sequence = ++chainSequence;
        UUID orderId = seedDeliveryOrder(sequence);
        UUID planId = UUID.randomUUID();
        OffsetDateTime anchor = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (
                    id, tenant_id, brand_id, location_id, order_id, status, sourcing_mode,
                    service_level, customer_delivery_fee_minor, currency, confirmed_at,
                    preparation_seconds, estimated_ready_at, pickup_window_start,
                    pickup_window_end, promised_delivery_start, promised_delivery_end,
                    source_at, latest_assignment_at, branch_zone)
                SELECT :id, :tenantId, :brandId, :locationId, :orderId, 'SOURCING', 'FLEET_FIRST',
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
                .param("anchor", anchor)
                .update();

        jdbc.sql("""
                INSERT INTO fulfillment.shipments (
                    id, tenant_id, brand_id, location_id, order_id, delivery_plan_id, status,
                    source_type, courier_id, assigned_at, picked_up_at, delivered_at)
                SELECT :id, :tenantId, :brandId, :locationId, :orderId, :planId, :status,
                       'INTERNAL', :courierId,
                       anchor + interval '5 minutes',
                       CASE WHEN :status IN ('PICKED_UP', 'DELIVERED')
                            THEN anchor + interval '20 minutes' END,
                       CASE WHEN :status = 'DELIVERED'
                            THEN anchor + interval '40 minutes' END
                  FROM (SELECT CAST(:anchor AS timestamptz) AS anchor) AS moment
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("orderId", orderId)
                .param("planId", planId)
                .param("status", status)
                .param("courierId", courierId)
                .param("anchor", anchor)
                .update();
    }

    private UUID seedDeliveryOrder(int sequence) {
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String reference = "dispatch-" + sequence;

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
                        :reference, 'DELIVERY', 'AUTO_CONFIRM', 0, 'NONE', 'CONFIRMED', 'UZS',
                        50000, 0, 50000, :quoteId, 'hash', :publicationId, :cartId, :reference,
                        1, now())
                """)
                .param("id", orderId)
                .param("number", "X-" + sequence)
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

    private static ActorRef manager() {
        return ActorRef.user("keycloak-manager", "Branch manager");
    }

    private static ActorRef courier() {
        return ActorRef.user("keycloak-courier", "Courier");
    }

    // ------------------------------------------------------------------- fakes

    /** A clock the tests move, because two of the rules here are durations. */
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

    /** Resolves nothing but the enforcement mode, which two tests move. */
    private final class ConfigurablePolicies implements PolicyResolver {

        private ShiftEnforcement enforcement = ShiftEnforcement.ENFORCED;

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
}
