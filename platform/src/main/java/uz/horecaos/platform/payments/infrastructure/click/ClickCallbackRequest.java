package uz.horecaos.platform.payments.infrastructure.click;

import java.util.Map;
import java.util.Objects;

/**
 * One SHOP API arrival, held exactly as it came off the wire (ADR 0013).
 *
 * <p>Every field is a {@code String} and every field is the value Click actually
 * sent. That is not laziness, it is the contract: the signature is an MD5 over the
 * concatenation of these strings, so a field parsed into a number and rendered
 * back before hashing produces a different digest. Click may legitimately send
 * {@code 1000}, {@code 1000.0} or {@code 1000.00} for one amount, and each hashes
 * differently — reformatting is the single commonest cause of a spurious
 * {@code -1 SIGN CHECK FAILED!}.
 *
 * <p>So the discipline is: verify from this record, then parse. Parsing is
 * {@link #amountAsSom(String)}, and it is deliberately a separate step returning a
 * separate type.
 *
 * @param merchantPrepareId absent on Prepare — genuinely absent rather than an
 *                          empty string, which is what the signature formula
 *                          expresses by omitting it entirely
 * @param error             Click's own status. Negative means Click's side failed
 *                          and the payment must be voided, answered {@code -9}
 */
public record ClickCallbackRequest(
        String clickTransId,
        String serviceId,
        String clickPaydocId,
        String merchantTransId,
        String merchantPrepareId,
        String amount,
        String action,
        String error,
        String errorNote,
        String signTime,
        String signString) {

    /** Prepare. */
    public static final String ACTION_PREPARE = "0";

    /** Complete — the only Click surface that credits an order. */
    public static final String ACTION_COMPLETE = "1";

    public static ClickCallbackRequest fromForm(Map<String, String> form) {
        Objects.requireNonNull(form, "A form body is required");
        return new ClickCallbackRequest(
                form.get("click_trans_id"),
                form.get("service_id"),
                form.get("click_paydoc_id"),
                form.get("merchant_trans_id"),
                form.get("merchant_prepare_id"),
                form.get("amount"),
                form.get("action"),
                form.get("error"),
                form.get("error_note"),
                form.get("sign_time"),
                form.get("sign_string"));
    }

    public boolean isComplete() {
        return ACTION_COMPLETE.equals(action);
    }

    public boolean isKnownAction() {
        return ACTION_PREPARE.equals(action) || ACTION_COMPLETE.equals(action);
    }

    /**
     * Whether every field the protocol requires is present.
     *
     * <p>Answers the {@code -8} check, and answers it for a <em>partially</em>
     * missing body rather than only for an entirely empty one. The Django
     * reference gets this backwards — its {@code isset} helper returns true only
     * when every required field is absent — so a request missing just
     * {@code sign_time} sails past its {@code -8} and fails later as something
     * else. The PHP reference is correct and this follows it.
     */
    public boolean hasEveryRequiredField() {
        boolean common = present(clickTransId) && present(serviceId) && present(merchantTransId)
                && present(amount) && present(action) && present(signTime) && present(signString);
        return common && (!isComplete() || present(merchantPrepareId));
    }

    /**
     * Whether Click reported a failure on its own side.
     *
     * <p>A negative {@code error} on the incoming request means Click's processing
     * failed. The documented answer is to void the payment locally and reply
     * {@code -9}, whichever action this was.
     */
    public boolean reportsClickSideFailure() {
        if (!present(error)) {
            return false;
        }
        try {
            return Long.parseLong(error.strip()) < 0;
        } catch (NumberFormatException unparseable) {
            // Not a number where the protocol promises one. Treated as a failure
            // report rather than ignored: the conservative reading refuses to
            // credit, and the callback row records what actually arrived.
            return true;
        }
    }

    /**
     * The amount as whole som, or empty when it is not one.
     *
     * <p>Empty rather than rounded. ADR 0018 stores whole som and UZS is
     * transacted in whole som, so a fractional amount means Click and the platform
     * disagree about what is being charged, and the caller answers {@code -2}
     * rather than silently accepting a figure it had to alter to accept.
     *
     * <p>Parsed with {@link java.math.BigDecimal} rather than {@code Double}: both
     * Click reference implementations compare {@code float}s against the order
     * total with a 0.01 tolerance, and Django's does it through a misplaced
     * parenthesis that lets underpayment through. Whole-som integers have neither
     * problem.
     */
    public java.util.Optional<Long> amountAsSom() {
        return amountAsSom(amount);
    }

    static java.util.Optional<Long> amountAsSom(String raw) {
        if (!present(raw)) {
            return java.util.Optional.empty();
        }
        try {
            java.math.BigDecimal parsed = new java.math.BigDecimal(raw.strip());
            return java.util.Optional.of(parsed.longValueExact());
        } catch (ArithmeticException | NumberFormatException notWholeSom) {
            return java.util.Optional.empty();
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Omits the signature and the amount.
     *
     * <p>The signature is a keyed digest of a secret, and the amount plus the
     * merchant transaction id together identify a customer's order. Neither
     * belongs in a log line under ADR 0029.
     */
    @Override
    public String toString() {
        return "ClickCallbackRequest[action=" + action + " clickTransId=" + clickTransId
                + " paydoc=" + clickPaydocId + "]";
    }
}
