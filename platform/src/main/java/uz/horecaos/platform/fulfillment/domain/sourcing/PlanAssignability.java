package uz.horecaos.platform.fulfillment.domain.sourcing;

/**
 * Whether a person may put a courier on a plan, by the plan's status alone
 * (ADR 0172, "Ports").
 *
 * <p>One definition for the two paths that assign by hand, the dispatch board's
 * single assignment and the orders board's bulk one. Before it existed the
 * single path checked no plan status of its own: a {@code CANCELLED} or
 * {@code COMPLETED} plan was protected only by the attempt and winner
 * compare-and-set, and a plan with no shipment could be handed a courier and
 * dragged back out of {@code CANCELLED} by the conditional status move that
 * followed.
 *
 * <p>The status is only half the answer. A plan can be {@code WAITING_TO_SOURCE}
 * with a live automated attempt on it, or {@code ASSIGNED} while its shipment was
 * cancelled; the attempt and shipment rows say that, and the callers read them
 * too.
 */
public enum PlanAssignability {

    /** Nobody has the plan and automated sourcing is not holding it: a person may assign. */
    OPEN,

    /** A courier or a partner already carries it ({@code ASSIGNED}, {@code IN_PROGRESS}). */
    CARRIED,

    /** Automated sourcing is mid-decision ({@code SOURCING}, {@code BOOKING}). */
    BEING_SOURCED,

    /** The plan is over ({@code COMPLETED}, {@code CANCELLED}). */
    NOT_OPEN;

    public static PlanAssignability of(PlanStatus status) {
        return switch (status) {
            case PLANNED, WAITING_TO_SOURCE, SCHEDULED, RETRY_PENDING, MANUAL_ACTION_REQUIRED -> OPEN;
            case ASSIGNED, IN_PROGRESS -> CARRIED;
            case SOURCING, BOOKING -> BEING_SOURCED;
            case COMPLETED, CANCELLED -> NOT_OPEN;
        };
    }
}
