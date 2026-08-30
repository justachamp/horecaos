package uz.horecaos.platform.fulfillment.domain.sourcing;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * ADR 0014's time model, as a value (ADR 0014 "Time model").
 *
 * <p>The ADR writes it as seven column names and two formulas. This is those two
 * formulas, computed once from a confirmation instant and a preparation
 * estimate, so that every consumer reads the same window rather than
 * recalculating it from a clock it happened to have.
 *
 * <p>Instants are UTC and the branch timezone is carried beside them, exactly as
 * the ADR requires. Uzbekistan is UTC+5 with no DST, so a wall-clock rendering
 * never shifts under a plan; the zone is held anyway because a branch whose
 * hours wrap past midnight is rendered against it, and a plan that cannot say
 * which day it belongs to is one an operator cannot find.
 *
 * @param sourceAt           when sourcing should start for the lane named by
 *                           {@code leadSeconds}. Earlier than the pickup window
 *                           by a lead time and a safety buffer, which is ADR
 *                           0014's stated formula
 * @param latestAssignmentAt past this instant no assignment can still meet the
 *                           promise, and the plan becomes an operations
 *                           exception rather than a sourcing retry
 */
public record PickupPlan(
        Instant confirmedAt,
        Duration preparation,
        Instant estimatedReadyAt,
        Instant pickupWindowStart,
        Instant pickupWindowEnd,
        Instant sourceAt,
        Instant latestAssignmentAt,
        ZoneId branchZone,
        int calculationVersion) {

    /** Bumped when any formula below changes, so a recalculation is visible as one. */
    public static final int CALCULATION_VERSION = 1;

    public PickupPlan {
        Objects.requireNonNull(confirmedAt, "A confirmation instant is required");
        Objects.requireNonNull(preparation, "A preparation estimate is required");
        Objects.requireNonNull(branchZone, "A branch timezone is required");
        if (preparation.isNegative()) {
            throw new IllegalArgumentException("A preparation estimate cannot be negative");
        }
        if (pickupWindowEnd.isBefore(pickupWindowStart)) {
            throw new IllegalArgumentException("A pickup window cannot end before it starts");
        }
    }

    /**
     * The plan for an order confirmed now with this preparation estimate.
     *
     * <p>{@code sourceAt} is computed against the <em>in-house</em> lead time.
     * The partner lane is slower and its own deadline is derived backwards from
     * the end of the pickup window by {@link SourcingPlanner}, rather than by
     * starting the whole plan early enough for the slowest lane — which would
     * source every order at the pace of the partner we hope not to use.
     */
    public static PickupPlan forOrder(
            Instant confirmedAt, Duration preparation, ZoneId branchZone, DeliverySourcingPolicy policy) {

        Instant readyAt = confirmedAt.plus(preparation);
        Instant windowEnd = readyAt.plusSeconds(policy.pickupToleranceSeconds());
        Instant sourceAt =
                readyAt.minusSeconds(policy.preparationLeadSeconds()).minusSeconds(policy.safetyBufferSeconds());

        return new PickupPlan(
                confirmedAt,
                preparation,
                readyAt,
                readyAt,
                windowEnd,
                // Never before the order was confirmed. A twenty-minute order at
                // a branch configured with a twenty-five-minute partner lead
                // would otherwise carry a source_at in the past, which reads as
                // an overdue sourcing job for every such order rather than as
                // "start now".
                sourceAt.isBefore(confirmedAt) ? confirmedAt : sourceAt,
                windowEnd.plusSeconds(policy.latestAssignmentSlackSeconds()),
                branchZone,
                CALCULATION_VERSION);
    }

    /**
     * The plan after the kitchen revised its estimate.
     *
     * <p>Recalculated from the original confirmation instant rather than from
     * now, so that a revision arriving late does not push the promise out by the
     * time it took to arrive. Neither verified partner supports reschedule, so
     * what this produces is compared against an existing booking by the caller
     * and turned into cancel-and-re-source when it has moved too far — it never
     * silently restates a window a courier was already given.
     */
    public PickupPlan withPreparation(Duration revised, DeliverySourcingPolicy policy) {
        return forOrder(confirmedAt, revised, branchZone, policy);
    }

    /** Whether sourcing should have started by this instant. */
    public boolean isDue(Instant now) {
        return !now.isBefore(sourceAt);
    }
}
