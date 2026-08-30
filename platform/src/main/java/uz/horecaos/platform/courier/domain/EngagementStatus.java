package uz.horecaos.platform.courier.domain;

/**
 * The lifecycle of one courier's engagement with one tenant (ADR 0042).
 *
 * <p>There is no path from onboarding straight to dispatchable: a courier
 * reaches {@link #ACTIVE} only through a recorded verification, because an
 * unverified registration is exactly the fact that turns a compliant
 * arrangement into an undeclared one without producing an error anywhere.
 */
public enum EngagementStatus {

    PENDING_VERIFICATION,
    ACTIVE,

    /** The registration lapsed. New offers stop; accepted work finishes. */
    SUSPENDED_COMPLIANCE,

    /** A manager suspended the engagement for an operational reason. */
    SUSPENDED_OPERATIONAL,

    ENDED;

    /** Whether dispatch may make this courier a new offer. */
    public boolean dispatchable() {
        return this == ACTIVE;
    }
}
