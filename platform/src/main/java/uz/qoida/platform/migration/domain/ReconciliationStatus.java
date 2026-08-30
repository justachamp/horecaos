package uz.qoida.platform.migration.domain;

/**
 * What has been done about a reconciliation difference (ADR 0024).
 *
 * <p>{@link #APPROVED} and {@link #RESOLVED} are different outcomes and the
 * distinction is the point: a resolved difference is gone, and an approved one is
 * still there and a named person accepted it against a named rule version. A
 * single "closed" value would let the second quietly present itself as the first
 * in every summary anyone reads afterwards.
 */
public enum ReconciliationStatus {

    /** Recorded and unanswered. An open result is not evidence of anything. */
    OPEN,

    /** The difference remains and was accepted within the rule's approved tolerance. */
    APPROVED,

    /** The difference is gone, and a later run proved it. */
    RESOLVED;

    /** Whether this difference still owes someone a decision. */
    public boolean outstanding() {
        return this == OPEN;
    }
}
