package uz.horecaos.platform.payments.domain;

/**
 * What a provider told the platform had happened (ADR 0013).
 *
 * <p>These name events, not commands. {@link #EXPIRE} is the exception and is
 * marked as such: Payme expresses it as a cancellation with reason {@code 4}, and
 * Click has no expiry at all, so on Click this is a fact HorecaOS decided and the
 * provider is never told.
 */
public enum PaymentTransactionType {
    RESERVE,

    CAPTURE,

    CANCEL,

    /** HorecaOS's own reservation timeout. See {@link #CANCEL} for the provider-side event. */
    EXPIRE,

    /** A reversal HorecaOS initiated, which only Click offers and which may still be refused. */
    REVERSE,

    /**
     * A refund executed in the provider's console and back-recorded here.
     *
     * <p>No platform-initiated refund ships at cutover, because neither provider
     * has the primitive in the shape the platform needs: Click's reversal takes no
     * amount and is bounded by the reporting month, and Payme has no outbound
     * refund call at all. The type exists so that a console refund has somewhere
     * to be recorded, which is what makes the daily settlement reconciliation
     * enforceable rather than advisory.
     */
    REFUND;

    public boolean movesMoneyToTheMerchant() {
        return this == CAPTURE;
    }

    public boolean movesMoneyToTheCustomer() {
        return this == REVERSE || this == REFUND;
    }
}
