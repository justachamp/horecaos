package uz.horecaos.platform.payments.infrastructure.click;

/**
 * The codes HorecaOS returns to Click on Prepare and Complete (ADR 0013).
 *
 * <p>The notes are Click's own strings and are returned verbatim. They are not
 * decoration: {@code error_note} is what Click's support tooling displays, and a
 * paraphrase makes a support conversation about a stuck payment harder than it
 * needs to be.
 *
 * <p>Two of these carry rules that outrank everything else in this adapter.
 *
 * <p>{@link #ALREADY_PAID} is <strong>not a failure</strong>. It is the documented
 * answer to a replayed Complete for a settled payment, and Click reads it as
 * "fine, settled". Reporting a replay as anything else is how a paid order gets
 * charged twice or investigated as broken.
 *
 * <p>{@link #FAILED_TO_UPDATE_USER} is the <strong>only</strong> code here that
 * means "transient, come back", and it may be returned only when nothing about the
 * order was decided — a database write that failed, and no more. It must never
 * carry a business decision. After a successful charge, the response to Complete
 * may be {@code 0}, {@code -4} or {@code -9} and nothing else; an unfulfillable
 * order is answered {@code 0} and then reversed through
 * {@code DELETE payment/reversal}. Returning an error there leaves the customer
 * charged and uncredited while Click retries and finally escalates to its own
 * support.
 */
public enum ClickShopApiError {

    SUCCESS(0, "Success"),

    SIGN_CHECK_FAILED(-1, "SIGN CHECK FAILED!"),

    INCORRECT_AMOUNT(-2, "Incorrect parameter amount"),

    ACTION_NOT_FOUND(-3, "Action not found"),

    /** Terminal, and it means success. See the class note. */
    ALREADY_PAID(-4, "Already paid"),

    USER_DOES_NOT_EXIST(-5, "User does not exist"),

    TRANSACTION_DOES_NOT_EXIST(-6, "Transaction does not exist"),

    /** The one retryable code. Infrastructure only, never a business decision. */
    FAILED_TO_UPDATE_USER(-7, "Failed to update user"),

    BAD_REQUEST(-8, "Error in request from click"),

    /** Also the answer when Click's own {@code error} field arrived negative. */
    TRANSACTION_CANCELLED(-9, "Transaction cancelled");

    private final int code;
    private final String note;

    ClickShopApiError(int code, String note) {
        this.code = code;
        this.note = note;
    }

    public int code() {
        return code;
    }

    /** Click's own wording, returned unchanged. */
    public String note() {
        return note;
    }

    public boolean successful() {
        return this == SUCCESS;
    }

    /**
     * Whether this code may be returned once the card has been charged.
     *
     * <p>The rule from the most important paragraph of the SHOP API documentation,
     * expressed as a method so a caller can be made to ask it rather than
     * remember it.
     */
    public boolean permittedAfterASuccessfulCharge() {
        return this == SUCCESS || this == ALREADY_PAID || this == TRANSACTION_CANCELLED;
    }
}
