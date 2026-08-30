package uz.qoida.platform.ordering.domain;

/**
 * Where an amendment is in its own short life (ADR 0039).
 *
 * <pre>
 * DRAFT -&gt; PRICED
 * PRICED -&gt; AWAITING_CUSTOMER_CONFIRMATION -&gt; AWAITING_PAYMENT -&gt; APPLIED
 * PRICED -&gt; APPLIED                     (no increase, no payment required)
 * any non-terminal -&gt; REJECTED | EXPIRED
 * </pre>
 *
 * <p>Deliberately not part of {@link OrderStatus}. An in-flight command is a
 * process state; promoting it to an order status would mean two authorities
 * writing one column and would put "somebody is editing this" in front of every
 * consumer of the commercial state machine.
 *
 * <p>Only the {@code PRICED -> APPLIED} edge is exercised today, because the
 * three built commands change no money. The rest are declared so the states an
 * expired or rejected amendment lands in exist before the command that produces
 * them, rather than being invented under a financial command's deadline.
 */
public enum AmendmentStatus {

    /** Commands are being collected. Nothing has been priced. */
    DRAFT(false),

    /** A total exists and, where it differs, a delta the operator can read out. */
    PRICED(false),

    /** The increase needs the customer's recorded agreement before it commits. */
    AWAITING_CUSTOMER_CONFIRMATION(false),

    /** An online-paid order whose incremental charge has not yet succeeded. */
    AWAITING_PAYMENT(false),

    APPLIED(true),
    REJECTED(true),
    EXPIRED(true);

    private final boolean terminal;

    AmendmentStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean terminal() {
        return terminal;
    }

    /**
     * Whether this amendment still holds the order.
     *
     * <p>One open amendment per order, guarded by a partial unique index on
     * exactly this set. Two operators on one order is routine, and without the
     * index both would build a change against the same base revision.
     */
    public boolean open() {
        return !terminal;
    }
}
