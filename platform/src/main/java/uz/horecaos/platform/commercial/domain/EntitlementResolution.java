package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.commercial.api.EnforcementMode;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementSource;
import uz.horecaos.platform.commercial.api.EntitlementValue;
import uz.horecaos.platform.commercial.api.ResetPeriod;

/**
 * The single definition of entitlement precedence (ADR 0021).
 *
 * <p>A pure function of the key, the rows already fetched, and the clock, for
 * the reason {@code ScopeResolution} is one under ADR 0030: precedence consumed
 * by every enforcement point in the platform has to be exhaustively testable
 * without a database.
 *
 * <p>It is deliberately <em>not</em> ADR 0030's resolver. That resolver walks
 * one chain — platform, tenant, brand, location — and returns one scalar. This
 * one walks a different chain, override then plan version then catalogue, and
 * returns a value together with the behaviour at its boundary. The two look
 * alike from a distance and share no rule: an entitlement has no brand level, a
 * configuration value has no overage price, and merging them would give each the
 * other's failure modes. What is reused is the piece that genuinely is a
 * configuration value — the per-tenant enforcement ceiling, which arrives here
 * as {@code ceiling} having been resolved by ADR 0030 down the ordinary chain.
 */
public final class EntitlementResolution {

    private EntitlementResolution() {}

    /**
     * Resolves one key.
     *
     * @param key             the code-owned declaration
     * @param planEntitlement the row on the tenant's plan version, or null
     * @param override        the tenant's override for this key, or null
     * @param status          the live subscription's status, or null when there is none
     * @param planCurrency    the plan version's currency, needed to price overage
     * @param ceiling         the tenant's enforcement ceiling (ADR 0030)
     * @param at              the instant resolution is performed at
     */
    public static EntitlementValue resolve(
            EntitlementKey<?> key,
            @Nullable PlanEntitlement planEntitlement,
            @Nullable EntitlementOverride override,
            @Nullable SubscriptionStatus status,
            @Nullable String planCurrency,
            EnforcementMode ceiling,
            Instant at) {

        boolean planApplies = planEntitlement != null && status != null && status.grantsPlanEntitlements();
        PlanEntitlement plan = planApplies ? planEntitlement : null;
        EntitlementOverride live = override != null && override.isLiveAt(at) ? override : null;

        // Shape — the reset period, the warning threshold, the overage rate —
        // always comes from the plan when there is one. Only the number and,
        // where stated, the mode can be overridden.
        ResetPeriod resetPeriod = plan != null ? plan.resetPeriod() : key.resetPeriod();
        Integer warnThreshold = plan != null ? plan.warnThresholdBasisPoints() : null;
        Long overagePrice = plan != null ? plan.overageUnitPriceMinor() : null;
        String currency = overagePrice != null ? planCurrency : null;

        Long limit;
        Boolean feature;
        EnforcementMode declaredMode;
        EntitlementSource source;

        if (live != null) {
            limit = key.isCounted() ? live.integerValue() : null;
            feature = key.isFeature() ? live.booleanValue() : null;
            declaredMode =
                    firstNonNull(live.enforcementMode(), plan != null ? plan.enforcementMode() : key.defaultMode());
            source = EntitlementSource.TENANT_OVERRIDE;
        } else if (plan != null) {
            limit = key.isCounted() ? plan.integerValue() : null;
            feature = key.isFeature() ? plan.booleanValue() : null;
            declaredMode = plan.enforcementMode();
            source = EntitlementSource.PLAN_VERSION;
        } else {
            // No subscription, or a plan version that says nothing about this
            // key. Both resolve to the catalogue's safe default, which can never
            // refuse — an unsubscribed tenant is an unfinished sale, not a
            // tenant that should stop working.
            limit = key.isCounted() ? (Long) key.safeDefault() : null;
            feature = key.isFeature() ? (Boolean) key.safeDefault() : null;
            declaredMode = key.defaultMode();
            source = EntitlementSource.CATALOGUE_DEFAULT;
            overagePrice = null;
            currency = null;
            warnThreshold = null;
        }

        // Suspension is the one policy that may make an entitlement stricter
        // than the plan sold. It blocks additions and touches nothing that
        // already exists: ADR 0021 refuses to delete a customer's data over a
        // commercial dispute, and a limit of zero on a standing count means "no
        // more", never "remove what is there".
        //
        // Read-only Operations and continued export access are ADR 0025
        // capability decisions rather than entitlements, and are deliberately
        // not modelled here. A plan must never be able to grant or remove a
        // user's permission.
        if (status == SubscriptionStatus.SUSPENDED && key.isCounted() && live == null) {
            limit = 0L;
            declaredMode = EnforcementMode.HARD;
            source = EntitlementSource.SUSPENSION_POLICY;
            overagePrice = null;
            currency = null;
        }

        EnforcementMode effectiveMode = EnforcementMode.weakerOf(declaredMode, ceiling);

        return new EntitlementValue(
                key,
                limit,
                feature,
                declaredMode,
                effectiveMode,
                resetPeriod,
                warnThreshold,
                overagePrice,
                currency,
                source);
    }

    private static EnforcementMode firstNonNull(@Nullable EnforcementMode first, EnforcementMode second) {
        return first != null ? first : second;
    }
}
