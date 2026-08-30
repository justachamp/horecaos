package uz.qoida.platform.fiscal.domain;

/**
 * Where an order's fiscal obligation has got to (ADR 0038).
 *
 * <p>This repeats the five values {@code payments.domain.FiscalStatus} carries
 * and adds the sixth. The repetition is deliberate rather than accidental:
 * {@code payments.domain} is module-internal, and a module reaching into another
 * module's internals to borrow an enum is the dependency Spring Modulith exists
 * to refuse. The vocabulary is a database vocabulary — the values are the ones
 * {@code ck_fiscal_document_status} permits — and both modules read it from the
 * same column, so the two enums cannot drift without the constraint saying so.
 *
 * <p>There is no null. That is the whole design: a null status is the same shape
 * as "not attempted yet", and the two things a tenant most needs to tell apart
 * are "no receipt was ever owed" and "a receipt is owed and nobody knows where it
 * is".
 */
public enum FiscalDocumentState {

    /**
     * No payment provider can issue a receipt for this leg, and that is a
     * recorded decision rather than a gap.
     *
     * <p>Cash, in practice, and therefore most orders. Always carries a reason
     * code, so a reversal of the 2026-08-22 decision is a query rather than an
     * archaeology exercise. It does not extinguish the obligation: the receipt
     * comes from the restaurant's own fiscal-capable equipment under ADR 0038's
     * {@code TERMINAL} responsibility, which is not built.
     */
    NOT_APPLICABLE,

    /** A receipt is owed and the provider has not been asked yet. */
    PENDING,

    /**
     * Asked, and no outcome yet.
     *
     * <p>The state this module exists for. On Payme it is reachable indefinitely:
     * the fiscal data goes out with the checkout and the outcome comes back
     * through an inbound callback that the provider is not obliged to send and
     * that Qoida cannot ask for again.
     */
    SUBMITTED,

    /** The provider reported a receipt, and its identifiers are on the row. */
    ISSUED,

    /**
     * The provider answered, and the answer was that there is no receipt.
     *
     * <p>Including a {@code SetFiscalData} that arrived with a non-zero
     * {@code status_code}: arrival is not proof of a receipt, and treating it as
     * one is a defect that passes every test written against the happy-path
     * example in the documentation.
     */
    FAILED,

    /**
     * A receipt is owed, nothing further will happen on its own, and a person has
     * to look at it.
     *
     * <p>Not an error status. An error is something that went wrong once;
     * {@code BLOCKED} is a piece of work with a reason attached, and the
     * difference matters because an order sitting unreceipted behind a generic
     * failure is how a tenant finds out at month end.
     */
    BLOCKED;

    /** Whether a receipt is still owed and has not been evidenced either way. */
    public boolean owesAReceipt() {
        return this == PENDING || this == SUBMITTED || this == FAILED || this == BLOCKED;
    }

    /** Whether the obligation has been resolved, one way or the other. */
    public boolean resolved() {
        return this == ISSUED || this == NOT_APPLICABLE;
    }

    public static FiscalDocumentState require(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new IllegalArgumentException("Unknown fiscal document state: " + value);
        }
    }
}
