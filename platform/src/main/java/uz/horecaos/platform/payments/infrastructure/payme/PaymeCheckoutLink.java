package uz.horecaos.platform.payments.infrastructure.payme;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import uz.horecaos.platform.payments.domain.TiyinAmount;

/**
 * The outbound half of Payme, which is a string (ADR 0013).
 *
 * <p>There is no server-side "create invoice" call in the Payme Merchant API. A
 * checkout is a URL or an HTML form carrying the cashbox id, the amount in tiyin,
 * and the account fields, and <strong>it is not authenticated</strong>: anyone can
 * construct one with any amount against any order reference they can guess. Which
 * is why nothing this class produces may ever credit an order, and why
 * {@code CheckPerformTransaction} recomputing the amount server-side is the only
 * thing standing between the platform and a customer paying one som for a hundred
 * thousand som order.
 *
 * <p><strong>The encoding.</strong> The format is
 * {@code <checkout_host>/base64(payload)} with {@code ;} between parameters and
 * {@code key=value} within them. The provider notes verified the documented worked
 * example byte for byte: it is standard RFC 4648 base64 of the raw ASCII payload,
 * not URL-safe base64 and not percent-encoded afterwards. That example is 48 bytes
 * long, so it happens to produce neither {@code =} padding nor a {@code +} or
 * {@code /} in the output, and the docs say nothing about either case.
 *
 * <p>Two decisions follow, both pinned by tests so a sandbox result can overturn
 * them without anyone having to reconstruct the reasoning:
 *
 * <ol>
 * <li><strong>Padding is kept.</strong> Standard base64, {@code =} and all. A real
 * payload here is 74 bytes plus the digits of the amount, so padding appears for
 * two amount lengths out of three and cannot be treated as a rare case. The
 * alternative the notes suggest — lengthening the plaintext until it is a multiple
 * of three, for instance by appending {@code ;l=ru} — is deliberately not done: it
 * makes the emitted link depend on the amount's digit count, which is a far worse
 * thing to debug than a trailing equals sign.</li>
 * <li><strong>Only {@code /} is percent-encoded.</strong> Standard base64 emits
 * {@code +}, {@code /} and {@code =}; of those, {@code +} and {@code =} are legal
 * characters inside a URL path segment and survive unchanged, while {@code /} is
 * the one character that changes the URL's structure rather than its content. It
 * is therefore written {@code %2F} and nothing else is touched. This is
 * controlled by a flag so that a sandbox can settle it with a property change.</li>
 * </ol>
 *
 * <p>Both are open question U19 in the provider notes. Neither is guessable from
 * the documentation, and the only arbiter is a sandbox transaction.
 */
public final class PaymeCheckoutLink {

    /** The cashbox id. */
    private static final String MERCHANT = "m";

    /** Account fields, one per {@code ac.<field>=<value>} pair. */
    private static final String ACCOUNT_PREFIX = "ac.";

    /** The amount, in tiyin. */
    private static final String AMOUNT = "a";

    private static final char PARAMETER_SEPARATOR = ';';

    private PaymeCheckoutLink() {
    }

    /**
     * The plaintext payload, in the order the documented example uses.
     *
     * @param amount tiyin, because every Payme amount that has ever existed is
     *               tiyin. The type is the guard: a som figure does not compile
     *               here, which turns a hundredfold overcharge into a build failure
     */
    public static String payload(String cashboxId, String orderReference, TiyinAmount amount) {
        Objects.requireNonNull(cashboxId, "A cashbox id is required");
        Objects.requireNonNull(orderReference, "An order reference is required");
        Objects.requireNonNull(amount, "An amount is required");

        if (amount.value() <= 0) {
            // Payme's Amount type is "a positive integer, greater than zero". A
            // zero-amount link is refused at the checkout page with an error the
            // customer cannot act on, so it is refused here instead.
            throw new IllegalArgumentException("A Payme checkout amount must be greater than zero");
        }

        return MERCHANT + '=' + cashboxId
                + PARAMETER_SEPARATOR + ACCOUNT_PREFIX + PaymeAccount.ORDER_FIELD + '='
                + orderReference
                + PARAMETER_SEPARATOR + AMOUNT + '=' + amount.value();
    }

    /** Standard RFC 4648 base64, padding kept. See the class comment. */
    public static String encode(String payload) {
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * The link the browser follows and the QR encodes — they are the same string.
     *
     * @param percentEncodePathSeparator whether a {@code /} in the base64 output is
     *                                   written {@code %2F}. True is the safe
     *                                   default; a sandbox that rejects it flips
     *                                   one property
     */
    public static String url(String checkoutHost, String payload,
            boolean percentEncodePathSeparator) {
        String encoded = encode(payload);
        if (percentEncodePathSeparator) {
            encoded = encoded.replace("/", "%2F");
        }
        return trimTrailingSlash(checkoutHost) + '/' + encoded;
    }

    /**
     * The POST form's fields.
     *
     * <p>Used rather than the GET link whenever a {@code detail} object has to
     * travel, because {@code detail} and {@code description} are documented only
     * for the form and not for the link — open question U20 — and a fiscalised
     * cashbox that silently loses its item lines produces a receipt that does not
     * match the charge.
     *
     * <p>{@code account[order_id]} uses the bracket notation the docs specify. The
     * {@code callback} is omitted when none is supplied, in which case Payme falls
     * back to the request's {@code Referer} — and either way the return is a
     * browser redirect that proves nothing. Only {@code PerformTransaction} does.
     */
    public static Map<String, String> formFields(String cashboxId, String orderReference,
            TiyinAmount amount, String language, String callbackUrl, String detailBase64) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("merchant", cashboxId);
        fields.put("amount", Long.toString(amount.value()));
        fields.put("account[" + PaymeAccount.ORDER_FIELD + "]", orderReference);
        if (language != null && !language.isBlank()) {
            fields.put("lang", language);
        }
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            fields.put("callback", callbackUrl);
        }
        if (detailBase64 != null && !detailBase64.isBlank()) {
            fields.put("detail", detailBase64);
        }
        return fields;
    }

    private static String trimTrailingSlash(String host) {
        Objects.requireNonNull(host, "A checkout host is required");
        return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
    }
}
