package uz.horecaos.platform.fulfillment.domain.sourcing;

/**
 * How a plan is sourced (ADR 0014 "Sourcing modes").
 *
 * <p>The owner's decision on 2026-08-23 was "both, partners as the fallback",
 * which is {@link #FLEET_FIRST} and is the default. The other three remain
 * because a tenant with no fleet, a tenant with no partner contract, and a
 * branch that dispatches by hand are all real and none of them is a degenerate
 * case of the others.
 */
public enum SourcingMode {

    /**
     * The in-house fleet first, an external partner when nobody takes it.
     *
     * <p>Commission is paid only when the fleet could not or would not take the
     * order, and the fleet is not asked to hold an order it has no capacity for.
     * {@link SourcingPlanner} owns how long "when nobody takes it" is allowed to
     * last.
     */
    FLEET_FIRST,

    /** The fleet only. No partner is called, and an unfilled plan escalates. */
    FLEET_ONLY,

    /**
     * Partners only. For a tenant with no couriers of its own. The fleet is never
     * asked, so a plan whose partners are exhausted escalates rather than falling
     * back to a courier -- {@link #PARTNER_FIRST} is the mode that does.
     */
    PARTNER_ONLY,

    /**
     * A named partner first, the in-house fleet second (ADR 0142, Decision 9).
     *
     * <p>The flagship case the dispatch-rules row asks for -- "Yandex for the far
     * zone, our own couriers if Yandex refuses" -- cannot be said with the other
     * four modes: {@link #FLEET_FIRST} asks the fleet first, and {@link
     * #PARTNER_ONLY} never reaches it. The partner lane runs exactly as ADR 0014
     * has it (a ladder or a quote race, a single winner, nothing cancelled); the
     * fleet is offered the plan only when that lane has ended with a definite
     * answer -- every eligible partner refused, or none is bound. An uncertain
     * partner attempt still escalates before either lane runs, so an unreconciled
     * booking never falls through to a courier.
     */
    PARTNER_FIRST,

    /**
     * Operations selects and records the assignment. Sourcing produces the plan
     * and the window and then stops, which is ADR 0014's rollback position:
     * automated sourcing off, plans and evidence preserved.
     */
    MANUAL;

    public boolean usesFleet() {
        return this == FLEET_FIRST || this == FLEET_ONLY || this == PARTNER_FIRST;
    }

    public boolean usesPartners() {
        return this == FLEET_FIRST || this == PARTNER_ONLY || this == PARTNER_FIRST;
    }

    /**
     * Whether a partner lane runs after the fleet lane, and so whether the fleet
     * must hand over early enough for the partner to make the window.
     *
     * <p>The test is "does a partner lane <em>follow</em> the fleet lane", not "does
     * the mode use partners": {@link #PARTNER_FIRST} uses both, but its fleet lane
     * has no partner behind it to protect, so its deadline is {@code
     * latest_assignment_at} as in {@link #FLEET_ONLY}. Keyed on {@link
     * #usesPartners()} instead, a partner-first fleet lane would open already past
     * its deadline and refuse with {@code FLEET_BUDGET_SPENT}.
     */
    public boolean partnerLaneFollowsFleet() {
        return this == FLEET_FIRST;
    }

    /** Whether the partner lane is asked before the fleet lane. */
    public boolean partnerLaneFirst() {
        return this == PARTNER_FIRST;
    }
}
