package uz.horecaos.platform.fiscal.domain;

import java.util.Set;

/**
 * Why a fiscal document is in the state it is (ADR 0038).
 *
 * <p>Every document carries one in every state — the column is NOT NULL — because
 * a reason that is only present when something is wrong is a reason nobody
 * queries. The cash code in particular exists to be queried: the 2026-08-22
 * decision was recorded as a value precisely so that reversing it is a migration
 * rather than an accountant reading order histories.
 *
 * <p>The blocking reasons are the ADR's list. Only one of them has a producer in
 * this build, and the rest are named here rather than invented later because a
 * worklist that can only say {@code PROVIDER_REPORT_OVERDUE} teaches operators
 * that "blocked" means "Payme again", which is exactly the habit that makes the
 * first {@code MARKS_INCOMPLETE} invisible.
 */
public final class FiscalReasonCode {

    // ------------------------------------------------------------- resolved

    /**
     * The 2026-08-22 decision, written where a query can find it.
     *
     * <p>Neither provider can fiscalize a cash order: Click's {@code submit_items}
     * requires a CLICK {@code payment_id} that does not exist, and Payme's fiscal
     * data attaches to a Payme receipt that does not exist. Click's
     * {@code received_cash} is a tender split <em>inside</em> a Click payment and
     * is not a cash path.
     */
    public static final String CASH_TENDER_NO_PROVIDER_FISCALIZATION = "CASH_TENDER_NO_PROVIDER_FISCALIZATION";

    /** A provider issued a receipt and its evidence is on the row. */
    public static final String PARTNER_FISCALIZED = "PARTNER_FISCALIZED";

    // -------------------------------------------------------------- in flight

    /** Nothing can be sent yet, because Click cannot fiscalize before a capture. */
    public static final String AWAITING_CAPTURE = "AWAITING_CAPTURE";

    /** Sent, and the provider has not reported an outcome. */
    public static final String AWAITING_PROVIDER = "AWAITING_PROVIDER";

    /** The provider answered with a non-zero status, which is evidence of no receipt. */
    public static final String PROVIDER_REJECTED = "PROVIDER_REJECTED";

    // ---------------------------------------------------------------- blocked

    /**
     * The reporting deadline passed with no answer. The sweeper's code, and the
     * only one this build produces.
     */
    public static final String PROVIDER_REPORT_OVERDUE = "PROVIDER_REPORT_OVERDUE";

    /** A priceable node on this order reaches a receipt with no ИКПУ. */
    public static final String CLASSIFICATION_MISSING = "CLASSIFICATION_MISSING";

    /** Fewer marking codes were captured than the line's quantity. */
    public static final String MARKS_INCOMPLETE = "MARKS_INCOMPLETE";

    /** No provider account and no fiscal terminal can discharge this obligation. */
    public static final String NO_FISCAL_PATH = "NO_FISCAL_PATH";

    /** The entity's own fiscal-capable equipment did not answer. */
    public static final String TERMINAL_OFFLINE = "TERMINAL_OFFLINE";

    /**
     * The receipt lines could not be built because the tax rule of the sale cannot be stated on a
     * receipt: the rate is not a whole percent, the prices were not VAT-inclusive, or the quote recorded no
     * rule (ADR 0166 Decision 9, ADR 0038). Nothing was sent.
     */
    public static final String VAT_NOT_EXPRESSIBLE = "VAT_NOT_EXPRESSIBLE";

    /**
     * The receipt lines could not be made to add up to the payment, or a figure they need was never
     * recorded (ADR 0166 Decision 9). Nothing was sent and no line absorbs the difference.
     */
    public static final String RECEIPT_DOES_NOT_RECONCILE = "RECEIPT_DOES_NOT_RECONCILE";

    /**
     * Historical. Before the receipt line builder existed a claimed document was blocked with this reason
     * because there was nothing to file but a placeholder that may not be filed (ADR 0166, Decision 6).
     * Nothing produces it any more; rows that carry it are retried through the builder like any other
     * blocked document, so it stays a valid reason to filter and unblock by.
     */
    public static final String LINES_NOT_BUILT = "LINES_NOT_BUILT";

    /**
     * The blocking reasons, so a worklist filter and an operator's unblock command
     * are validated against one list rather than two that drift.
     */
    public static final Set<String> BLOCKING = Set.of(
            PROVIDER_REPORT_OVERDUE,
            CLASSIFICATION_MISSING,
            MARKS_INCOMPLETE,
            NO_FISCAL_PATH,
            TERMINAL_OFFLINE,
            VAT_NOT_EXPRESSIBLE,
            RECEIPT_DOES_NOT_RECONCILE,
            LINES_NOT_BUILT);

    private FiscalReasonCode() {}
}
