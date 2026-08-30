package uz.qoida.platform.payments.domain;

/**
 * Why a fiscal document is in the status it is (ADR 0013).
 *
 * <p>These are queried, not read. The cash reason in particular exists so that a
 * reversal of the 2026-08-22 decision is a migration rather than an archaeology
 * exercise, and {@code ix_fiscal_documents_not_applicable} is the index that makes
 * the query cheap.
 */
public final class FiscalReason {

    /**
     * The user's decision of 2026-08-22, written down where a query can find it.
     *
     * <p>Neither provider can fiscalize a cash order: Click's {@code submit_items}
     * requires a CLICK {@code payment_id} that does not exist, and Payme's fiscal
     * data attaches to a Payme receipt that does not exist. Cash is this market's
     * majority tender — the legacy {@code payment_methods} seeds it enabled — so
     * this is the common case and not an edge case, and partner fiscal coverage is
     * the minority of orders at cutover.
     */
    public static final String CASH_TENDER_NO_PROVIDER_FISCALIZATION =
            "CASH_TENDER_NO_PROVIDER_FISCALIZATION";

    public static final String CASH_TENDER_NOTE = "cash tender, no provider fiscalization";

    /** The provider issued a receipt and the evidence is on the row. */
    public static final String PARTNER_FISCALIZED = "PARTNER_FISCALIZED";

    /** Awaiting capture, because Click cannot fiscalize before a payment exists. */
    public static final String AWAITING_CAPTURE = "AWAITING_CAPTURE";

    /** Submitted, and the provider has not yet reported an outcome. */
    public static final String AWAITING_PROVIDER = "AWAITING_PROVIDER";

    /** The provider answered with a non-zero status, which is evidence of no receipt. */
    public static final String PROVIDER_REJECTED = "PROVIDER_REJECTED";

    private FiscalReason() {
    }
}
