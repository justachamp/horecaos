package uz.horecaos.platform.payments.infrastructure.click;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The SHOP API {@code sign_string}, and the MERCHANT API {@code Auth} header
 * (ADR 0013).
 *
 * <p>Both are here because both are keyed on the same {@code secret_key} and
 * neither is used anywhere else. Neither is an HMAC — Click concatenates the
 * secret into the middle of one digest input and appends it to the other — so
 * there is no library primitive that expresses either, and writing them out is
 * the only way to write them correctly.
 *
 * <h2>Inbound: {@code sign_string}</h2>
 *
 * <pre>
 * Prepare  md5( click_trans_id ++ service_id ++ SECRET ++ merchant_trans_id
 *               ++ amount ++ action ++ sign_time )
 * Complete md5( click_trans_id ++ service_id ++ SECRET ++ merchant_trans_id
 *               ++ merchant_prepare_id ++ amount ++ action ++ sign_time )
 * </pre>
 *
 * <p>The only difference is {@code merchant_prepare_id}, which is <em>absent</em>
 * on Prepare rather than padded with an empty string. {@code click_paydoc_id} is
 * in neither, despite arriving on both, and so are {@code error} and
 * {@code error_note}.
 *
 * <p><strong>The amount is hashed exactly as received.</strong> It arrives as form
 * text and Click may send {@code 1000}, {@code 1000.0} or {@code 1000.00} for the
 * same figure; each produces a different MD5. Parsing it to a number and rendering
 * it back — even to the same number of decimal places — is the commonest cause of
 * a spurious {@code -1 SIGN CHECK FAILED!} in this integration, and it is the one
 * defect a test in this build exists specifically to catch. That is why every
 * parameter below is a {@code String} and none is a number.
 *
 * <p>MD5 with a shared-secret prefix is not a strong primitive, and this is the
 * only authentication on the endpoint that credits orders. That is Click's design
 * and cannot be improved from this side; what can be done is done — the comparison
 * is constant-time, every arrival including a failed one is recorded, and the
 * failure count per binding is the alert ADR 0013 asks for.
 */
public final class ClickSignature {

    private ClickSignature() {}

    /** {@code action=0}. Signs seven fields; no {@code merchant_prepare_id}. */
    public static String prepare(
            String secretKey,
            String clickTransId,
            String serviceId,
            String merchantTransId,
            String rawAmount,
            String action,
            String signTime) {
        return md5(concat(clickTransId, serviceId, secretKey, merchantTransId, rawAmount, action, signTime));
    }

    /** {@code action=1}. Signs eight fields; {@code merchant_prepare_id} is the fifth. */
    public static String complete(
            String secretKey,
            String clickTransId,
            String serviceId,
            String merchantTransId,
            String merchantPrepareId,
            String rawAmount,
            String action,
            String signTime) {
        return md5(concat(
                clickTransId, serviceId, secretKey, merchantTransId, merchantPrepareId, rawAmount, action, signTime));
    }

    /**
     * The digest this request should carry, chosen by its own {@code action}.
     *
     * <p>Reads every field straight off the record, so nothing between the wire
     * and the digest can reformat one.
     */
    public static String expected(String secretKey, ClickCallbackRequest request) {
        return request.isComplete()
                ? complete(
                        secretKey,
                        request.clickTransId(),
                        request.serviceId(),
                        request.merchantTransId(),
                        request.merchantPrepareId(),
                        request.amount(),
                        request.action(),
                        request.signTime())
                : prepare(
                        secretKey,
                        request.clickTransId(),
                        request.serviceId(),
                        request.merchantTransId(),
                        request.amount(),
                        request.action(),
                        request.signTime());
    }

    /**
     * Constant-time comparison of two hex digests.
     *
     * <p>Case-insensitive because the digest is hex and Click's own casing is not
     * promised anywhere; length-safe because a short or absent {@code sign_string}
     * must answer {@code -1} rather than throw.
     */
    public static boolean matches(String expected, String received) {
        if (expected == null || received == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                received.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The MERCHANT API {@code Auth} header:
     * {@code merchant_user_id:sha1(timestamp ++ secret_key):timestamp}.
     *
     * <p>String concatenation with no separator, the secret <em>appended</em> after
     * the timestamp, and the timestamp in whole seconds. Not an HMAC, however much
     * it resembles one. Click documents no validity window and no accepted clock
     * skew, so the timestamp is computed per call and never cached.
     */
    public static String authHeader(String merchantUserId, String secretKey, long epochSeconds) {
        String timestamp = Long.toString(epochSeconds);
        return merchantUserId + ":" + sha1(timestamp + secretKey) + ":" + timestamp;
    }

    private static String concat(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            // A null field cannot reach a correct signature, and a request missing
            // one has already been answered -8. Appending nothing keeps the digest
            // total rather than throwing on a probe.
            joined.append(part == null ? "" : part);
        }
        return joined.toString();
    }

    private static String md5(String input) {
        return digest("MD5", input);
    }

    private static String sha1(String input) {
        return digest("SHA-1", input);
    }

    private static String digest(String algorithm, String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // MD5 and SHA-1 are required of every JRE. If one is missing, the
            // deployment is unusable rather than degraded.
            throw new IllegalStateException(algorithm + " is unavailable", impossible);
        }
    }
}
