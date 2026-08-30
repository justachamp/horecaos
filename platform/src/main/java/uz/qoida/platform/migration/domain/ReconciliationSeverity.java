package uz.qoida.platform.migration.domain;

/**
 * How much a reconciliation difference matters (ADR 0024).
 *
 * <p>Severity belongs to the rule, not to the size of the number. A one-row
 * difference in authorized payment totals is critical and a large difference in a
 * derived analytics figure may be informational, which is why the rule declares
 * its own zero tolerance or approved tolerance rather than a threshold being
 * applied to every comparison alike.
 */
public enum ReconciliationSeverity {

    /**
     * Zero tolerance: money, ancestry, tenant boundaries, identity scope, and
     * external provider references.
     */
    CRITICAL,

    /** A difference that needs a documented explanation before cutover. */
    WARNING,

    /** Recorded for the trail. Expected, bounded, or derived. */
    INFO;

    /** Whether a difference at this severity blocks cutover until it is dealt with. */
    public boolean blocksCutover() {
        return this == CRITICAL;
    }
}
