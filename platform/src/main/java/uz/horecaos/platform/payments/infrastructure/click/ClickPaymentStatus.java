package uz.horecaos.platform.payments.infrastructure.click;

import org.jspecify.annotations.Nullable;

/**
 * Click's {@code payment_status}, which is not Click's {@code error_code}
 * (ADR 0013).
 *
 * <p>Confusing the two is a documented way to credit an unpaid order. Several of
 * Click's own examples show {@code "payment_status": 1} in a response whose
 * {@code error_note} is {@code "Success"}: {@code error_code: 0} means the API
 * call worked, and only {@code payment_status: 2} means the money moved.
 *
 * <table>
 *   <caption>The documented values</caption>
 *   <tr><th>Value</th><th>Meaning</th></tr>
 *   <tr><td>&lt; 0</td><td>Error, detail in {@code error_note}</td></tr>
 *   <tr><td>0</td><td>Payment created</td></tr>
 *   <tr><td>1</td><td>In processing — <em>not yet money</em></td></tr>
 *   <tr><td>2</td><td>Successfully paid</td></tr>
 * </table>
 *
 * <p>The full enumeration of the negative statuses is not published anywhere
 * reachable, so they collapse into one {@link #FAILED} here rather than being
 * mapped from a table nobody has. That is a gap in Click's documentation and an
 * open question to Click, not a gap in this adapter.
 */
public enum ClickPaymentStatus {

    /** Created, and nothing has been charged. */
    CREATED,

    /** In flight. Answering this to a resolver means "ask again", never "failed". */
    IN_PROCESSING,

    /** The only value that means money moved. */
    PAID,

    /** Any negative status. Which negative one is undocumented. */
    FAILED,

    /** The field was absent or unparseable — which several endpoints do legitimately. */
    UNKNOWN;

    public static ClickPaymentStatus of(@Nullable Object raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        long value;
        try {
            value = raw instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(raw.toString().strip());
        } catch (NumberFormatException unparseable) {
            return UNKNOWN;
        }
        if (value < 0) {
            return FAILED;
        }
        return switch ((int) value) {
            case 0 -> CREATED;
            case 1 -> IN_PROCESSING;
            case 2 -> PAID;
            // A value Click has not documented. Read as still in flight rather
            // than as money, because the conservative error here costs one more
            // query and the other costs an uncollected order.
            default -> IN_PROCESSING;
        };
    }

    /** Whether this status still needs to be asked about. */
    public boolean inFlight() {
        return this == CREATED || this == IN_PROCESSING || this == UNKNOWN;
    }
}
