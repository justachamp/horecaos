package uz.horecaos.platform.commercial.api;

/**
 * What the platform does when a tenant reaches the edge of an entitlement
 * (ADR 0021).
 *
 * <p>The four modes form a ladder from measuring to refusing, and the ordering
 * is what makes a staged rollout expressible: an operator raises a ceiling from
 * {@link #METER_ONLY} to {@link #SOFT} for one tenant, reads the overage it
 * records, and only then considers {@link #HARD}. A set of unordered modes
 * would leave "turn enforcement down a notch" with no meaning.
 */
public enum EnforcementMode {

    /**
     * Measure and change nothing. The default everywhere until somebody decides
     * otherwise, and the mode a rollback returns to.
     */
    METER_ONLY(0),

    /**
     * Allow the action, record the overage, and let the check answer that the
     * limit was passed. What gets billed depends on whether the plan carries an
     * overage unit price; what gets alerted does not.
     */
    SOFT(1),

    /**
     * Refuse the capacity-increasing action before it mutates anything, naming
     * the current usage, the limit, and the upgrade path.
     *
     * <p>Never applied to an action already in flight, and never used to remove
     * something that already exists: a lowered limit blocks additions, it does
     * not delete a tenant's data.
     */
    HARD(2),

    /**
     * The feature is not part of the plan at all. Activation is denied; existing
     * data is preserved and stays readable and exportable.
     */
    DISABLED(3);

    private final int strength;

    EnforcementMode(int strength) {
        this.strength = strength;
    }

    public int strength() {
        return strength;
    }

    /**
     * The weaker of two modes, which is how the per-tenant enforcement ceiling
     * is applied.
     *
     * <p>Deliberately weaker rather than stronger. A ceiling that could
     * strengthen a plan's declared mode would let a configuration value start
     * refusing actions the commercial terms permit, which is an outage caused by
     * a setting nobody connected to the symptom.
     */
    public static EnforcementMode weakerOf(EnforcementMode first, EnforcementMode second) {
        return first.strength <= second.strength ? first : second;
    }

    /** Whether this mode can produce a refusal at all. */
    public boolean canRefuse() {
        return this == HARD || this == DISABLED;
    }
}
