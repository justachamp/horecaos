package uz.horecaos.platform.ordering.domain;

/**
 * The canonical order statuses from {@code docs/domains/state-machines.md}.
 *
 * <p>Code-owned and closed. ADR 0036's omission list is explicit that a tenant
 * may not reorder or extend this: a tenant-defined status would be a state
 * nothing in the platform knows how to pay for, export, notify about, or report
 * on, and every consumer would need a fallback branch that silently does nothing.
 *
 * <p>Two things are deliberately absent. POS export status is not here — a POS
 * transport failure is an integration concern and cannot reverse
 * {@link #CONFIRMED}. Neither are in-flight commands such as "cancellation
 * requested": those are process-manager states, and promoting them to order
 * statuses would mean two authorities writing one column.
 */
public enum OrderStatus {

    /** Created and durable. Nothing external has happened yet. */
    RECEIVED(false),

    /** An online payment is being authorized (ADR 0013). */
    PAYMENT_AUTHORIZING(false),

    /** Waiting for the restaurant to accept or reject (ADR 0002). */
    AWAITING_APPROVAL(false),

    /** Authorization failed. Terminal: a new attempt is a new order. */
    PAYMENT_FAILED(true),

    /** The commercial commitment. From here the kitchen owns progress. */
    CONFIRMED(false),

    /** The restaurant declined. Terminal. */
    REJECTED(true),

    /** Nobody decided in time and the policy's timeout action was to reject. Terminal. */
    EXPIRED(true),

    PREPARING(false),

    READY(false),

    /** Out for delivery. Pickup orders never enter this state. */
    FULFILLING(false),

    COMPLETED(true),

    CANCELLED(true);

    private final boolean terminal;

    OrderStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** No transition leaves a terminal status. A correction is a new order. */
    public boolean terminal() {
        return terminal;
    }

    /**
     * Whether the order still occupies a kitchen slot for the ADR 0036
     * concurrent-order ceiling.
     *
     * <p>{@link #READY} still counts: the food is on the pass and the branch has
     * not finished with it. {@link #FULFILLING} does not, because a courier
     * holding the bag is not a kitchen constraint.
     */
    public boolean occupiesCapacity() {
        return this == RECEIVED
                || this == PAYMENT_AUTHORIZING
                || this == AWAITING_APPROVAL
                || this == CONFIRMED
                || this == PREPARING
                || this == READY;
    }

    /** Whether inventory reserved for this order should be given back. */
    public boolean releasesInventory() {
        return this == REJECTED || this == EXPIRED || this == CANCELLED || this == PAYMENT_FAILED;
    }
}
