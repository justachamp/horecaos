package uz.horecaos.platform.commercial.api;

/**
 * Where a sent document stands, as an operator reports it (ADR 0096 decision 3):
 * sent, signed by the buyer, refused -- plus draft, which is where a document the
 * platform has handed over starts (it is signed and sent to the buyer by people
 * inside the operator's own product, never by HorecaOS's code), cancelled, which
 * both operators have, and unknown, which is an honest answer when an operator
 * reports a status this adapter does not recognise.
 *
 * <p>The operator's own words are kept beside this reading (the raw status the
 * adapter was given), so a status mapped to {@link #UNKNOWN} is still visible and
 * a wrong mapping is fixable without losing what was reported.
 */
public enum EInvoiceOperatorState {

    /**
     * The operator holds it as a draft invoice to the buyer: the platform has handed it
     * over and nobody at HorecaOS has yet signed and sent it from the operator's product.
     */
    DRAFT(false),

    /** The operator holds the invoice, signed by HorecaOS, and the buyer has not yet signed or refused it. */
    SENT(false),

    /** The buyer signed it: this is the invoice. */
    SIGNED(true),

    /** The buyer refused it. The statement may be sent again. */
    REFUSED(true),

    /** The invoice was cancelled at the operator. The statement may be sent again. */
    CANCELLED(true),

    /** The operator reports a status this adapter does not recognise. Asked again later. */
    UNKNOWN(false);

    private final boolean settled;

    EInvoiceOperatorState(boolean settled) {
        this.settled = settled;
    }

    /** Whether the operator will say nothing further worth asking for. */
    public boolean settled() {
        return settled;
    }

    /** Whether the document stops standing as the invoice for its statement. */
    public boolean releasesTheStatement() {
        return this == REFUSED || this == CANCELLED;
    }
}
