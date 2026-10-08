package uz.horecaos.platform.commercial.domain;

/**
 * HorecaOS's own side of one attempt to send a statement to an operator (ADR 0096).
 *
 * <p>{@code PENDING} is written before the operator is called, so the attempt is
 * durable before anything can have been sent; {@code UNCERTAIN} is the attempt
 * whose answer never arrived, and it holds the statement until the operator says
 * what it holds.
 */
public enum EInvoiceDelivery {

    /** Recorded, and the operator is about to be called (or the call has not returned). */
    PENDING(true),

    /** The operator accepted the draft and named it. */
    SUBMITTED(true),

    /** The operator refused it or was never reached: nothing is held there. */
    FAILED(false),

    /** The operator may hold it: asked, never sent again. */
    UNCERTAIN(true);

    private final boolean holdsStatement;

    EInvoiceDelivery(boolean holdsStatement) {
        this.holdsStatement = holdsStatement;
    }

    /** Whether an attempt in this state may be standing as the statement's invoice. */
    public boolean holdsStatement() {
        return holdsStatement;
    }
}
