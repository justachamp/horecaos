package uz.horecaos.platform.commercial.application;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;

import tools.jackson.databind.json.JsonMapper;

import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.commercial.api.Boundary;
import uz.horecaos.platform.commercial.api.EnforcementMode;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementSource;
import uz.horecaos.platform.commercial.api.LimitCheck;
import uz.horecaos.platform.commercial.api.ResetPeriod;
import uz.horecaos.platform.commercial.api.UsageMovement;
import uz.horecaos.platform.commercial.domain.PlanEntitlement;
import uz.horecaos.platform.commercial.domain.SubscriptionStatus;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlanStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcSubscriptionStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0021 end to end against PostgreSQL.
 *
 * <p>The cases are the ones the ADR's testing section names, plus the two the
 * minimum-viable-cutover document turns into hard requirements: a meter-only
 * tenant is never refused anything, and raising the ceiling for one tenant
 * leaves every other tenant exactly as it was.
 *
 * <p>The plan used throughout is the console prototype's Network line — 9 000 000
 * so'm, twenty locations included, 250 000 per extra one — so the arithmetic the
 * tests assert is arithmetic somebody has already had to defend on a phone call.
 */
class CommercialPlatformTests {

    private static final UUID PILOT = UUID.fromString("018f6f4e-2100-7000-8000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("018f6f4e-2100-7000-8000-0000000000a2");

    private static final Instant NOW = Instant.parse("2026-08-23T09:00:00Z");
    private static final String AUTHOR = "commercial.author";
    private static final String APPROVER = "commercial.approver";

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private JdbcPlanStore planStore;
    private JdbcSubscriptionStore subscriptionStore;
    private JdbcUsageStore usageStore;
    private PlanCatalogService plans;
    private SubscriptionService subscriptions;
    private EntitlementQueryService entitlements;
    private UsageMeteringService metering;
    private RecordingAuditRecorder audit;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("""
                TRUNCATE TABLE commercial.usage_aggregates, commercial.usage_adjustments,
                    commercial.usage_events, commercial.entitlement_overrides,
                    commercial.subscriptions
                """).update();
        jdbc.sql("TRUNCATE TABLE commercial.plan_entitlements, commercial.plan_versions, commercial.plans CASCADE")
                .update();
        jdbc.sql("DELETE FROM tenant.configuration_values WHERE key_code = 'commercial.enforcement_ceiling'")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenant(PILOT, "pilot-restaurant", "Non uyi");
        seedTenant(OTHER, "other-restaurant", "Osh Markazi");

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        planStore = new JdbcPlanStore(jdbc);
        subscriptionStore = new JdbcSubscriptionStore(jdbc);
        usageStore = new JdbcUsageStore(jdbc, JsonMapper.builder().build());
        audit = new RecordingAuditRecorder();

        EnforcementCeiling ceiling = new EnforcementCeiling(new JdbcConfigurationResolver(jdbc));
        entitlements = new EntitlementQueryService(subscriptionStore, planStore, usageStore, ceiling, clock);
        metering = new UsageMeteringService(usageStore, entitlements, clock);
        plans = new PlanCatalogService(planStore, audit, clock);
        subscriptions = new SubscriptionService(subscriptionStore, planStore, entitlements, audit, clock);
    }

    // -------------------------------------------------------- the plan catalogue

