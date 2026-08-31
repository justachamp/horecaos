package uz.horecaos.platform.commercial.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.commercial.api.Boundary;
import uz.horecaos.platform.commercial.api.EnforcementMode;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementSource;
import uz.horecaos.platform.commercial.api.EntitlementValue;
import uz.horecaos.platform.commercial.api.LimitCheck;
import uz.horecaos.platform.commercial.api.ResetPeriod;
import uz.horecaos.platform.commercial.api.UsagePeriod;

/**
 * The boundary model, without a database (ADR 0021).
 *
 * <p>The question the whole module exists to answer is what happens at the 101st
 * order on a 100-order plan, and the answer differs by mode, by whether the plan
 * sells overage, and by what the tenant's enforcement ceiling permits. Every one
 * of those combinations is here, because a limit whose boundary behaviour is
 * only tested at the happy end is a limit nobody has actually tested.
 */
class EntitlementBoundaryTests {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final Instant NOW = Instant.parse("2026-08-23T09:00:00Z");
    private static final EntitlementKey<Long> ORDERS = EntitlementKeys.ORDERS_MONTHLY_INCLUDED;
    private static final EntitlementKey<Long> LOCATIONS = EntitlementKeys.LOCATIONS_MAX_COUNT;

    @Nested
    @DisplayName("the 101st order on a 100-order plan")
    class AtTheBoundary {

        @Test
        void meterOnlyAllowsItAndRecordsThatEnforcementWouldNotHave() {
            EntitlementValue value = counted(100L, EnforcementMode.HARD, EnforcementMode.METER_ONLY, null);

            Boundary effective = BoundaryPolicy.decideCounted(value, 100, 1, value.effectiveMode());
            Boundary declared = BoundaryPolicy.decideCounted(value, 100, 1, value.declaredMode());

            assertThat(effective)
                    .as("a meter-only tenant is never refused, whatever its plan says")
                    .isEqualTo(Boundary.OVER_UNBILLED);
            assertThat(declared)
                    .as("and the answer enforcement would have given is recorded beside it, "
                            + "because that count is the only evidence for switching enforcement on")
                    .isEqualTo(Boundary.REFUSED);
        }

        @Test
        void hardRefusesBeforeAnythingMutates() {
            EntitlementValue value = counted(100L, EnforcementMode.HARD, EnforcementMode.HARD, null);

            assertThat(BoundaryPolicy.decideCounted(value, 100, 1, value.effectiveMode()))
                    .isEqualTo(Boundary.REFUSED);
        }

        @Test
        void softWithARateBillsIt() {
            EntitlementValue value = counted(100L, EnforcementMode.SOFT, EnforcementMode.SOFT, 4_000L);

            assertThat(BoundaryPolicy.decideCounted(value, 100, 1, value.effectiveMode()))
                    .isEqualTo(Boundary.OVER_BILLABLE);
        }

        @Test
        void softWithoutARateIsFreeAndSaysSo() {
            // A limit sold with no overage price is one the tenant exceeds for
            // free. Answering OVER_BILLABLE here would produce an invoice line
            // with no agreed price behind it.
            EntitlementValue value = counted(100L, EnforcementMode.SOFT, EnforcementMode.SOFT, null);

            assertThat(BoundaryPolicy.decideCounted(value, 100, 1, value.effectiveMode()))
                    .isEqualTo(Boundary.OVER_UNBILLED);
        }

        @Test
        void theHundredthIsStillWithin() {
            EntitlementValue value = counted(100L, EnforcementMode.HARD, EnforcementMode.HARD, null);

            assertThat(BoundaryPolicy.decideCounted(value, 99, 1, value.effectiveMode()))
                    .as("the limit is inclusive; a 100-order plan includes the hundredth order")
                    .isEqualTo(Boundary.WITHIN);
        }
    }

    @Test
    void anUnsetLimitIsUnlimitedRatherThanZero() {
        // getLong answering 0 for SQL NULL is the failure this guards. A null
        // limit read as zero refuses everything the moment enforcement is on.
        EntitlementValue value = counted(null, EnforcementMode.HARD, EnforcementMode.HARD, null);

        assertThat(BoundaryPolicy.decideCounted(value, 5_000, 1, value.effectiveMode()))
                .isEqualTo(Boundary.UNLIMITED);
    }

