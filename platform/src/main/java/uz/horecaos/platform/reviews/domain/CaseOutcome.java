package uz.horecaos.platform.reviews.domain;

/**
 * Why a case ended, from a closed list (ADR 0159, Decision 8).
 *
 * <p>Four outcomes resolve a case and four close it unresolved. {@link #REMEDY_GRANTED} is the only
 * one that needs a fact behind it: at least one attached compensation. The others are the operator's
 * word, and the list is deliberately short enough that nobody needs a free-text "other".
 */
public enum CaseOutcome {
    REMEDY_GRANTED(CaseState.RESOLVED),
    OFFLINE_GESTURE(CaseState.RESOLVED),
    EXPLAINED_NO_REMEDY(CaseState.RESOLVED),
    NO_REMEDY_NEEDED(CaseState.RESOLVED),
    CUSTOMER_UNREACHABLE(CaseState.CLOSED_UNRESOLVED),
    CUSTOMER_DECLINED(CaseState.CLOSED_UNRESOLVED),
    COMPLAINT_NOT_UPHELD(CaseState.CLOSED_UNRESOLVED),
    NOT_ACTIONABLE(CaseState.CLOSED_UNRESOLVED);

    private final CaseState endsIn;

    CaseOutcome(CaseState endsIn) {
        this.endsIn = endsIn;
    }

    /** The terminal state this outcome belongs to. */
    public CaseState endsIn() {
        return endsIn;
    }

    public boolean needsCompensation() {
        return this == REMEDY_GRANTED;
    }
}
