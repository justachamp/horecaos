package uz.horecaos.platform.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.application.AdjustmentRuleEvaluator;
import uz.horecaos.platform.courier.application.CourierAdjustmentService;
import uz.horecaos.platform.courier.application.CourierEngagementService;
import uz.horecaos.platform.courier.application.CourierPolicyResolver;
import uz.horecaos.platform.courier.application.CourierShiftService;
import uz.horecaos.platform.courier.application.PlannedShiftService;
import uz.horecaos.platform.courier.application.PlannedShiftService.NewPlannedShift;
import uz.horecaos.platform.courier.application.PlannedShiftService.RosterComparison;
import uz.horecaos.platform.courier.domain.PlannedShiftStatus;
import uz.horecaos.platform.courier.domain.ShiftActor;
import uz.horecaos.platform.courier.domain.ShiftEnforcement;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierTypeRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcPlannedShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcPlannedShiftStore.PlannedShiftRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;

/**
 * The planned-shift model (operations gap map row {@code 3.5}, ADR 0042's
 * roster entry V0040 deliberately omitted). Against a real PostgreSQL, because
 * the property under test is the same class CLAUDE.md warns about: whether the
 * planned-versus-actual comparison agrees with two independently-written
 * tables is a database fact, not a mock's opinion.
 */
class PlannedShiftServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();

    /** A Tuesday, 12:00 in Tashkent. */
    private static final Instant NOON = Instant.parse("2026-08-25T07:00:00Z");

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private MutableClock clock;

    private JdbcCourierStore courierStore;
    private JdbcCourierShiftStore shiftStore;
    private JdbcPlannedShiftStore rosterStore;

    private CourierEngagementService engagements;
    private CourierShiftService shifts;
    private PlannedShiftService plannedShifts;

    private UUID branch;
    private UUID courierTypeId;
    private UUID courierId;
    private UUID engagementId;

    /**
     * Never asked in this suite — {@link CourierShiftService} needs an {@link
     * AdjustmentRuleEvaluator}, which in turn needs a {@link
     * CourierAdjustmentService}, which needs an {@link ApprovalService} — but
     * {@link ApprovalService} is not a functional interface, so a full stub is
     * simpler than a mock nothing here would ever verify.
     */
    private static final ApprovalService NEVER_REQUIRED = new ApprovalService() {
        @Override
        public ApprovalOutcome requireApproval(ApprovalRequestCommand command) {
            return new ApprovalOutcome.NotRequired();
        }

        @Override
        public void decide(UUID requestId, Decision decision, ActorRef approver, String reason) {
            throw new UnsupportedOperationException("Not exercised by this suite");
        }

        @Override
        public int expireOverdue() {
            return 0;
        }
    };

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for planned-shift tests");
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

        jdbc.sql("""
                TRUNCATE TABLE fulfillment.courier_roster_entries,
                    fulfillment.courier_assignment_earnings,
                    fulfillment.courier_shift_breaks,
                    fulfillment.courier_shifts,
                    fulfillment.courier_engagements,
                    fulfillment.couriers,
                    fulfillment.courier_types
                    CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(NOON);
        FieldProtection protection = new ReversibleProtection();

        courierStore = new JdbcCourierStore(jdbc);
        shiftStore = new JdbcCourierShiftStore(jdbc);
        rosterStore = new JdbcPlannedShiftStore(jdbc);

        CourierPolicyResolver policyResolver = new CourierPolicyResolver(new AdvisoryPolicies());
        RecordingAudit audit = new RecordingAudit();
        var ledgerStore = new uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore(jdbc);
        var ledger = new uz.horecaos.platform.courier.application.CourierLedgerService(
                ledgerStore,
                courierStore,
                policyResolver,
                (tenantId, locationId, businessDate) -> Optional.empty(),
                clock);
        CourierAdjustmentService adjustments =
                new CourierAdjustmentService(courierStore, ledger, NEVER_REQUIRED, audit, policyResolver, clock);
        AdjustmentRuleEvaluator adjustmentRules = new AdjustmentRuleEvaluator(courierStore, ledgerStore, adjustments);
        engagements = new CourierEngagementService(
                courierStore, protection, audit, policyResolver, (tenantId, assetIds) -> false, clock);
        shifts = new CourierShiftService(
                shiftStore,
                courierStore,
                ledgerStore,
                new uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore(jdbc),
                ledger,
                policyResolver,
                protection,
                audit,
                adjustmentRules,
                clock);
        plannedShifts = new PlannedShiftService(rosterStore, courierStore, shiftStore, audit, clock);

        branch = seedTenancy(TENANT);
        seedTenancy(OTHER_TENANT);
        seedCourier();
    }

    // ------------------------------------------------------------------ draft

    @Test
    @DisplayName("a drafted planned shift lands as DRAFT, with no publish or response stamp")
    void draftingLandsAsDraft() {
        PlannedShiftRow entry = plannedShifts.draft(newShift(NOON, NOON.plus(Duration.ofHours(8))));

        assertThat(entry.status()).isEqualTo(PlannedShiftStatus.DRAFT);
        assertThat(entry.publishedAt()).isNull();
        assertThat(entry.respondedAt()).isNull();
        assertThat(rosterStore.find(TENANT, entry.id())).contains(entry);
    }

    @Test
    @DisplayName("a planned shift that ends before it starts is refused before it reaches the database")
    void endMustBeAfterStart() {
        Throwable failure =
                catchThrowable(() -> plannedShifts.draft(newShift(NOON, NOON.minus(Duration.ofMinutes(1)))));
        assertThat(failure).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the database itself refuses planned_end <= planned_start, not only the service")
    void theDatabaseCheckConstraintHoldsEvenIfTheServiceCheckIsBypassed() {
        // Proves the constraint from CLAUDE.md's own rule -- "prefer a
        // constraint over a service-layer check" -- actually holds, by writing
        // straight past PlannedShiftService and hitting the table directly.
        Throwable failure = catchThrowable(() -> jdbc.sql("""
                        INSERT INTO fulfillment.courier_roster_entries (
                            id, tenant_id, brand_id, location_id, courier_id, engagement_id,
                            status, planned_start, planned_end, created_by, version, created_at, updated_at)
                        VALUES (:id, :tenantId, :brandId, :locationId, :courierId, :engagementId,
                            'DRAFT', :end, :start, :createdBy, 1, now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("courierId", courierId)
                .param("engagementId", engagementId)
                .param("start", java.time.OffsetDateTime.ofInstant(NOON, ZoneOffset.UTC))
                .param("end", java.time.OffsetDateTime.ofInstant(NOON.plus(Duration.ofHours(1)), ZoneOffset.UTC))
                .param("createdBy", UUID.randomUUID())
                .update());

        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).containsIgnoringCase("ck_roster_entry_window");
    }

    @Test
    @DisplayName("a courier with no live engagement cannot have a shift planned for them")
    void noLiveEngagementRefusesTheDraft() {
        UUID strangerId = UUID.randomUUID();
        Throwable failure = catchThrowable(() -> plannedShifts.draft(new NewPlannedShift(
                TENANT,
                BRAND,
                branch,
                strangerId,
                NOON,
                NOON.plus(Duration.ofHours(8)),
                UUID.randomUUID(),
                manager(),
                "planning ahead")));
        assertThat(failure).isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------- publish/cancel

    @Test
    @DisplayName("publishing moves DRAFT to PUBLISHED and stamps who published it; publishing twice is refused")
    void publishingIsOnceOnly() {
        PlannedShiftRow entry = plannedShifts.draft(newShift(NOON, NOON.plus(Duration.ofHours(8))));
        UUID publisher = UUID.randomUUID();

        plannedShifts.publish(TENANT, BRAND, branch, entry.id(), manager(), publisher, "publishing this week's roster");

        PlannedShiftRow published = rosterStore.find(TENANT, entry.id()).orElseThrow();
        assertThat(published.status()).isEqualTo(PlannedShiftStatus.PUBLISHED);
        assertThat(published.publishedAt()).isNotNull();
        assertThat(published.publishedBy()).isEqualTo(publisher);

        assertThat(catchThrowable(() -> plannedShifts.publish(
                        TENANT, BRAND, branch, entry.id(), manager(), publisher, "publishing again")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a DRAFT or PUBLISHED entry can be cancelled; a cancelled entry cannot be cancelled again")
    void cancellingIsIdempotentInRefusalNotInEffect() {
        PlannedShiftRow drafted = plannedShifts.draft(newShift(NOON, NOON.plus(Duration.ofHours(8))));
        plannedShifts.cancel(TENANT, BRAND, branch, drafted.id(), manager(), "branch closed that day");
        assertThat(rosterStore.find(TENANT, drafted.id()).orElseThrow().status())
                .isEqualTo(PlannedShiftStatus.CANCELLED);

        assertThat(catchThrowable(() -> plannedShifts.cancel(TENANT, BRAND, branch, drafted.id(), manager(), "again")))
                .isInstanceOf(ApiException.class);

        PlannedShiftRow published = plannedShifts.draft(newShift(NOON, NOON.plus(Duration.ofHours(8))));
        plannedShifts.publish(TENANT, BRAND, branch, published.id(), manager(), UUID.randomUUID(), "publishing");
        plannedShifts.cancel(
                TENANT, BRAND, branch, published.id(), manager(), "courier called in sick, roster withdrawn");
        assertThat(rosterStore.find(TENANT, published.id()).orElseThrow().status())
                .isEqualTo(PlannedShiftStatus.CANCELLED);
    }

    @Test
    @DisplayName(
            "publish/cancel refuse as not-found when the caller's own branch does not match the entry's actual branch")
    void publishAndCancelRefuseABranchMismatch() {
        // OperationsCourierController's @RequiresCapability is LOCATION-scoped
        // and reads brandId/locationId from the request, so its capability check
        // alone only proves the caller manages the branch they named -- never
        // that the entry they named lives there. This is the re-check that
        // closes that gap: a caller naming their own branch and someone else's
        // entry id must be refused, and refused as not-found (ADR 0031) rather
        // than merely forbidden, exactly like an entry that never existed.
        PlannedShiftRow entry = plannedShifts.draft(newShift(NOON, NOON.plus(Duration.ofHours(8))));
        UUID foreignLocation = UUID.randomUUID();
        UUID foreignBrand = UUID.randomUUID();

        assertThat(catchThrowable(() -> plannedShifts.publish(
                        TENANT, BRAND, foreignLocation, entry.id(), manager(), UUID.randomUUID(), "wrong branch")))
                .isInstanceOf(ApiException.class);
        assertThat(catchThrowable(
                        () -> plannedShifts.cancel(TENANT, foreignBrand, branch, entry.id(), manager(), "wrong brand")))
                .isInstanceOf(ApiException.class);

        // Neither mismatched call took effect -- the entry is still DRAFT.
        assertThat(rosterStore.find(TENANT, entry.id()).orElseThrow().status()).isEqualTo(PlannedShiftStatus.DRAFT);
    }

    // -------------------------------------------------------------- period filter

    @Test
    @DisplayName("atLocation's from/to window includes an overlapping entry and excludes one entirely outside it")
    void periodFilterIncludesOverlapAndExcludesOutside() {
        PlannedShiftRow thisWeek = plannedShifts.draft(newShift(NOON, NOON.plus(Duration.ofHours(8))));
        PlannedShiftRow nextMonth = plannedShifts.draft(newShift(
                NOON.plus(Duration.ofDays(30)), NOON.plus(Duration.ofDays(30)).plus(Duration.ofHours(8))));

        List<PlannedShiftRow> windowed = plannedShifts.atLocation(
                TENANT, BRAND, branch, NOON.minus(Duration.ofDays(1)), NOON.plus(Duration.ofDays(1)), 50);

        assertThat(windowed).extracting(PlannedShiftRow::id).containsExactly(thisWeek.id());
        assertThat(windowed).extracting(PlannedShiftRow::id).doesNotContain(nextMonth.id());

        // No window at all still answers everything -- IA 3.5's original,
        // unwindowed read, kept working rather than replaced.
        assertThat(plannedShifts.atLocation(TENANT, BRAND, branch, null, null, 50))
                .extracting(PlannedShiftRow::id)
                .contains(thisWeek.id(), nextMonth.id());
    }

    @Test
    @DisplayName(
            "the shift read's own from/to window includes a shift covering the period and excludes one entirely before it")
    void shiftPeriodFilterIncludesCoverageAndExcludesEarlierShifts() {
        ShiftRow earlier = shifts.open(new CourierShiftService.OpenShift(
                TENANT, BRAND, branch, courierId, ShiftActor.COURIER, courier(), "opening", null, "UZS"));
        shifts.close(new CourierShiftService.CloseShift(
                TENANT, earlier.id(), ShiftActor.COURIER, courier(), null, "closing", null, "UZS"));

        clock.set(NOON.plus(Duration.ofDays(10)));
        ShiftRow later = shifts.open(new CourierShiftService.OpenShift(
                TENANT, BRAND, branch, courierId, ShiftActor.COURIER, courier(), "opening again", null, "UZS"));

        List<ShiftRow> windowed = shiftStore.atLocation(
                TENANT, BRAND, branch, NOON.plus(Duration.ofDays(9)), NOON.plus(Duration.ofDays(11)), 50);

        assertThat(windowed).extracting(ShiftRow::id).containsExactly(later.id());
        assertThat(windowed).extracting(ShiftRow::id).doesNotContain(earlier.id());
    }

    // --------------------------------------------------------------- comparison

    @Test
    @DisplayName("a planned shift matched by an overlapping actual shift of the same courier is COVERED")
    void aMatchingShiftIsCovered() {
        PlannedShiftRow entry = plannedShifts.draft(newShift(NOON, NOON.plus(Duration.ofHours(8))));

        ShiftRow opened = shifts.open(new CourierShiftService.OpenShift(
                TENANT, BRAND, branch, courierId, ShiftActor.COURIER, courier(), "opening on time", null, "UZS"));

        List<RosterComparison> comparisons = plannedShifts.comparisonAt(
                TENANT, BRAND, branch, NOON.minus(Duration.ofHours(1)), NOON.plus(Duration.ofHours(9)), 50);

        assertThat(comparisons).hasSize(1);
        RosterComparison comparison = comparisons.get(0);
        assertThat(comparison.entry().id()).isEqualTo(entry.id());
        assertThat(comparison.coverage()).isEqualTo("COVERED");
        ShiftRow matched = comparison.matchedShift();
        assertThat(matched).isNotNull();
        assertThat(Objects.requireNonNull(matched).id()).isEqualTo(opened.id());
    }

    @Test
    @DisplayName(
            "a planned shift with no matching actual shift is PENDING before its window elapses and UNCOVERED after")
    void anUnmatchedEntryIsPendingThenUncovered() {
        PlannedShiftRow entry = plannedShifts.draft(newShift(NOON, NOON.plus(Duration.ofHours(2))));

        List<RosterComparison> beforeItElapses = plannedShifts.comparisonAt(
                TENANT, BRAND, branch, NOON.minus(Duration.ofHours(1)), NOON.plus(Duration.ofHours(3)), 50);
        assertThat(beforeItElapses).hasSize(1);
        assertThat(beforeItElapses.get(0).coverage()).isEqualTo("PENDING");
        assertThat(beforeItElapses.get(0).matchedShift()).isNull();

        clock.set(NOON.plus(Duration.ofHours(3)));
        List<RosterComparison> afterItElapses = plannedShifts.comparisonAt(
                TENANT, BRAND, branch, NOON.minus(Duration.ofHours(1)), NOON.plus(Duration.ofHours(3)), 50);
        assertThat(afterItElapses).hasSize(1);
        assertThat(afterItElapses.get(0).coverage()).isEqualTo("UNCOVERED");
        assertThat(entry.id()).isEqualTo(afterItElapses.get(0).entry().id());
    }

    // ---------------------------------------------------------- tenant isolation

    @Test
    @DisplayName("a planned shift is invisible to every tenant but the one that authored it")
    void tenantIsolationHolds() {
        PlannedShiftRow entry = plannedShifts.draft(newShift(NOON, NOON.plus(Duration.ofHours(8))));

        assertThat(rosterStore.find(OTHER_TENANT, entry.id())).isEmpty();
        assertThat(plannedShifts.atLocation(OTHER_TENANT, BRAND, branch, null, null, 50))
                .isEmpty();
        // publish/cancel under the wrong tenant find nothing to act on either --
        // the same "not found" a stranger id gets, never a cross-tenant mutation.
        assertThat(catchThrowable(() -> plannedShifts.publish(
                        OTHER_TENANT, BRAND, branch, entry.id(), manager(), UUID.randomUUID(), "x")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a foreign key rejects a planned shift naming another tenant's engagement for this tenant's location")
    void crossTenantEngagementIsRejectedByTheForeignKey() {
        // OTHER_TENANT's own courier/engagement, inserted directly so the
        // mismatch is visible at the row level rather than filtered by a
        // service that already refuses it -- the schema is the thing under test.
        UUID foreignEngagementId = UUID.randomUUID();
        Throwable failure = catchThrowable(() -> jdbc.sql("""
                        INSERT INTO fulfillment.courier_roster_entries (
                            id, tenant_id, brand_id, location_id, courier_id, engagement_id,
                            status, planned_start, planned_end, created_by, version, created_at, updated_at)
                        VALUES (:id, :tenantId, :brandId, :locationId, :courierId, :foreignEngagementId,
                            'DRAFT', :start, :end, :createdBy, 1, now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", branch)
                .param("courierId", courierId)
                .param("foreignEngagementId", foreignEngagementId)
                .param("start", java.time.OffsetDateTime.ofInstant(NOON, ZoneOffset.UTC))
                .param("end", java.time.OffsetDateTime.ofInstant(NOON.plus(Duration.ofHours(1)), ZoneOffset.UTC))
                .param("createdBy", UUID.randomUUID())
                .update());

        assertThat(failure).isNotNull();
    }

    // --------------------------------------------------------------------- names

    @Test
    @DisplayName("a bulk display-reference lookup answers every courier asked for and nothing about an unknown id")
    void displayReferenceLookupIsBulkAndTenantScoped() {
        var references = courierStore.displayReferencesOf(TENANT, java.util.Set.of(courierId, UUID.randomUUID()));
        assertThat(references).hasSize(1);
        assertThat(references.get(courierId)).isEqualTo("K-001");

        // An empty ask answers empty rather than issuing `IN ()`, which
        // PostgreSQL rejects outright.
        assertThat(courierStore.displayReferencesOf(TENANT, java.util.Set.of())).isEmpty();
    }

    // ------------------------------------------------------------------- fixtures

    private NewPlannedShift newShift(Instant start, Instant end) {
        return new NewPlannedShift(
                TENANT, BRAND, branch, courierId, start, end, UUID.randomUUID(), manager(), "planning ahead");
    }

    private UUID seedTenancy(UUID tenantId) {
        String suffix = tenantId.toString().substring(0, 8);
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "roster-tenant-" + suffix)
                .update();

        UUID brandId = tenantId.equals(TENANT) ? BRAND : UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("slug", "main-" + suffix)
                .update();

        UUID locationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', :slug, 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("slug", "centre-" + suffix)
                .update();

        return locationId;
    }

    private void seedCourier() {
        courierTypeId = UUID.randomUUID();
        courierStore.insertType(new CourierTypeRow(
                courierTypeId, TENANT, "SCOOTER", "Scooter", "SCOOTER", 0, 15_000, 2, 60, 0, "SHIFT", "ACTIVE", 1));

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

    private static ActorRef manager() {
        return ActorRef.user("keycloak-manager", "Branch manager");
    }

    private static ActorRef courier() {
        return ActorRef.user("keycloak-courier", "Courier");
    }

    // ------------------------------------------------------------------ fakes

    /** A clock the tests move, because the PENDING/UNCOVERED split is entirely about elapsed time. */
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

    private static final class AdvisoryPolicies implements PolicyResolver {

        @Override
        @SuppressWarnings("unchecked")
        public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
            var defaults = uz.horecaos.platform.courier.domain.CourierCompensationPolicy.DEFAULTS;
            var document = new uz.horecaos.platform.courier.domain.CourierCompensationPolicy(
                    defaults.reverificationDays(),
                    defaults.warningDays(),
                    defaults.settlementPeriodDays(),
                    defaults.cashCeilingMinor(),
                    defaults.penaltyApprovalThresholdMinor(),
                    ShiftEnforcement.ADVISORY,
                    defaults.graceSeconds(),
                    defaults.confirmationPointRetentionDays());
            return Optional.of((ResolvedPolicy<P>)
                    new ResolvedPolicy<>(key.code(), UUID.randomUUID(), 1, scope.type(), "test", document));
        }

        @Override
        public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
            return resolve(key, ResourceScope.tenant(TENANT));
        }
    }
}