    @Test
    void aWarningFiresBeforeTheLimitAndNotAtIt() {
        EntitlementValue value = new EntitlementValue(
                ORDERS,
                100L,
                null,
                EnforcementMode.SOFT,
                EnforcementMode.SOFT,
                ResetPeriod.BILLING_PERIOD,
                8_000,
                null,
                null,
                EntitlementSource.PLAN_VERSION);

        assertThat(BoundaryPolicy.decideCounted(value, 79, 1, EnforcementMode.SOFT))
                .isEqualTo(Boundary.APPROACHING);
        assertThat(BoundaryPolicy.decideCounted(value, 78, 1, EnforcementMode.SOFT))
                .isEqualTo(Boundary.WITHIN);
    }

    @Test
    void overageIsPricedInWholeMinorUnits() {
        // The console prototype's Non uyi row: a 9 000 000 so'm Network plan
        // including 20 locations, 41 locations in use, 250 000 per extra one.
        // 9 000 000 + 21 x 250 000 = 14 250 000, which is the figure an account
        // manager reads to an owner on the phone.
        EntitlementValue value = new EntitlementValue(
                LOCATIONS,
                20L,
                null,
                EnforcementMode.SOFT,
                EnforcementMode.SOFT,
                ResetPeriod.NONE,
                null,
                250_000L,
                "UZS",
                EntitlementSource.PLAN_VERSION);

        LimitCheck check = new LimitCheck(
                LOCATIONS.code(),
                java.util.UUID.randomUUID(),
                20L,
                41,
                0,
                lifetime(),
                value,
                BoundaryPolicy.decideCounted(value, 41, 0, EnforcementMode.SOFT),
                BoundaryPolicy.decideCounted(value, 41, 0, EnforcementMode.SOFT),
                BoundaryPolicy.overageQuantity(value, 41, 0));

        assertThat(check.overageQuantity()).isEqualTo(21);
        // SOFT mode with a real overage price makes this billable, so the charge
        // is never null here; the assertion above already proves it, and this
        // makes that guarantee explicit rather than unboxing blind.
        long overageChargeMinor = Objects.requireNonNull(check.overageChargeMinor());
        assertThat(overageChargeMinor)
                .as("a minor unit of UZS is one whole som; dividing this by a hundred "
                        + "is the bug that shipped here once already")
                .isEqualTo(5_250_000L);
        assertThat(9_000_000L + overageChargeMinor).isEqualTo(14_250_000L);
    }

    @Test
    void overageIsNotChargedWhenTheCeilingSuppressedEnforcement() {
        EntitlementValue value = new EntitlementValue(
                LOCATIONS,
                20L,
                null,
                EnforcementMode.SOFT,
                EnforcementMode.METER_ONLY,
                ResetPeriod.NONE,
                null,
                250_000L,
                "UZS",
                EntitlementSource.PLAN_VERSION);

        LimitCheck check = new LimitCheck(
                LOCATIONS.code(),
                java.util.UUID.randomUUID(),
                20L,
                41,
                0,
                lifetime(),
                value,
                Boundary.OVER_UNBILLED,
                Boundary.OVER_BILLABLE,
                21);

        assertThat(check.overageChargeMinor())
                .as("a meter-only tenant is measured, not invoiced; billing overage the "
                        + "platform chose not to enforce is a charge nobody agreed to")
                .isNull();
        assertThat(check.suppressedByCeiling()).isTrue();
    }

    @Nested
    @DisplayName("the enforcement ceiling")
    class Ceiling {

        @Test
        void weakensAPlanAndNeverStrengthensIt() {
            assertThat(EnforcementMode.weakerOf(EnforcementMode.HARD, EnforcementMode.METER_ONLY))
                    .isEqualTo(EnforcementMode.METER_ONLY);
            assertThat(EnforcementMode.weakerOf(EnforcementMode.METER_ONLY, EnforcementMode.HARD))
                    .as("a configuration value that could refuse more than the commercial terms "
                            + "is an outage caused by a setting nobody connects to the symptom")
                    .isEqualTo(EnforcementMode.METER_ONLY);
        }

        @Test
        void theCatalogueDefaultCanNeverRefuse() {
            EntitlementKeys.all()
                    .forEach(key -> assertThat(key.defaultMode().canRefuse())
                            .as("%s must not be able to refuse a tenant that has no subscription", key.code())
                            .isFalse());
        }
    }

    @Nested
    @DisplayName("resolution precedence")
    class Precedence {

