package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService;
import uz.horecaos.platform.commercial.api.EnforcementMode;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementSource;
import uz.horecaos.platform.commercial.api.EntitlementValue;
import uz.horecaos.platform.commercial.api.ResetPeriod;
import uz.horecaos.platform.commercial.api.UsageMovement;
import uz.horecaos.platform.commercial.domain.BillingUnit;
import uz.horecaos.platform.commercial.domain.PlanEntitlement;
import uz.horecaos.platform.commercial.domain.PlanTerms;
import uz.horecaos.platform.commercial.domain.Statement;
import uz.horecaos.platform.commercial.domain.StatementLine;
import uz.horecaos.platform.commercial.domain.SubscriptionStatus;
import uz.horecaos.platform.commercial.infrastructure.NotConfiguredCardCharger;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcArrearsStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcModuleStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlanStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcStatementStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcSubscriptionStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcWalletStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADRs 0087 to 0089 against PostgreSQL: a module switches a feature on and is
 * billed on its own unit, a month is closed by a statement that bills the
 * same thing every time it is computed, and a tenant in arrears is on the
 * board for as long as it has been there.
 */
class ModulesStatementsAndArrearsTests {

    private static final UUID PILOT = UUID.fromString("018f6f4e-2100-7000-8000-0000000000b1");

    private static final Instant JULY = Instant.parse("2026-07-15T09:00:00Z");
    private static final Instant AUGUST = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant SEPTEMBER = Instant.parse("2026-09-11T09:00:00Z");

