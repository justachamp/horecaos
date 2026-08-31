package uz.horecaos.platform.commercial.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.commercial.api.EnforcementMode;
import uz.horecaos.platform.commercial.api.ResetPeriod;

/**
 * One stored entitlement on one plan version, as the resolver sees it
 * (ADR 0021).
 *
 * <p>Exactly one of {@code integerValue} and {@code booleanValue} is populated,
 * which the table's payload constraint already guarantees; this record repeats
 * the rule so a value assembled in memory cannot break it either.
 */
public record PlanEntitlement(
        String entitlementKey,
        @Nullable Long integerValue,
        @Nullable Boolean booleanValue,
        EnforcementMode enforcementMode,
        ResetPeriod resetPeriod,
        @Nullable Integer warnThresholdBasisPoints,
        @Nullable Long overageUnitPriceMinor) {

    public PlanEntitlement {
        Objects.requireNonNull(entitlementKey, "An entitlement key is required");
        Objects.requireNonNull(enforcementMode, "An enforcement mode is required");
        Objects.requireNonNull(resetPeriod, "A reset period is required");

        if ((integerValue == null) == (booleanValue == null)) {
            throw new IllegalArgumentException("A plan entitlement carries exactly one typed value: " + entitlementKey);
        }
        if (overageUnitPriceMinor != null && enforcementMode.canRefuse()) {
            throw new IllegalArgumentException("A refusing mode never bills overage: " + entitlementKey);
        }
    }

    public static PlanEntitlement counted(
            String key,
            long limit,
            EnforcementMode mode,
            ResetPeriod resetPeriod,
            @Nullable Integer warnThresholdBasisPoints,
            @Nullable Long overageUnitPriceMinor) {
        return new PlanEntitlement(
                key, limit, null, mode, resetPeriod, warnThresholdBasisPoints, overageUnitPriceMinor);
    }

    public static PlanEntitlement feature(String key, boolean granted, EnforcementMode mode) {
        return new PlanEntitlement(key, null, granted, mode, ResetPeriod.NONE, null, null);
    }
}
