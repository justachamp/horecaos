package uz.horecaos.platform.commercial.domain;

import uz.horecaos.platform.commercial.api.Boundary;
import uz.horecaos.platform.commercial.api.EnforcementMode;
import uz.horecaos.platform.commercial.api.EntitlementValue;

/**
 * What happens at the edge of a limit (ADR 0021).
 *
 * <p>The answer to "what happens at the 101st order on a 100-order plan". The
 * function is pure and takes the mode as an argument rather than reading it off
 * the value, which is what lets a caller ask it twice — once with the effective
 * mode for what to do, and once with the declared mode for what the commercial
 * terms would have done. That second answer is the entire evidence base for
 * turning enforcement on, and it does not exist if the mode is baked in.
 */
public final class BoundaryPolicy {

    private static final long BASIS_POINTS = 10_000L;

    private BoundaryPolicy() {}

    /**
     * Decides the boundary for a counted entitlement.
     *
     * @param value     the resolved entitlement
     * @param consumed  what the ledger says was used this period
     * @param requested the increase about to be made, zero to ask where the tenant stands
     * @param mode      the mode to answer under
     */
    public static Boundary decideCounted(EntitlementValue value, long consumed, long requested, EnforcementMode mode) {

        Long limit = value.limit();
        if (limit == null) {
            return Boundary.UNLIMITED;
        }

        long projected = Math.addExact(consumed, requested);
        if (projected <= limit) {
            return approaching(value, projected, limit) ? Boundary.APPROACHING : Boundary.WITHIN;
        }
        if (mode.canRefuse()) {
            return Boundary.REFUSED;
        }
        // Past the limit and allowed. Billable needs two things: a rate the
        // tenant agreed to, and a mode that permits charging. A limit sold
        // without an overage rate is one the tenant exceeds for free, and a
        // meter-only tenant is measured rather than invoiced — METER_ONLY is
        // defined as measuring without changing behaviour, and a charge is the
        // largest behaviour change available.
        return value.overageUnitPriceMinor() != null && mode == EnforcementMode.SOFT
                ? Boundary.OVER_BILLABLE
                : Boundary.OVER_UNBILLED;
    }

    /**
     * Decides the boundary for a feature entitlement.
     *
     * <p>A feature the plan does not include is {@link Boundary#OVER_UNBILLED}
     * rather than allowed, whenever enforcement is not set to refuse. That is
     * the honest reading of a meter-only rollout: the tenant is using something
     * outside its plan, the platform is letting it, and somebody should be able
     * to count how often that happens before deciding to stop it.
     */
    public static Boundary decideFeature(EntitlementValue value, EnforcementMode mode) {
        if (Boolean.TRUE.equals(value.featureEnabled())) {
            return Boundary.WITHIN;
        }
        return mode == EnforcementMode.DISABLED ? Boundary.REFUSED : Boundary.OVER_UNBILLED;
    }

    /** Units beyond the limit after the request, never negative. */
    public static long overageQuantity(EntitlementValue value, long consumed, long requested) {
        Long limit = value.limit();
        if (limit == null) {
            return 0;
        }
        return Math.max(0, Math.addExact(consumed, requested) - limit);
    }

    /**
     * Whether a projected consumption has reached the warning threshold.
     *
     * <p>Compared in basis points with integer arithmetic. Eighty per cent of a
     * limit computed in floating point disagrees with itself between two
     * machines, and the disagreement surfaces as an alert that fires for one
     * tenant and not another on the same number.
     */
    private static boolean approaching(EntitlementValue value, long projected, long limit) {
        Integer threshold = value.warnThresholdBasisPoints();
        if (threshold == null || limit <= 0) {
            return false;
        }
        return Math.multiplyExact(projected, BASIS_POINTS) >= Math.multiplyExact(limit, (long) threshold);
    }
}
