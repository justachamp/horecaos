package uz.horecaos.platform.payments.infrastructure.payme;

/**
 * Payme's {@code Reason} codes (ADR 0013).
 *
 * <p>Stored verbatim as text on the attempt and on the transaction, and returned
 * unchanged in {@code CheckTransaction} and {@code GetStatement}. Not an enum on
 * the storage side: these are things Payme said, and a value Payme adds later is a
 * fact to record and investigate rather than a deserialization failure on the path
 * that credits an order.
 *
 * <p>Null for a transaction that was never cancelled, and left null rather than
 * zeroed — Payme's own {@code CheckTransaction} example pairs a {@code cancel_time}
 * of {@code 0} with a {@code reason} of {@code null}, so the two unset markers are
 * genuinely different.
 */
public final class PaymeCancellationReason {

    /** One or more receivers not found or inactive in Payme Business. */
    public static final int RECEIVER_UNAVAILABLE = 1;

    /** Error during the debit operation in the processing centre. */
    public static final int DEBIT_FAILED = 2;

    /** Transaction execution error. */
    public static final int EXECUTION_FAILED = 3;

    /**
     * Cancelled by timeout — twelve hours from Payme's own creation time.
     *
     * <p>The reason HorecaOS writes whenever it expires a transaction itself, both
     * lazily on the next inbound call and from the background sweep. Payme's Java
     * template sets the state and forgets the reason and the cancel time, which
     * leaves the transaction unreportable through {@code CheckTransaction} and
     * {@code GetStatement} — the two places it most needs to appear.
     */
    public static final int TIMEOUT = 4;

    /** Money returned. What a cabinet-initiated refund normally arrives with. */
    public static final int REFUND = 5;

    public static final int UNKNOWN = 10;

    private PaymeCancellationReason() {
    }
}