        @Test
        void anOverrideBeatsThePlanAndKeepsThePlansShape() {
            PlanEntitlement plan = PlanEntitlement.counted(
                    ORDERS.code(), 100, EnforcementMode.SOFT, ResetPeriod.BILLING_PERIOD, 8_000, 500L);
            EntitlementOverride override = new EntitlementOverride(
                    ORDERS.code(), 5_000L, null, null, NOW.minusSeconds(60), NOW.plusSeconds(3_600));

            EntitlementValue value = EntitlementResolution.resolve(
                    ORDERS, plan, override, SubscriptionStatus.ACTIVE, "UZS", EnforcementMode.HARD, NOW);

            assertThat(value.limit()).isEqualTo(5_000L);
            assertThat(value.source()).isEqualTo(EntitlementSource.TENANT_OVERRIDE);
            assertThat(value.overageUnitPriceMinor())
                    .as("a support exception raises the number; it does not quietly change what "
                            + "the tenant is charged past it")
                    .isEqualTo(500L);
            assertThat(value.resetPeriod()).isEqualTo(ResetPeriod.BILLING_PERIOD);
        }

        @Test
        void anExpiredOverrideFallsBackToThePlanOnTheInstantItEnds() {
            PlanEntitlement plan = PlanEntitlement.counted(
                    ORDERS.code(), 100, EnforcementMode.SOFT, ResetPeriod.BILLING_PERIOD, null, null);
            EntitlementOverride override =
                    new EntitlementOverride(ORDERS.code(), 5_000L, null, null, NOW.minusSeconds(3_600), NOW);

            EntitlementValue value = EntitlementResolution.resolve(
                    ORDERS, plan, override, SubscriptionStatus.ACTIVE, "UZS", EnforcementMode.HARD, NOW);

            assertThat(value.limit())
                    .as("validUntil is exclusive, so expiry is deterministic rather than "
                            + "dependent on whichever job noticed it first")
                    .isEqualTo(100L);
            assertThat(value.source()).isEqualTo(EntitlementSource.PLAN_VERSION);
        }

        @Test
        void noSubscriptionResolvesToACatalogueDefaultThatRefusesNothing() {
            EntitlementValue value =
                    EntitlementResolution.resolve(ORDERS, null, null, null, null, EnforcementMode.HARD, NOW);

            assertThat(value.source()).isEqualTo(EntitlementSource.CATALOGUE_DEFAULT);
            assertThat(value.limit()).isNull();
            assertThat(value.effectiveMode().canRefuse())
                    .as("a tenant mid-onboarding has no plan, and must not be a broken tenant")
                    .isFalse();
        }

        @Test
        void aSuspendedSubscriptionBlocksAdditionsAndRemovesNothing() {
            PlanEntitlement plan = PlanEntitlement.counted(
                    LOCATIONS.code(), 20, EnforcementMode.SOFT, ResetPeriod.NONE, null, 250_000L);

            EntitlementValue value = EntitlementResolution.resolve(
                    LOCATIONS, plan, null, SubscriptionStatus.SUSPENDED, "UZS", EnforcementMode.HARD, NOW);

            assertThat(value.limit()).isZero();
            assertThat(value.source()).isEqualTo(EntitlementSource.SUSPENSION_POLICY);
            assertThat(BoundaryPolicy.decideCounted(value, 41, 1, value.effectiveMode()))
                    .as("a 42nd branch is refused")
                    .isEqualTo(Boundary.REFUSED);
            assertThat(BoundaryPolicy.decideCounted(value, 41, 0, value.effectiveMode()))
                    .as("but the 41 that exist are still over a limit of zero and are never "
                            + "deleted; ADR 0021 refuses to destroy data over a commercial dispute")
                    .isEqualTo(Boundary.REFUSED);
        }

        @Test
        void aSuspendedTenantUnderTheDefaultCeilingIsStillNotRefused() {
            PlanEntitlement plan =
                    PlanEntitlement.counted(LOCATIONS.code(), 20, EnforcementMode.SOFT, ResetPeriod.NONE, null, null);

            EntitlementValue value = EntitlementResolution.resolve(
                    LOCATIONS, plan, null, SubscriptionStatus.SUSPENDED, "UZS", EnforcementMode.METER_ONLY, NOW);

            assertThat(value.declaredMode()).isEqualTo(EnforcementMode.HARD);
            assertThat(BoundaryPolicy.decideCounted(value, 41, 1, value.effectiveMode()))
                    .as("suspension is a decision to degrade; the ceiling still decides whether "
                            + "the platform acts on any decision at all")
                    .isEqualTo(Boundary.OVER_UNBILLED);
        }

