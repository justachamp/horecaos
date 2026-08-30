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

    /** Partners only. For a tenant with no couriers of its own. */
    PARTNER_ONLY,

    /**
     * Operations selects and records the assignment. Sourcing produces the plan
     * and the window and then stops, which is ADR 0014's rollback position:
     * automated sourcing off, plans and evidence preserved.
     */
    MANUAL;

    public boolean usesFleet() {
        return this == FLEET_FIRST || this == FLEET_ONLY;
    }

    public boolean usesPartners() {
        return this == FLEET_FIRST || this == PARTNER_ONLY;
    }
}
