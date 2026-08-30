package uz.horecaos.platform.payments.domain;

/**
 * Where an order's fiscal obligation has got to, on the partner path (ADR 0013,
 * ADR 0038).
 *
 * <p>{@link #NOT_APPLICABLE} is the reason this is an enum and not a nullable
 * column. A null means "unknown", and the whole point of the cash decision is that
 * this is known and deliberate: neither payment provider can fiscalize a cash
 * order, and if that decision is ever reversed the affected orders must be found
 * by a query on a reason code rather than by inspecting orders one at a time.
 */
public enum FiscalStatus {

    /**
     * No payment provider can issue a receipt for this order, and that is a
     * decision rather than a gap.
     *
     * <p>Always accompanied by a reason code. Never a null, never an absent row.
     */
    NOT_APPLICABLE,

    /** A receipt is owed and has not been submitted. */
    PENDING,

    /**
     * Sent, and no outcome yet.
     *
     * <p>Reachable indefinitely on Payme, whose {@code SetFiscalData} arrives
     * asynchronously and may simply never come; the interval that turns silence
     * into a blocked document is an open question to Payme.
     */
    SUBMITTED,

    ISSUED,

    FAILED,

    /**
     * The reporting deadline passed and nothing arrived (ADR 0038).
     *
     * <p>Written only by the {@code fiscal} module's reporting sweeper, and read
     * here because a document that has been blocked is still an ordinary document
     * on this path: a late {@code SetFiscalData} is accepted after the block and
     * resolves it normally, so the Payme callback handler must be able to load a
     * row in this status rather than fail to map it.
     *
     * <p>The value is added here rather than the enum being widened by the fiscal
     * module because both modules read the same column, and
     * {@code ck_fiscal_document_status} is the one definition of what it may hold.
     */
    BLOCKED;

    public boolean owesAReceipt() {
        return this == PENDING || this == SUBMITTED || this == FAILED || this == BLOCKED;
    }
}
