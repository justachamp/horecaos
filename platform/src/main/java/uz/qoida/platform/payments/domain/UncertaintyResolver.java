package uz.qoida.platform.payments.domain;

/**
 * The named procedure that settles an uncertain attempt (ADR 0013).
 *
 * <p>Recorded on the attempt when it becomes uncertain, rather than inferred from
 * the provider when someone comes to look. Inferring it later works only while
 * every provider has exactly one resolution path, and Click already has two — a
 * lookup by Qoida's own id followed by a lookup by Click's.
 */
public enum UncertaintyResolver {

    /**
     * {@code GET /v2/merchant/payment/status_by_mti/{service_id}/{merchant_trans_id}/{business_date}},
     * then {@code payment/status} for the payment it names.
     *
     * <p>Two preconditions make it work and both are written before the mutating
     * call: the merchant transaction id, so there is a key to ask about, and the
     * business date, because the trailing path segment's meaning and timezone are
     * undocumented. A wrong date reads as "no payment found" — which is precisely
     * the answer that would make a retry look safe, and why a not-found from this
     * resolver never unblocks a second charge.
     */
    CLICK_STATUS_BY_MTI,

    /**
     * {@code CheckTransaction(id)}, which never mutates.
     *
     * <p>Uncertainty is structurally rarer on Payme, because the roles are
     * reversed: Payme is the client and repeats its own calls, so Qoida's
     * obligation is idempotency rather than polling. What remains uncertain is
     * internal — a crash between persisting the transaction state and committing
     * the order change — and this is the query that settles it. It must not expire
     * a transaction as a side effect.
     */
    PAYME_CHECK_TRANSACTION,

    /**
     * The automated path has run out of answers and a human owns it now.
     *
     * <p>Reached when Click's {@code status_by_mti} reports nothing after the
     * business date has been widened, or when the deadline passes. Deliberately a
     * state and not a retry: on Click, absence of evidence is not evidence of
     * absence.
     */
    OPERATIONS_EXCEPTION;

    public static UncertaintyResolver forProvider(PaymentProviderType provider) {
        return switch (provider) {
            case CLICK -> CLICK_STATUS_BY_MTI;
            case PAYME -> PAYME_CHECK_TRANSACTION;
            // Telegram's reconciliation path is the one thing that cannot be
            // specified today, because Payme does not document whether a
            // bot-cashbox payment reaches the Merchant API endpoint at all. An
            // uncertain Telegram attempt therefore goes straight to a human rather
            // than to a query nobody can promise answers.
            case TELEGRAM -> OPERATIONS_EXCEPTION;
        };
    }
}
