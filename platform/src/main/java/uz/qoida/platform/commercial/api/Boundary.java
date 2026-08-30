package uz.qoida.platform.commercial.api;

/**
 * The answer at the edge of an entitlement (ADR 0021).
 *
 * <p>Six answers rather than a boolean, because "what happens at the 101st order
 * on a 100-order plan" has six honest answers and a boolean has one. A caller
 * that only wants to know whether to proceed reads {@link #allowed()}; a caller
 * building an invoice line, an alert, or an upgrade prompt reads the constant.
 */
public enum Boundary {

    /** No limit is set for this key. Nothing to approach and nothing to bill. */
    UNLIMITED,

    /** Under the limit, and under the warning threshold if one is set. */
    WITHIN,

    /**
     * Under the limit but at or past the warning threshold. Allowed, and worth
     * telling somebody about while an upgrade is still a conversation rather
     * than an incident.
     */
    APPROACHING,

    /**
     * Past the limit, allowed, and billable: the plan carries an overage unit
     * price and the quantity beyond the limit is what gets charged.
     */
    OVER_BILLABLE,

    /**
     * Past the limit and allowed, but with no rate to charge it at. Either the
     * plan sells no overage or enforcement is capped below what it declared.
     * Recorded, alertable, and free — which is a deliberate state and not an
     * error, because it is what every tenant is in during a meter-only rollout.
     */
    OVER_UNBILLED,

    /**
     * Refused before anything mutated. Carries the current usage, the limit, and
     * the upgrade path, because a refusal a tenant cannot act on is an outage
     * with extra steps.
     */
    REFUSED;

    public boolean allowed() {
        return this != REFUSED;
    }

    /** Whether this answer is worth raising with the tenant or with Operations. */
    public boolean noteworthy() {
        return this == APPROACHING || this == OVER_BILLABLE
                || this == OVER_UNBILLED || this == REFUSED;
    }
}
