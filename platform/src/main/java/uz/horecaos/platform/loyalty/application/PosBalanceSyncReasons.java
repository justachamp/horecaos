package uz.horecaos.platform.loyalty.application;

/**
 * The ledger reason codes only the till-balance sync writes (ADR 0171 Decision 5).
 *
 * <p>They never pass through the operator reason-code list of ADR 0165: an operator cannot choose them from a
 * menu, because "earned at the till" is a fact the sync observed and not a justification a person gives. A person
 * resolving a difference writes {@link #CORRECTION}, and the entry carries the approval that allowed it.
 */
public final class PosBalanceSyncReasons {

    /** Points the customer earned at the till, imported as a credit. */
    public static final String EARN = "POS_SYNC_EARN";

    /** Points the customer spent at the till, imported as a debit. */
    public static final String SPEND = "POS_SYNC_SPEND";

    /** A person accepted the till's number over the platform's, under approval where it credits above the threshold. */
    public static final String CORRECTION = "POS_SYNC_CORRECTION";

    /** Whether the code is one only the sync writes: the one test the sync's own adjustment path admits by. */
    public static boolean isSyncReason(String reasonCode) {
        return EARN.equals(reasonCode) || SPEND.equals(reasonCode) || CORRECTION.equals(reasonCode);
    }

    private PosBalanceSyncReasons() {}
}
