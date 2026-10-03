package uz.horecaos.platform.fulfillment.domain.sourcing;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Start;

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

    /**
     * Bumped when any formula below changes, so a recalculation is visible as one.
     *
     * <p>2: ADR 0142. {@code source_at} is measured from a named basis plus a bounded
     * offset (a dispatch rule's {@code dispatchAt}). The default basis and offset
     * reproduce version 1's number exactly, but a plan now says which formula made it.
     */
    public static final int CALCULATION_VERSION = 2;

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
        return forOrder(confirmedAt, preparation, branchZone, policy, Start.lead());
    }

    /**
     * {@link #forOrder(Instant, Duration, ZoneId, DeliverySourcingPolicy)} with the dispatch start a
     * rule chose (ADR 0142 Decision 8): a named basis plus a bounded offset.
     *
     * <ul>
     *   <li>{@code LEAD}: the estimated ready time less the in-house lead and the safety buffer
     *       (version 1's only formula, and the default);
     *   <li>{@code CONFIRMATION}: the moment the order was confirmed;
     *   <li>{@code READY}: the estimated ready time.
     * </ul>
     *
     * The offset is added to the basis, and the result is never before the confirmation.
     */
    public static PickupPlan forOrder(
            Instant confirmedAt, Duration preparation, ZoneId branchZone, DeliverySourcingPolicy policy, Start start) {

        Instant readyAt = confirmedAt.plus(preparation);
        Instant windowEnd = readyAt.plusSeconds(policy.pickupToleranceSeconds());
        Instant sourceAt = startInstant(confirmedAt, readyAt, policy, start);

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

    /** The unfloored instant a start resolves to; exposed for the lint that asks whether it leaves time for a partner. */
    static Instant startInstant(Instant confirmedAt, Instant readyAt, DeliverySourcingPolicy policy, Start start) {
        Instant basis =
                switch (start.basis()) {
                    case LEAD ->
                        readyAt.minusSeconds(policy.preparationLeadSeconds())
                                .minusSeconds(policy.safetyBufferSeconds());
                    case CONFIRMATION -> confirmedAt;
                    case READY -> readyAt;
                };
        return basis.plusSeconds(start.offsetSeconds());
    }

    /**
     * Where a start lands relative to the estimated ready time, in seconds (negative is before),
     * for an order with this much preparation and <em>before</em> the floor at the confirmation
     * instant.
     *
     * <p>Publish-time lint asks whether a rule's basis and offset leave a partner enough time,
     * and the answer must follow the same arithmetic {@link #forOrder} uses. The floor is left
     * out on purpose: it is what a very short order does to <em>any</em> start, the default
     * included, and refusing a rule for it would refuse the built-in default too.
     */
    public static long secondsFromReady(Duration preparation, DeliverySourcingPolicy policy, Start start) {
        Instant confirmedAt = Instant.EPOCH;
        Instant readyAt = confirmedAt.plus(preparation);
        return Duration.between(readyAt, startInstant(confirmedAt, readyAt, policy, start))
                .toSeconds();
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

    /** {@link #withPreparation(Duration, DeliverySourcingPolicy)} under the dispatch start the plan was created with. */
    public PickupPlan withPreparation(Duration revised, DeliverySourcingPolicy policy, Start start) {
        return forOrder(confirmedAt, revised, branchZone, policy, start);
    }

    /** Whether sourcing should have started by this instant. */
    public boolean isDue(Instant now) {
        return !now.isBefore(sourceAt);
    }
}
