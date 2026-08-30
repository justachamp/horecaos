package uz.qoida.platform.fulfillment.domain.sourcing;

/**
 * What became of one thing sourcing asked of somebody (ADR 0014).
 *
 * <p>{@link #FAILED} and {@link #DECLINED} are kept apart because they are
 * answers from two different parties: a partner refusing a booking is a business
 * rejection to walk past, and a courier declining an offer is a person saying no.
 * An operator reading a plan's history needs to tell them apart, and a single
 * "rejected" would hide which of the two happened.
 *
 * <p>{@link #UNCERTAIN} is the one that stops everything. ADR 0014 forbids
 * booking a fallback while the first provider may have accepted.
 */
public enum AttemptStatus {

    REQUESTED,
    OFFERED,
    ACCEPTED,
    DECLINED,
    EXPIRED,
    FAILED,
    CANCELLED,
    UNCERTAIN;

    /** Whether this attempt can still turn into a courier without another call. */
    public boolean live() {
        return this == REQUESTED || this == OFFERED;
    }
}
