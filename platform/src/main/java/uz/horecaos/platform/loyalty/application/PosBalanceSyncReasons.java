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

    /**
     * The codes a person's resolution of a till discrepancy may write: the held credit they accept, and the
     * difference they accept. {@link #SPEND} is the sync's alone and no person resolves into it.
     */
    public static boolean isWrittenByAResolution(String reasonCode) {
        return EARN.equals(reasonCode) || CORRECTION.equals(reasonCode);
    }

    private PosBalanceSyncReasons() {}
}