        @Test
        void aFeatureOutsideThePlanIsDeniedOnlyWhenTheModeSaysDisabled() {
            EntitlementKey<Boolean> pos = EntitlementKeys.POS_INTEGRATIONS_ENABLED;
            PlanEntitlement plan = PlanEntitlement.feature(pos.code(), false, EnforcementMode.DISABLED);

            EntitlementValue disabled = EntitlementResolution.resolve(
                    pos, plan, null, SubscriptionStatus.ACTIVE, "UZS", EnforcementMode.DISABLED, NOW);
            EntitlementValue metered = EntitlementResolution.resolve(
                    pos, plan, null, SubscriptionStatus.ACTIVE, "UZS", EnforcementMode.METER_ONLY, NOW);

            assertThat(BoundaryPolicy.decideFeature(disabled, disabled.effectiveMode()))
                    .isEqualTo(Boundary.REFUSED);
            assertThat(BoundaryPolicy.decideFeature(metered, metered.effectiveMode()))
                    .as("used outside the plan, allowed, and counted — which is what a " + "meter-only rollout is for")
                    .isEqualTo(Boundary.OVER_UNBILLED);
        }
    }

    @Nested
    @DisplayName("periods")
    class Periods {

        @Test
        void aMonthRollsInTheTenantsTimezoneAndNotInUtc() {
            // 2026-08-31T20:00Z is already 01:00 on 1 September in Tashkent. A
            // period computed in UTC would put these orders in August's invoice
            // and the tenant's own order list would disagree with it.
            UsagePeriod period =
                    UsagePeriods.of(ResetPeriod.MONTHLY, Instant.parse("2026-08-31T20:00:00Z"), TASHKENT, null, null);

            assertThat(period.key()).isEqualTo("2026-09");
        }

        @Test
        void aStandingLimitHasOnePeriodThatNeverCloses() {
            UsagePeriod first =
                    UsagePeriods.of(ResetPeriod.NONE, Instant.parse("2024-01-01T00:00:00Z"), TASHKENT, null, null);
            UsagePeriod later =
                    UsagePeriods.of(ResetPeriod.NONE, Instant.parse("2029-06-30T00:00:00Z"), TASHKENT, null, null);

            assertThat(first).isEqualTo(later);
            assertThat(first.isLifetime()).isTrue();
        }

        @Test
        void aBillingPeriodIsTheSubscriptionsOwnWindowRatherThanTheCalendarMonth() {
            Instant start = Instant.parse("2026-08-14T00:00:00Z");
            Instant end = Instant.parse("2026-09-14T00:00:00Z");

            UsagePeriod period = UsagePeriods.of(
                    ResetPeriod.BILLING_PERIOD, Instant.parse("2026-09-01T10:00:00Z"), TASHKENT, start, end);

            assertThat(period.key())
                    .as("a tenant that started on the fourteenth does not get its allowance " + "back on the first")
                    .isEqualTo("2026-08-14");
            assertThat(period.contains(Instant.parse("2026-09-13T23:59:59Z"))).isTrue();
        }

        @Test
        void usageBeforeAnySubscriptionFallsBackToTheCalendarMonth() {
            UsagePeriod period = UsagePeriods.of(
                    ResetPeriod.BILLING_PERIOD, Instant.parse("2026-08-23T10:00:00Z"), TASHKENT, null, null);

            assertThat(period.key()).isEqualTo("2026-08");
        }
    }

    @Test
    void aSnapshotHashesTheEntitlementsAndNotTheInstantItWasRead() {
        EntitlementValue value = counted(100L, EnforcementMode.SOFT, EnforcementMode.SOFT, null);
        var tenant = java.util.UUID.randomUUID();

        var first = new uz.horecaos.platform.commercial.api.EntitlementSnapshot(
                tenant, null, java.util.Map.of(ORDERS.code(), value), NOW);
        var second = new uz.horecaos.platform.commercial.api.EntitlementSnapshot(
                tenant, null, java.util.Map.of(ORDERS.code(), value), NOW.plusSeconds(3_600));

        assertThat(first.hash())
                .as("two identical entitlement sets read an hour apart are the same entitlements")
                .isEqualTo(second.hash());
    }

    // ------------------------------------------------------------- fixtures

    private static EntitlementValue counted(
            @Nullable Long limit,
            EnforcementMode declared,
            EnforcementMode effective,
            @Nullable Long overagePrice) {
        return new EntitlementValue(
                ORDERS,
                limit,
                null,
                declared,
                effective,
                ResetPeriod.BILLING_PERIOD,
                null,
                overagePrice,
                overagePrice == null ? null : "UZS",
                EntitlementSource.PLAN_VERSION);
    }

    private static UsagePeriod lifetime() {
        return UsagePeriods.of(ResetPeriod.NONE, NOW, TASHKENT, null, null);
    }
}
