package uz.horecaos.platform.payments.infrastructure.payme;

/**
 * Payme's JSON-RPC error codes, verbatim (ADR 0013).
 *
 * <p>These never leave this package. The domain sees a HorecaOS failure code and
 * never a provider one, because Click's vocabulary and Payme's have no values in
 * common and a shared error enumeration would have to invent a union nobody
 * speaks.
 *
 * <p>Every one of these travels in an <strong>HTTP 200</strong> response body.
 * Payme reads any other status as {@link #INTERNAL_ERROR}, which is why
 * {@link #INSUFFICIENT_PRIVILEGE} in particular cannot be delivered by Spring
 * Security's stock {@code httpBasic()}: that answers a bodyless 401 and fails
 * Payme's very first sandbox test.
 */
public final class PaymeErrorCode {

    /** The request did not arrive by POST. */
    public static final int METHOD_NOT_POST = -32300;

    /** The body would not parse. The docs say -32700 here; Payme's PHP template says -32600. */
    public static final int PARSE_ERROR = -32700;

    /** The body parsed and is structurally wrong: a missing field, or a field of the wrong type. */
    public static final int INVALID_REQUEST = -32600;

    /** No such method. The method name goes in {@code data}. */
    public static final int METHOD_NOT_FOUND = -32601;

    /**
     * The authentication failure code, and the whole reason this adapter does its
     * own Basic authentication.
     */
    public static final int INSUFFICIENT_PRIVILEGE = -32504;

    /** A dependency of ours failed. Also what Payme synthesises for any non-200 status. */
    public static final int INTERNAL_ERROR = -32400;

    /** The amount does not match the invoiced amount. One-time accounts only. */
    public static final int WRONG_AMOUNT = -31001;

    /** No transaction of that id. Every method but {@code CreateTransaction} may answer it. */
    public static final int TRANSACTION_NOT_FOUND = -31003;

    /**
     * The goods or the service were delivered in full, so the transaction cannot be
     * cancelled.
     *
     * <p>The only veto HorecaOS has over a refund Payme initiated from the merchant
     * cabinet. See {@code PaymeMerchantApi#refundIsAllowed}.
     */
    public static final int ORDER_ALREADY_DELIVERED = -31007;

    /** The operation cannot be performed here — the state machine says no. */
    public static final int OPERATION_NOT_PERMITTED = -31008;

    /**
     * The first code of the account-error range.
     *
     * <p>Between {@link #ACCOUNT_RANGE_FIRST} and {@link #ACCOUNT_RANGE_LAST} the
     * localised {@code message} object is mandatory and {@code data} must name the
     * offending {@code account} sub-field. HorecaOS's account schema has exactly one
     * field, so {@code data} is always {@code "order_id"}.
     */
    public static final int ACCOUNT_RANGE_FIRST = -31050;

    public static final int ACCOUNT_RANGE_LAST = -31099;

    /** {@code SetFiscalData}: no receipt of that id. */
    public static final int FISCAL_RECEIPT_NOT_FOUND = -32001;

    /** {@code SetFiscalData}: an invalid parameter, named in {@code message}. */
    public static final int FISCAL_INVALID_PARAMETERS = -32602;

    private PaymeErrorCode() {
    }

    /** Whether a code falls in the range whose localised message and {@code data} are mandatory. */
    public static boolean isAccountError(int code) {
        return code <= ACCOUNT_RANGE_FIRST && code >= ACCOUNT_RANGE_LAST;
    }
}