    private static final ActorRef AUTHOR = ActorRef.user("commercial.author", null);
    private static final ActorRef APPROVER = ActorRef.user("commercial.approver", null);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MovableClock clock;
    private List<AuditFact> facts;
    private PlanCatalogService plans;
    private SubscriptionService subscriptions;
    private EntitlementQueryService entitlements;
    private UsageMeteringService metering;
    private ModuleCatalogService modules;
    private StatementService statements;
    private ArrearsService arrears;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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
                TRUNCATE TABLE commercial.usage_aggregates, commercial.usage_adjustments,
                    commercial.usage_events, commercial.entitlement_overrides,
                    commercial.subscriptions, commercial.wallet_entries, commercial.tenant_billing,
                    commercial.statement_lines, commercial.statements,
                    commercial.tenant_modules, commercial.modules
                """).update();
        jdbc.sql("TRUNCATE TABLE commercial.plan_entitlements, commercial.plan_versions, commercial.plans CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'pilot', 'Non uyi', 'Non uyi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", PILOT).update();

        clock = new MovableClock(JULY);
        facts = new ArrayList<>();
        AuditRecorder audit = facts::add;
        JdbcPlanStore planStore = new JdbcPlanStore(jdbc);
        JdbcSubscriptionStore subscriptionStore = new JdbcSubscriptionStore(jdbc);
        JdbcUsageStore usageStore =
                new JdbcUsageStore(jdbc, JsonMapper.builder().build());
        JdbcModuleStore moduleStore = new JdbcModuleStore(jdbc);
        JdbcStatementStore statementStore = new JdbcStatementStore(jdbc);
        JdbcWalletStore walletStore = new JdbcWalletStore(jdbc);
        ApprovalService approvals = new JdbcApprovalService(jdbc, audit, clock, new SimpleMeterRegistry());
        WalletService wallet = new WalletService(
                walletStore, subscriptionStore, approvals, new NotConfiguredCardCharger(), audit, clock);

        EnforcementCeiling ceiling = new EnforcementCeiling(new JdbcConfigurationResolver(jdbc));
        entitlements =
                new EntitlementQueryService(subscriptionStore, planStore, usageStore, moduleStore, ceiling, clock);
        metering = new UsageMeteringService(usageStore, entitlements, clock);
        plans = new PlanCatalogService(planStore, audit, clock);
        subscriptions = new SubscriptionService(subscriptionStore, planStore, entitlements, audit, clock);
        modules = new ModuleCatalogService(moduleStore, audit, clock);
        statements = new StatementService(
                subscriptionStore, planStore, moduleStore, statementStore, usageStore, wallet, audit, clock);
        arrears = new ArrearsService(new JdbcArrearsStore(jdbc), statementStore);
    }

    // ------------------------------------------------------------- modules

    @Test
    void aModuleSwitchesOnAFeatureThePlanLeftOffUntilItEnds() {
        startOnBasicPlan();
        UUID analytics = activeModule(
                "analytics", BillingUnit.PER_TENANT, 150_000, EntitlementKeys.TELEGRAM_DIGESTS_ENABLED.code());
        assertThat(digestsValue().featureEnabled()).isFalse();

        UUID held = modules.add(PILOT, analytics, null, AUTHOR, "sold with the pilot", "corr");

        EntitlementValue on = digestsValue();
        assertThat(on.featureEnabled()).isTrue();
        assertThat(on.source()).isEqualTo(EntitlementSource.MODULE);

        modules.end(PILOT, held, AUTHOR, "the owner cancelled it", "corr");
        assertThat(digestsValue().featureEnabled()).isFalse();
        assertThat(digestsValue().source()).isNotEqualTo(EntitlementSource.MODULE);
    }

    @Test
    void aModuleLapsesWithTheSubscriptionsEntitlements() {
        startOnBasicPlan();
        UUID analytics = activeModule(
                "analytics", BillingUnit.PER_TENANT, 150_000, EntitlementKeys.TELEGRAM_DIGESTS_ENABLED.code());
        modules.add(PILOT, analytics, null, AUTHOR, "sold", "corr");

        long version = subscriptions.live(PILOT).orElseThrow().version();
        subscriptions.transition(
                PILOT, SubscriptionStatus.SUSPENDED, version, "unpaid", null, AUTHOR, "three months late", "corr");

        assertThat(digestsValue().featureEnabled())
                .as("a suspended subscription does not keep a module's features running")
                .isFalse();
    }

    @Test
    void aModuleIsActivatedBySomebodyElseAndIsFrozenOnceItIs() {
        UUID id = modules.draft(
                "kiosk", "Kiosk", null, BillingUnit.PER_UNIT, "UZS", 50_000, List.of(), AUTHOR, "new", "corr");

        assertThatThrownBy(() -> modules.activate(id, AUTHOR, "my own", "corr"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("somebody other than");
        modules.activate(id, APPROVER, "signed off", "corr");

        assertThatThrownBy(() -> jdbc.sql("UPDATE commercial.modules SET unit_price_minor = 1 WHERE id = :id")
                        .param("id", id)
                        .update())
                .hasMessageContaining("Activated module terms are immutable");
        assertThat(modules.all())
                .singleElement()
                .satisfies(module -> assertThat(module.approvedBy()).isEqualTo("commercial.approver"));
    }

    @Test
    void aModuleSwitchesFeaturesOnlyAndAPerUnitModuleNeedsAQuantity() {
        assertThatThrownBy(() -> modules.draft(
                        "more-branches",
                        "More branches",
                        null,
                        BillingUnit.PER_TENANT,
                        "UZS",
                        1,
                        List.of(EntitlementKeys.LOCATIONS_MAX_COUNT.code()),
                        AUTHOR,
                        "x",
                        "corr"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("counted limit");

        UUID kiosk = activeModule("kiosk", BillingUnit.PER_UNIT, 50_000);
        assertThatThrownBy(() -> modules.add(PILOT, kiosk, null, AUTHOR, "no count", "corr"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("say how many");
        UUID app = activeModule("white-label", BillingUnit.ONE_OFF, 1_000_000);
        assertThatThrownBy(() -> modules.add(PILOT, app, 2, AUTHOR, "a count it does not take", "corr"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("takes no quantity");
    }

    // ---------------------------------------------------------- statements

    @Test
    void aClosedMonthBillsThePlanTheModulesAndTheOverageAndReadsTheSameEveryTime() {
        startOnBasicPlan();
        clock.set(AUGUST);
        recordLocations(3);
        recordOrders(5);
        modules.add(PILOT, activeModule("kds", BillingUnit.PER_LOCATION, 100_000), null, AUTHOR, "kitchens", "corr");
        modules.add(PILOT, activeModule("kiosk", BillingUnit.PER_UNIT, 50_000), 2, AUTHOR, "two kiosks", "corr");
        modules.add(
                PILOT, activeModule("white-label", BillingUnit.ONE_OFF, 1_000_000), null, AUTHOR, "the app", "corr");
        clock.set(SEPTEMBER);

        Statement august = statements.draft(PILOT, "2026-08");

        assertThat(august.lines())
                .extracting(
                        StatementLine::kind,
                        StatementLine::referenceCode,
                        StatementLine::quantity,
                        StatementLine::amountMinor)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PLAN", "BASIC@v1", 1L, 1_200_000L),
                        org.assertj.core.groups.Tuple.tuple(
                                "OVERAGE", EntitlementKeys.ORDERS_MONTHLY_INCLUDED.code(), 3L, 1_500L),
                        org.assertj.core.groups.Tuple.tuple("MODULE", "kds", 3L, 300_000L),
                        org.assertj.core.groups.Tuple.tuple("MODULE", "kiosk", 2L, 100_000L),
                        org.assertj.core.groups.Tuple.tuple("MODULE", "white-label", 1L, 1_000_000L));
        assertThat(august.totalMinor()).isEqualTo(2_601_500L);
        assertThat(august.periodStart()).isEqualTo(Instant.parse("2026-07-31T19:00:00Z"));

        // A branch opened after the month closed does not change what the month bills.
        recordLocations(1, "late");
        assertThat(statements.draft(PILOT, "2026-08").totalMinor()).isEqualTo(2_601_500L);

        Statement september = statements.draft(PILOT, "2026-09");
        assertThat(september.lines())
                .as("a one-off is billed only in the month it was added")
                .extracting(StatementLine::referenceCode)
                .doesNotContain("white-label");
    }

    @Test
    void aStatementIsIssuedOnceForAnEndedMonthAndCorrectedByVoiding() {
        startOnBasicPlan();
        clock.set(SEPTEMBER);

        assertThatThrownBy(() -> statements.issue(PILOT, "2026-09", AUTHOR, "too early", "corr"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("has not ended");

        StatementService.IssuedRef first = statements.issue(PILOT, "2026-08", AUTHOR, "August close", "corr");
        assertThat(first.number()).startsWith("S-2026-08-");
        assertThatThrownBy(() -> statements.issue(PILOT, "2026-08", AUTHOR, "again", "corr"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        assertThatThrownBy(() -> jdbc.sql("UPDATE commercial.statements SET total_minor = 1 WHERE id = :id")
                        .param("id", first.statementId())
                        .update())
                .hasMessageContaining("only ever moves to VOID");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM commercial.statement_lines WHERE statement_id = :id")
                        .param("id", first.statementId())
                        .update())
                .hasMessageContaining("written once");

        statements.voidStatement(PILOT, first.statementId(), AUTHOR, "wrong plan", "corr");
        StatementService.IssuedRef second = statements.issue(PILOT, "2026-08", AUTHOR, "August, corrected", "corr");

        assertThat(second.number()).isNotEqualTo(first.number());
        assertThat(statements.list(PILOT)).extracting(Statement::status).containsExactlyInAnyOrder("VOID", "ISSUED");
        assertThat(statements.find(PILOT, second.statementId()).lines()).hasSize(1);
        assertThat(facts)
                .extracting(AuditFact::actionCode)
                .contains("commercial.statement.issued", "commercial.statement.voided");
    }

    @Test
    void aMonthWithNothingToBillIsNotIssued() {
        clock.set(SEPTEMBER);

        assertThat(statements.draft(PILOT, "2026-08").lines()).isEmpty();
        assertThatThrownBy(() -> statements.issue(PILOT, "2026-08", AUTHOR, "empty", "corr"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Nothing is billable");
    }

    // ------------------------------------------------------------ plan terms

    @Test
    void aTwelveMonthTermIsBilledAtItsDiscountAndItsDepositBecomesDueInTheWalletInstead() {
        UUID versionId = activeTermsPlan();
        UUID subscriptionId = subscriptions.start(PILOT, versionId, null, 12, AUTHOR, "a year up front", "corr");
        clock.set(SEPTEMBER);

        // ADR 0095, item 6 (decided 2026-09-11): the deposit is credited to the
        // first statement through the wallet, not billed as a line.
        assertThat(depositDue(subscriptionId)).isEqualTo(500_000L);

        Statement july = statements.draft(PILOT, "2026-07");
        assertThat(july.lines())
                .extracting(StatementLine::kind, StatementLine::unitPriceMinor, StatementLine::quantity)
                .containsExactly(
                        // The fourteen-day trial ends inside July, and nothing is
                        // prorated: a month only partly in trial bills in full.
                        org.assertj.core.groups.Tuple.tuple("PLAN", 1_080_000L, 1L));
        assertThat(july.lines().getFirst().description()).contains("12-month term, 10% off");

        Statement august = statements.draft(PILOT, "2026-08");
        assertThat(august.lines())
                .extracting(StatementLine::kind, StatementLine::amountMinor)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PLAN", 1_080_000L));
    }

    private long depositDue(UUID subscriptionId) {
        return jdbc.sql("SELECT deposit_due_minor FROM commercial.subscriptions WHERE id = :id")
                .param("id", subscriptionId)
                .query(Long.class)
                .single();
    }

    /**
     * ADR 0093 as decided on 2026-09-11: leaving a term early repays the term
     * discount received. Started mid-July on a twelve-month term, charged for
     * July (partly in trial, so in full), August and September, terminated in
     * September: three charges at 1 080 000 instead of 1 200 000 repay 3 x 120 000,
     * on September's statement and no other.
     */
    @Test
    void leavingATwelveMonthTermEarlyRepaysTheDiscountInTheMonthItEnded() {
        UUID versionId = activeTermsPlan();
        subscriptions.start(PILOT, versionId, null, 12, AUTHOR, "a year up front", "corr");
        clock.set(Instant.parse("2026-09-05T09:00:00Z"));
        subscriptions.transition(
                PILOT, SubscriptionStatus.TERMINATED, 1, null, null, AUTHOR, "the restaurant closed", "corr");
        clock.set(Instant.parse("2026-10-02T09:00:00Z"));

        Statement september = statements.draft(PILOT, "2026-09");
        assertThat(september.lines())
                .extracting(StatementLine::kind, StatementLine::quantity, StatementLine::unitPriceMinor)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PLAN", 1L, 1_080_000L),
                        org.assertj.core.groups.Tuple.tuple("EARLY_EXIT", 3L, 120_000L));
        assertThat(september.lines().getLast().description()).contains("12-month term early, 10% term discount");
        assertThat(september.totalMinor()).isEqualTo(1_080_000L + 360_000L);

        assertThat(statements.draft(PILOT, "2026-08").lines())
                .as("the repayment is billed once, in the month the subscription ended")
                .extracting(StatementLine::kind)
                .containsExactly("PLAN");
    }

    @Test
    void endingAMonthToMonthSubscriptionRepaysNothing() {
        UUID versionId = activeTermsPlan();
        subscriptions.start(PILOT, versionId, null, 1, AUTHOR, "month to month", "corr");
        clock.set(Instant.parse("2026-09-05T09:00:00Z"));
        subscriptions.transition(PILOT, SubscriptionStatus.TERMINATED, 1, null, null, AUTHOR, "closed", "corr");
        clock.set(Instant.parse("2026-10-02T09:00:00Z"));

        assertThat(statements.draft(PILOT, "2026-09").lines())
                .extracting(StatementLine::kind)
                .containsExactly("PLAN");
    }

    @Test
    void aSubscriptionTakesThePlansTrialAndOnlyATermThePlanOffers() {
        UUID versionId = activeTermsPlan();

        assertThatThrownBy(() -> subscriptions.start(PILOT, versionId, null, 6, AUTHOR, "half a year", "corr"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no 6-month term");

        subscriptions.start(PILOT, versionId, null, 1, AUTHOR, "month to month", "corr");
        var live = subscriptions.live(PILOT).orElseThrow();
        assertThat(live.status()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(live.trialEndAt()).isEqualTo(JULY.plus(java.time.Duration.ofDays(14)));
        assertThat(subscriptions.termMonths(live.id())).isEqualTo(1);
    }

    @Test
    void aPlansTermsAreFrozenWithItAndOfferedOnAMonthlyPlanOnly() {
        UUID versionId = activeTermsPlan();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO commercial.plan_term_discounts (plan_version_id, term_months, discount_basis_points)
                        VALUES (:id, 6, 500)
                        """).param("id", versionId).update()).hasMessageContaining("immutable");

        UUID yearly = plans.createPlan("YEARLY", "Yearly", AUTHOR, "a yearly price", "corr");
        assertThatThrownBy(() -> plans.draftVersion(
                        yearly,
                        "UZS",
                        12_000_000,
                        "YEARLY",
                        null,
                        Map.of(),
                        new PlanTerms(null, 0, Map.of(12, 1_000)),
                        AUTHOR,
                        "a yearly term discount",
                        "corr"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("monthly plan only");
    }

    /** A monthly plan: 1 200 000 a month, a fourteen-day trial, a 500 000 deposit, 10% off a year. */
    private UUID activeTermsPlan() {
        UUID planId = plans.createPlan("TERMS", "Terms", AUTHOR, "the price list", "corr");
        UUID versionId = plans.draftVersion(
                planId,
                "UZS",
                1_200_000,
                "MONTHLY",
                null,
                Map.of(),
                new PlanTerms(14, 500_000, Map.of(12, 1_000)),
                AUTHOR,
                "terms",
                "corr");
        plans.activate(versionId, APPROVER, "signed off", "corr");
        return versionId;
    }

    // ------------------------------------------------------------- arrears

    @Test
    void aTenantPastDueIsOnTheBoardForAsLongAsItHasBeenThere() {
        startOnBasicPlan();
        clock.set(AUGUST);
        long version = subscriptions.live(PILOT).orElseThrow().version();
        subscriptions.transition(
                PILOT, SubscriptionStatus.PAST_DUE, version, null, null, AUTHOR, "July unpaid", "corr");
        clock.set(SEPTEMBER);
        statements.issue(PILOT, "2026-08", AUTHOR, "August close", "corr");

        ArrearsService.Board board = arrears.board();

        assertThat(board.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(SubscriptionStatus.PAST_DUE);
            assertThat(row.since()).isEqualTo(AUGUST);
            assertThat(row.tenantName()).isEqualTo("Non uyi");
        });
        assertThat(board.latestStatements()).containsKey(PILOT);
        assertThat(arrears.pastDueSince(SEPTEMBER.minus(java.time.Duration.ofDays(14)), 10))
                .singleElement()
                .satisfies(arrear -> assertThat(arrear.subscriptionId())
                        .isEqualTo(subscriptions.live(PILOT).orElseThrow().id()));
        assertThat(arrears.pastDueSince(SEPTEMBER.minus(java.time.Duration.ofDays(30)), 10))
                .isEmpty();
    }

    // ------------------------------------------------------------- helpers

    private EntitlementValue digestsValue() {
        return java.util.Objects.requireNonNull(
                entitlements.snapshot(PILOT).values().get(EntitlementKeys.TELEGRAM_DIGESTS_ENABLED.code()));
    }

    private UUID activeModule(String code, BillingUnit unit, long price, String... features) {
        UUID id = modules.draft(code, code, null, unit, "UZS", price, List.of(features), AUTHOR, "new line", "corr");
        modules.activate(id, APPROVER, "signed off", "corr");
        return id;
    }

    /** A monthly plan: 1 200 000 a month, two orders included, 500 for each beyond. */
    private void startOnBasicPlan() {
        UUID planId = plans.createPlan("BASIC", "Basic", AUTHOR, "the price list", "corr");
        UUID versionId = plans.draftVersion(
                planId,
                "UZS",
                1_200_000,
                "MONTHLY",
                null,
                Map.of(
                        EntitlementKeys.ORDERS_MONTHLY_INCLUDED.code(),
                        PlanEntitlement.counted(
                                EntitlementKeys.ORDERS_MONTHLY_INCLUDED.code(),
                                2,
                                EnforcementMode.SOFT,
                                ResetPeriod.MONTHLY,
                                8_000,
                                500L),
                        EntitlementKeys.LOCATIONS_MAX_COUNT.code(),
                        PlanEntitlement.counted(
                                EntitlementKeys.LOCATIONS_MAX_COUNT.code(),
                                20,
                                EnforcementMode.SOFT,
                                ResetPeriod.NONE,
                                8_000,
                                null)),
                AUTHOR,
                "the 2026 price list",
                "corr");
        plans.activate(versionId, APPROVER, "signed off", "corr");
        subscriptions.start(PILOT, versionId, null, AUTHOR, "pilot", "corr");
    }

    private void recordLocations(int count) {
        recordLocations(count, "loc");
    }

    private void recordLocations(int count, String prefix) {
        for (int index = 0; index < count; index++) {
            metering.record(UsageMovement.of(
                    PILOT,
                    EntitlementKeys.LOCATIONS_MAX_COUNT,
                    1,
                    "tenancy.LocationCreated",
                    prefix + index,
                    clock.instant()));
        }
    }

    private void recordOrders(int count) {
        for (int index = 0; index < count; index++) {
            metering.record(UsageMovement.of(
                    PILOT,
                    EntitlementKeys.ORDERS_MONTHLY_INCLUDED,
                    1,
                    "ordering.OrderPlaced",
                    "order-" + index,
                    clock.instant()));
        }
    }

    /** A clock a test moves forward, so a month can be lived through and then closed. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
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
}
