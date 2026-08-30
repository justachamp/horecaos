package uz.qoida.platform.commercial.api;

import java.util.Objects;

/**
 * One resolved entitlement: a value, the behaviour at its boundary, and where
 * both came from (ADR 0021).
 *
 * <p>This is the record that makes an entitlement not a feature flag. A flag
 * would be {@code limit} alone. The four fields beside it — the mode, the reset
 * period, the warning threshold and the overage price — are what let a caller
 * be told <em>refused</em>, <em>allowed and warned</em>, or <em>allowed and
 * billed at this rate</em> rather than merely <em>no</em>.
 *
 * @param key            the code-owned declaration this value answers
 * @param limit          the counted limit, or null for unlimited
 * @param featureEnabled whether the feature is granted, for boolean keys
 * @param declaredMode   the mode the commercial terms declare
 * @param effectiveMode  the mode after the tenant's enforcement ceiling
 * @param resetPeriod    the window the limit is measured over
 * @param warnThresholdBasisPoints  basis points of the limit at which a check warns, or null
 * @param overageUnitPriceMinor     minor units per unit over the limit, or null when overage is not billable
 * @param currency       the currency of the overage price, or null when there is none
 * @param source         where the value was resolved from
 */
public record EntitlementValue(
        EntitlementKey<?> key,
        Long limit,
        Boolean featureEnabled,
        EnforcementMode declaredMode,
        EnforcementMode effectiveMode,
        ResetPeriod resetPeriod,
        Integer warnThresholdBasisPoints,
        Long overageUnitPriceMinor,
        String currency,
        EntitlementSource source) {

    public EntitlementValue {
        Objects.requireNonNull(key, "An entitlement key is required");
        Objects.requireNonNull(declaredMode, "A declared mode is required");
        Objects.requireNonNull(effectiveMode, "An effective mode is required");
        Objects.requireNonNull(resetPeriod, "A reset period is required");
        Objects.requireNonNull(source, "A source is required");

        if (limit != null && limit < 0) {
            throw new IllegalArgumentException("A limit cannot be negative: " + key.code());
        }
        // An overage rate is a currency amount, and a currency amount without a
        // currency is the bug this codebase has already shipped once.
        if ((overageUnitPriceMinor == null) != (currency == null)) {
            throw new IllegalArgumentException(
                    "An overage price and its currency travel together: " + key.code());
        }
    }

    public boolean unlimited() {
        return key.isCounted() && limit == null;
    }

    /**
     * Whether passing this limit produces a billable line rather than only an
     * alert.
     *
     * <p>Requires {@link EnforcementMode#SOFT} specifically, not merely "a mode
     * that does not refuse". {@link EnforcementMode#METER_ONLY} is defined as
     * measuring without changing behaviour, and charging a tenant is a change of
     * behaviour — the largest one this module is capable of. A meter-only
     * rollout that quietly accrued overage would be a rollout nobody could
     * safely run, which is the opposite of what it is for.
     */
    public boolean billableOverage() {
        return overageUnitPriceMinor != null && effectiveMode == EnforcementMode.SOFT;
    }
}
