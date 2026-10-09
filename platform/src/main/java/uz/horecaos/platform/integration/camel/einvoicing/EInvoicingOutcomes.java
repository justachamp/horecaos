package uz.horecaos.platform.integration.camel.einvoicing;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.commercial.api.EInvoiceSendOutcome;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * What a classified {@link ProviderOutcome} means for sending one document (ADR 0007, ADR
 * 0096): the three answers the platform's sender acts on differently, drawn once for both
 * adapters so they cannot disagree about when it is safe to send again.
 *
 * <p>The rule is the one the payment route states for a charge. A create is not idempotent at
 * either operator, so only an outcome that <em>provably</em> wrote nothing is "not sent"; an
 * operator that answered with a business refusal holds nothing either; and everything else --
 * above all a timeout or reset after the request was written, a 5xx, a 409 -- is uncertain,
 * which the platform resolves by asking the operator for the document and never by sending it
 * again.
 */
public final class EInvoicingOutcomes {

    /** Failures that happened before the request left, or that the operator refused before acting. */
    private static final Set<String> NOTHING_WAS_WRITTEN = Set.of(
            "CIRCUIT_OPEN",
            "CONNECTION_FAILED",
            "CONNECT_TIMEOUT",
            "TRANSPORT_FAILURE",
            "RATE_LIMITED",
            "PROVIDER_AUTHENTICATION");

    /** Long digit runs: a phone number, a card fragment, a passport number -- whatever turns up in free text. */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{9,}");

    private static final int MAX_DETAIL = 300;

    private EInvoicingOutcomes() {}

    /** The outcome of a create that did not succeed, as the sender's three answers. */
    public static EInvoiceSendOutcome sendFailure(ProviderOutcome outcome) {
        String code = outcome.errorCode() == null ? "UNKNOWN" : outcome.errorCode();
        String detail = outcome.detail() == null ? "" : outcome.detail();
        return switch (outcome.status()) {
            case SUCCESS -> throw new IllegalArgumentException("A successful outcome is not a failure");
            case UNCERTAIN -> new EInvoiceSendOutcome.Uncertain(code, detail);
            case REJECTED -> {
                if ("PROVIDER_AUTHENTICATION".equals(code)) {
                    yield new EInvoiceSendOutcome.NotSent("OPERATOR_AUTHENTICATION", detail);
                }
                yield EInvoicingGateway.NOTHING_WAS_SENT.contains(code)
                        ? new EInvoiceSendOutcome.NotSent(code, detail)
                        : new EInvoiceSendOutcome.Refused(code, detail);
            }
            case RETRYABLE ->
                NOTHING_WAS_WRITTEN.contains(code) || EInvoicingGateway.NOTHING_WAS_SENT.contains(code)
                        ? new EInvoiceSendOutcome.NotSent(code, detail)
                        : new EInvoiceSendOutcome.Uncertain(code, detail);
        };
    }

    /** The first non-blank text among these keys of a parsed operator answer, however the operator nests it. */
    public static @Nullable String text(@Nullable Map<String, Object> body, List<String> keys) {
        if (body == null) {
            return null;
        }
        for (String key : keys) {
            Object value = body.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text.strip();
            }
            if (value instanceof Number number) {
                return number.toString();
            }
        }
        return null;
    }

    /** The first integer among these keys, as an operator may write one as a number or as a digit string. */
    public static @Nullable Integer integer(@Nullable Map<String, Object> body, List<String> keys) {
        if (body == null) {
            return null;
        }
        for (String key : keys) {
            Object value = body.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text && text.strip().matches("-?\\d{1,9}")) {
                return Integer.parseInt(text.strip());
            }
        }
        return null;
    }

    /** Free text from an operator, bounded and scrubbed of long digit runs, for a failure detail a person reads. */
    public static String scrub(@Nullable String text) {
        if (text == null) {
            return "";
        }
        String scrubbed = DIGIT_RUN.matcher(text.strip()).replaceAll("[redacted]");
        return scrubbed.length() <= MAX_DETAIL ? scrubbed : scrubbed.substring(0, MAX_DETAIL);
    }

    @SuppressWarnings("unchecked")
    public static @Nullable Map<String, Object> map(@Nullable Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(@Nullable Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }
}