    @Test
    @DisplayName("activated plan terms cannot mutate in place")
    void anActivatedPlanVersionIsImmutable() {
        UUID versionId = activateNetworkPlan();

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE commercial.plan_versions SET price_minor = 1 WHERE id = :id
                """).param("id", versionId).update())
                .as("the refusal is at the database, where a support script cannot route around it")
                .hasMessageContaining("Activated plan terms are immutable");

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE commercial.plan_entitlements SET integer_value = 999
                 WHERE plan_version_id = :id
                """).param("id", versionId).update())
                .hasMessageContaining("entitlements of an activated plan version are immutable");
    }

    @Test
    void aPlanVersionIsNotApprovedByItsOwnAuthor() {
        UUID planId = plans.createPlan("NETWORK", "Network", ActorRef.user(AUTHOR, null),
                "the price list", "corr");
        UUID versionId = draftNetworkVersion(planId);

        assertThatThrownBy(() -> plans.activate(versionId, ActorRef.user(AUTHOR, null),
                "activating my own draft", "corr"))
                .isInstanceOf(ApiException.class)
                .as("the person who typed a price is the last person able to notice a "
                        + "misplaced digit in it")
                .hasMessageContaining("other than its author");
    }

    @Test
    void anUnknownEntitlementKeyFailsPlanActivation() {
        UUID planId = plans.createPlan("BASIC", "Basic", ActorRef.user(AUTHOR, null),
                "the price list", "corr");

        assertThatThrownBy(() -> plans.draftVersion(planId, "UZS", 1_200_000, "MONTHLY", null,
                Map.of("locations.maxcount", PlanEntitlement.counted("locations.maxcount", 1,
                        EnforcementMode.SOFT, ResetPeriod.NONE, null, null)),
                ActorRef.user(AUTHOR, null), "typo", "corr"))
                .isInstanceOf(ApiException.class)
                .as("a key the code does not declare would sit in the table resolving to "
                        + "nothing, which reads as unlimited")
                .hasMessageContaining("Unknown entitlement key");
    }

    // ------------------------------------------------------------ the meter

    @Test
    @DisplayName("a redelivered event does not count twice")
    void duplicateMovementsDoNotDoubleCount() {
        UsageMovement movement = UsageMovement.of(PILOT, EntitlementKeys.ORDERS_MONTHLY_INCLUDED,
                1, "ordering.OrderConfirmed", "order-4711", NOW);

        assertThat(metering.record(movement)).isTrue();
        assertThat(metering.record(movement))
                .as("at-least-once delivery is the contract; a second recording is ignored "
                        + "rather than rejected")
                .isFalse();

        assertThat(consumed("orders.monthly_included", "2026-08")).isEqualTo(1);
    }

    @Test
    @DisplayName("the ledger is append-only, at the database")
    void aRecordedMovementCannotBeEditedOrDeleted() {
        metering.record(UsageMovement.of(PILOT, EntitlementKeys.ORDERS_MONTHLY_INCLUDED,
                1, "ordering.OrderConfirmed", "order-1", NOW));

        assertThatThrownBy(() -> jdbc.sql("UPDATE commercial.usage_events SET quantity = 500").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM commercial.usage_events").update())
                .as("a disputed figure must not be quietly repaired into an undisputed one")
                .hasMessageContaining("append-only");
    }

    @Test
    void aRebuildReproducesTheLiveAggregateIncludingAdjustments() {
        for (int index = 0; index < 40; index++) {
            metering.record(UsageMovement.of(PILOT, EntitlementKeys.ORDERS_MONTHLY_INCLUDED,
                    1, "ordering.OrderConfirmed", "order-" + index, NOW));
        }
        metering.adjust(PILOT, EntitlementKeys.ORDERS_MONTHLY_INCLUDED, "2026-08", -5,
                "duplicated by a consumer bug on 21 August", "INC-114", AUTHOR, APPROVER);

        long before = consumed("orders.monthly_included", "2026-08");

        // Throw the cache away entirely. Nothing is lost, which is the property
        // an append-only ledger buys and a counter updated in place cannot.
        jdbc.sql("TRUNCATE TABLE commercial.usage_aggregates").update();
        List<UsageMeteringService.Divergence> divergences = metering.rebuild(PILOT);

        assertThat(before).isEqualTo(35);
        assertThat(consumed("orders.monthly_included", "2026-08")).isEqualTo(before);
        assertThat(divergences)
                .as("the rebuild reports what it repaired; the cache was empty, so one entry")
                .hasSize(1);
        assertThat(metering.rebuild(PILOT))
                .as("a second rebuild changes nothing, which is what makes a non-empty "
                        + "answer an alert rather than noise")
                .isEmpty();
    }

    @Test
    void aStandingCountFallsWhenSomethingIsRemoved() {
        metering.record(UsageMovement.of(PILOT, EntitlementKeys.LOCATIONS_MAX_COUNT,
                1, "tenancy.LocationCreated", "loc-1", NOW));
        metering.record(UsageMovement.of(PILOT, EntitlementKeys.LOCATIONS_MAX_COUNT,
                1, "tenancy.LocationCreated", "loc-2", NOW));
        metering.record(UsageMovement.of(PILOT, EntitlementKeys.LOCATIONS_MAX_COUNT,
                -1, "tenancy.LocationClosed", "loc-2", NOW));

        assertThat(consumed("locations.max_count", "LIFETIME"))
                .as("a branch that closed is a movement of minus one, not the deletion of "
                        + "the row that opened it")
                .isEqualTo(1);
    }

    @Test
    void aMovementCannotCarryADimensionOutsideTheAllowlist() {
        assertThatThrownBy(() -> new UsageMovement(PILOT, EntitlementKeys.ORDERS_MONTHLY_INCLUDED,
                1, "ordering.OrderConfirmed", "order-1", NOW,
                Map.of("customer_id", UUID.randomUUID().toString())))
                .isInstanceOf(IllegalArgumentException.class)
                .as("the ledger is append-only, so anything personal that reaches it can "
                        + "never be scrubbed (ADR 0029)")
                .hasMessageContaining("not allowlisted");
    }

    // ------------------------------------------------------- meter-only safety

    @Test
    @DisplayName("a meter-only tenant is never refused, at any multiple of its limit")
    void meterOnlyNeverRefuses() {
        UUID versionId = activateNetworkPlan();
        subscriptions.start(PILOT, versionId, null, ActorRef.user(AUTHOR, null),
                "pilot onboarding", "corr");

        recordLocations(PILOT, 200);

        LimitCheck check = entitlements.check(PILOT, EntitlementKeys.LOCATIONS_MAX_COUNT, 1);

        assertThatCode(() -> entitlements.require(PILOT, EntitlementKeys.LOCATIONS_MAX_COUNT, 1))
                .as("an entitlement check that starts refusing things mid-pilot is a "
                        + "self-inflicted outage")
                .doesNotThrowAnyException();
        assertThat(check.value().effectiveMode()).isEqualTo(EnforcementMode.METER_ONLY);
        assertThat(check.boundary().allowed()).isTrue();
        assertThat(check.wouldBe())
                .as("and the platform still knows the plan would have billed for 181 branches")
                .isEqualTo(Boundary.OVER_BILLABLE);
        assertThat(check.overageQuantity()).isEqualTo(181);
        assertThat(check.overageChargeMinor())
                .as("measured, not invoiced, until somebody raises the ceiling")
                .isNull();
    }

    @Test
    void raisingTheCeilingEnforcesOneTenantAndLeavesTheOtherAlone() {
        UUID versionId = activateNetworkPlan();
        subscriptions.start(PILOT, versionId, null, ActorRef.user(AUTHOR, null), "pilot", "corr");
        subscriptions.start(OTHER, versionId, null, ActorRef.user(AUTHOR, null), "second", "corr");
        recordLocations(PILOT, 25);
        recordLocations(OTHER, 25);

        setCeiling(PILOT, "SOFT");

        LimitCheck enforced = entitlements.check(PILOT, EntitlementKeys.LOCATIONS_MAX_COUNT, 0);
        LimitCheck untouched = entitlements.check(OTHER, EntitlementKeys.LOCATIONS_MAX_COUNT, 0);

        assertThat(enforced.boundary()).isEqualTo(Boundary.OVER_BILLABLE);
        assertThat(enforced.overageChargeMinor())
                .as("five extra branches at 250 000 so'm; a minor unit of UZS is one whole som")
                .isEqualTo(1_250_000L);
        assertThat(untouched.boundary())
                .as("a ceiling is per tenant; enabling enforcement for a pilot must not "
                        + "enforce the whole estate")
                .isEqualTo(Boundary.OVER_UNBILLED);
        assertThat(untouched.overageChargeMinor()).isNull();
    }

    @Test
    void aHardCeilingRefusesWithSomethingTheTenantCanActOn() {
        UUID versionId = activateHardLimitPlan();
        subscriptions.start(PILOT, versionId, null, ActorRef.user(AUTHOR, null), "pilot", "corr");
        recordLocations(PILOT, 20);
        setCeiling(PILOT, "HARD");

        assertThatThrownBy(() -> entitlements.require(PILOT, EntitlementKeys.LOCATIONS_MAX_COUNT, 1))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException failure = (ApiException) thrown;
                    assertThat(failure.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_REQUIRED);
                    assertThat(failure.properties())
                            .as("a refusal a tenant cannot act on is an outage with extra steps")
                            .containsEntry("limit", 20L)
                            .containsEntry("consumed", 20L)
                            .containsKey("upgradePath");
                });
    }

    @Test
    void anOverrideRaisesOneTenantsLimitWithoutTouchingThePlan() {
        UUID versionId = activateHardLimitPlan();
        subscriptions.start(PILOT, versionId, null, ActorRef.user(AUTHOR, null), "pilot", "corr");
        recordLocations(PILOT, 20);
        setCeiling(PILOT, "HARD");

        subscriptions.override(PILOT, EntitlementKeys.LOCATIONS_MAX_COUNT.code(), 40L, null,
                NOW.plusSeconds(86_400), ActorRef.user(AUTHOR, null), APPROVER,
                "acquisition closes on Monday and the contract is being redrawn", "corr");

        LimitCheck check = entitlements.check(PILOT, EntitlementKeys.LOCATIONS_MAX_COUNT, 1);

        assertThat(check.boundary()).isEqualTo(Boundary.WITHIN);
        assertThat(check.value().source()).isEqualTo(EntitlementSource.TENANT_OVERRIDE);
        assertThat(entitlements.check(OTHER, EntitlementKeys.LOCATIONS_MAX_COUNT, 1).limit())
                .as("an override is one tenant's exception, never a change to the plan")
                .isNull();
    }

    // ------------------------------------------------------ subscriptions

    @Test
    void aSuspensionBlocksAdditionsAndDeletesNothing() {
        UUID versionId = activateNetworkPlan();
        subscriptions.start(PILOT, versionId, null, ActorRef.user(AUTHOR, null), "pilot", "corr");
        recordLocations(PILOT, 5);
        setCeiling(PILOT, "HARD");

        subscriptions.transition(PILOT, SubscriptionStatus.SUSPENDED, 1,
                "three invoices unpaid at ninety days", null, ActorRef.user(AUTHOR, null),
                "collections decision of 23 August", "corr");

        assertThatThrownBy(() -> entitlements.require(PILOT, EntitlementKeys.LOCATIONS_MAX_COUNT, 1))
                .isInstanceOf(ApiException.class);
        assertThat(consumed("locations.max_count", "LIFETIME"))
                .as("the five branches that exist are untouched; ADR 0021 refuses to destroy "
                        + "data over a commercial dispute")
                .isEqualTo(5);
        assertThat(jdbc.sql("SELECT status FROM tenant.tenants WHERE id = :id")
                .param("id", PILOT).query(String.class).single())
                .as("and the tenant itself is not suspended by a billing decision")
                .isEqualTo("ACTIVE");
    }

    @Test
    void anImpossibleTransitionIsRefusedRatherThanRecorded() {
        UUID versionId = activateNetworkPlan();
        subscriptions.start(PILOT, versionId, null, ActorRef.user(AUTHOR, null), "pilot", "corr");
        subscriptions.transition(PILOT, SubscriptionStatus.TERMINATED, 1, null, null,
                ActorRef.user(AUTHOR, null), "the restaurant closed", "corr");

        assertThatThrownBy(() -> subscriptions.transition(PILOT, SubscriptionStatus.ACTIVE, 2,
                null, null, ActorRef.user(AUTHOR, null), "reinstate", "corr"))
                .isInstanceOf(ApiException.class)
                .as("a terminated subscription is restarted by starting a new one, so the "
                        + "terms it restarts under are recorded rather than assumed")
                .hasMessageContaining("no live subscription");
    }

    @Test
    void everyCommercialDecisionLeavesAnAuditFactWithTheEntitlementHash() {
        UUID versionId = activateNetworkPlan();
        subscriptions.start(PILOT, versionId, null, ActorRef.user(AUTHOR, null), "pilot", "corr");

        assertThat(audit.facts.stream().map(AuditFact::actionCode))
                .contains("commercial.plan.created", "commercial.plan_version.drafted",
                        "commercial.plan_version.activated", "commercial.subscription.started");
        assertThat(audit.facts.stream()
                .filter(fact -> fact.actionCode().equals("commercial.subscription.started"))
                .findFirst().orElseThrow().changeDocument())
                .as("\"the tenant was on Growth\" is not a defence; the hash of what it was "
                        + "actually entitled to is")
                .containsKey("entitlementHash");
    }

    // ------------------------------------------------------- tenant isolation

    @Test
    void usageAndEntitlementsNeverCrossATenantBoundary() {
        UUID versionId = activateNetworkPlan();
        subscriptions.start(OTHER, versionId, null, ActorRef.user(AUTHOR, null), "second", "corr");
        recordLocations(OTHER, 30);

        assertThat(consumed("locations.max_count", "LIFETIME")).isZero();
        assertThat(entitlements.check(PILOT, EntitlementKeys.LOCATIONS_MAX_COUNT, 0).consumed())
                .isZero();
        assertThat(entitlements.snapshot(PILOT).subscriptionId())
                .as("a tenant with no subscription reads none of its neighbour's")
                .isNull();
    }

    // ------------------------------------------------------------- fixtures

    private UUID activateNetworkPlan() {
        UUID planId = plans.createPlan("NETWORK", "Network", ActorRef.user(AUTHOR, null),
                "the price list", "corr");
        UUID versionId = draftNetworkVersion(planId);
        plans.activate(versionId, ActorRef.user(APPROVER, null), "signed off by finance", "corr");
        return versionId;
    }

    /** The console prototype's Network line, priced exactly as it shows it. */
    private UUID draftNetworkVersion(UUID planId) {
        return plans.draftVersion(planId, "UZS", 9_000_000, "MONTHLY", "TERMS-2026-NETWORK",
                Map.of(
                        EntitlementKeys.LOCATIONS_MAX_COUNT.code(), PlanEntitlement.counted(
                                EntitlementKeys.LOCATIONS_MAX_COUNT.code(), 20,
                                EnforcementMode.SOFT, ResetPeriod.NONE, 8_000, 250_000L),
                        EntitlementKeys.ORDERS_MONTHLY_INCLUDED.code(), PlanEntitlement.counted(
                                EntitlementKeys.ORDERS_MONTHLY_INCLUDED.code(), 20_000,
                                EnforcementMode.SOFT, ResetPeriod.BILLING_PERIOD, 8_000, 500L)),
                ActorRef.user(AUTHOR, null), "the 2026 price list", "corr");
    }

    private UUID activateHardLimitPlan() {
        UUID planId = plans.createPlan("BASIC", "Basic", ActorRef.user(AUTHOR, null),
                "the price list", "corr");
        UUID versionId = plans.draftVersion(planId, "UZS", 1_200_000, "MONTHLY", null,
                Map.of(EntitlementKeys.LOCATIONS_MAX_COUNT.code(), PlanEntitlement.counted(
                        EntitlementKeys.LOCATIONS_MAX_COUNT.code(), 20,
                        EnforcementMode.HARD, ResetPeriod.NONE, 8_000, null)),
                ActorRef.user(AUTHOR, null), "the 2026 price list", "corr");
        plans.activate(versionId, ActorRef.user(APPROVER, null), "signed off by finance", "corr");
        return versionId;
    }

    private void recordLocations(UUID tenantId, int count) {
        for (int index = 0; index < count; index++) {
            metering.record(UsageMovement.of(tenantId, EntitlementKeys.LOCATIONS_MAX_COUNT,
                    1, "tenancy.LocationCreated", tenantId + "-loc-" + index, NOW));
        }
    }

    private void setCeiling(UUID tenantId, String mode) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (
                    id, key_code, scope_type, tenant_id, value_type, string_value, set_by, reason)
                VALUES (:id, 'commercial.enforcement_ceiling', 'TENANT', :tenantId, 'STRING',
                    :value, 'test', 'staged rollout')
                """)
                .param("id", UUID.randomUUID()).param("tenantId", tenantId).param("value", mode)
                .update();
    }

    private long consumed(String entitlementKey, String periodKey) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(consumed_quantity), 0) FROM commercial.usage_aggregates
                 WHERE tenant_id = :tenantId AND entitlement_key = :key AND period_key = :periodKey
                """)
                .param("tenantId", PILOT).param("key", entitlementKey).param("periodKey", periodKey)
                .query(Long.class)
                .single();
    }

    private void seedTenant(UUID id, String slug, String name) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, :name, :name, 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id).param("slug", slug).param("name", name)
                .update();
    }

    /** Keeps the facts so a test can assert what was recorded, not merely that something was. */
    private static final class RecordingAuditRecorder implements AuditRecorder {
        private final List<AuditFact> facts = new ArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }
    }
}
