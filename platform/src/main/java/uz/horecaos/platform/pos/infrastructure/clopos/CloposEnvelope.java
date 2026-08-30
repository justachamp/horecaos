package uz.horecaos.platform.pos.infrastructure.clopos;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import uz.horecaos.platform.integration.api.pos.PosApiCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * Reads a Clopos response, including the ones that lie about their status code.
 *
 * <p><strong>Read {@code success} before the status code.</strong> Clopos states
 * this outright in its own documentation, because at least one authentication
 * failure — a test integrator used against a production brand — is returned as
 * {@code 200 OK} with {@code success: false}. An adapter that branches on HTTP
 * status alone treats that misconfiguration as a successful authentication and
 * then fails incomprehensibly on the next call, several layers away from the
 * cause.
 *
 * <p>Three error vocabularies coexist in this API and none of them is
 * authoritative. The prose error page returns human sentences
 * ("Headers are missing", "Token expired"); endpoint pages return snake_case
 * slugs ("not_found", "validation_failed"); and the OpenAPI {@code Error} schema
 * is a third shape with no {@code success} field at all, contradicting the
 * documented envelope that says to branch on it. Everything here therefore
 * matches defensively across all three rather than switching on any one.
 *
 * <p>The classification below is this platform's engineering position and not
 * something Clopos publishes. The line it draws is the one that matters: a 401
 * saying our integrator is unknown is a <em>platform-wide</em> failure that
 * happens to arrive as a per-tenant status code, and an adapter that treats every
 * 401 as "this restaurant's credentials are wrong" will suspend every binding in
 * the estate the day Clopos deactivates our integrator.
 */
public final class CloposEnvelope {

    /**
     * Clopos error strings that mean our integrator registration is broken.
     *
     * <p>Not the restaurant's problem and not fixable by the restaurant. These
     * page an engineer rather than emailing a branch manager.
     */
    private static final List<String> PLATFORM_WIDE = List.of(
            "invalid integrator_id",
            "integrator is in test mode");

    private CloposEnvelope() {
    }

    /**
     * Turns a transport outcome into a Clopos outcome.
     *
     * @param effect what the call would repeat if it were sent again. Needed here
     *               because a 200 carrying {@code success: false} is a failure the
     *               transport already called a success, and reclassifying it must
     *               not turn an unkeyed create into something a caller may repeat
     */
    public static ProviderOutcome read(ProviderOutcome transportOutcome, PosApiCall.Effect effect) {
        if (transportOutcome.status() != ProviderOutcome.Status.SUCCESS) {
            return reclassify(transportOutcome, effect);
        }

        Map<String, Object> body = transportOutcome.normalized();
        Object success = body.get("success");
        if (Boolean.FALSE.equals(success)) {
            String error = string(body, "error");
            String message = string(body, "message");
            // Terminal, always. Every documented case of this shape is a
            // configuration fault — a test integrator against a production brand,
            // a disabled client — and none of them improves by being sent again.
            return ProviderOutcome.rejected(
                    isPlatformWide(error) ? "CLOPOS_INTEGRATOR_INVALID" : "CLOPOS_REFUSED",
                    describe(error, message));
        }
        return transportOutcome;
    }

    private static ProviderOutcome reclassify(ProviderOutcome outcome, PosApiCall.Effect effect) {
        if (outcome.status() != ProviderOutcome.Status.REJECTED) {
            // Retryable and uncertain outcomes were decided by the transport from
            // evidence this class does not have — whether the request left the
            // process at all — and second-guessing that here would be guessing.
            return outcome;
        }
        String detail = outcome.detail() == null ? "" : outcome.detail();
        if (isPlatformWide(detail)) {
            return ProviderOutcome.rejected("CLOPOS_INTEGRATOR_INVALID",
                    "HorecaOS's integrator registration was refused, which affects every brand "
                            + "rather than this one: " + trim(detail));
        }
        if (detail.toLowerCase(java.util.Locale.ROOT).contains("client is disabled")) {
            // The restaurant switched the Open API module off in their own back
            // office. Fixable by them in thirty seconds, and by nobody else.
            return ProviderOutcome.rejected("CLOPOS_CLIENT_DISABLED",
                    "The restaurant has disabled the Open API module for these credentials");
        }
        if (detail.toLowerCase(java.util.Locale.ROOT).contains("token expired")) {
            // The only genuinely retryable 401. The session token aged out; a new
            // one costs one call to /auth.
            return ProviderOutcome.retryable("CLOPOS_TOKEN_EXPIRED", trim(detail), null);
        }
        return outcome;
    }

    private static boolean isPlatformWide(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return PLATFORM_WIDE.stream().anyMatch(lower::contains);
    }

    /** The list payload of a successful response, or empty. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> dataList(Map<String, Object> body) {
        Object data = body.get("data");
        if (data instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    /** The single-object payload of a successful response, or empty. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> dataObject(Map<String, Object> body) {
        Object data = body.get("data");
        return data instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** The pre-pagination count Clopos reports, when it reports one. */
    public static Optional<Integer> total(Map<String, Object> body) {
        Object total = body.get("total");
        return total instanceof Number number ? Optional.of(number.intValue()) : Optional.empty();
    }

    public static String string(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Reads a money field without ever going through a double.
     *
     * <p>Clopos types every amount as a JSON {@code number} and carries no
     * currency field anywhere in its API, and its own examples are inconsistent
     * about scale: a receipt total of 30000 sits beside product prices like 8.5.
     * For UZS whole-som amounts a double would in fact be exact, but a boundary
     * where money is a floating-point value is a boundary that will eventually
     * meet a service charge percentage, and the intermediate of that will not
     * round-trip. Parsing the token's own text keeps the value exact whatever it
     * turns out to be.
     */
    public static BigDecimal decimal(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        // Jackson hands back Integer, Long, Double or BigDecimal depending on the
        // literal. String.valueOf on a Double would re-render it through binary
        // floating point, so anything that is already exact is taken as it is and
        // only the Double case is re-parsed from its shortest exact text.
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).strip());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    public static boolean flag(Map<String, Object> body, String key, boolean fallback) {
        Object value = body.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        // Clopos expresses several booleans as integer 1 and 0 — status and
        // is_main among them — and one of them, hidden, arrives both ways.
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return fallback;
    }

    private static String describe(String error, String message) {
        if (error != null && message != null) {
            return error + ": " + trim(message);
        }
        return trim(error != null ? error : message);
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        // Bounded because a Clopos error body has been observed to echo request
        // content back, and a request body here carries a customer's address.
        return value.length() > 300 ? value.substring(0, 300) : value;
    }
}
