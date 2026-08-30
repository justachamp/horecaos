package uz.horecaos.platform.payments.infrastructure.click;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import uz.horecaos.platform.payments.domain.SomAmount;

/**
 * The {@code my.click.uz/services/pay/} redirect (ADR 0013, direction C).
 *
 * <p><strong>Unsigned, and therefore unauthenticated.</strong> Anyone can build
 * this URL for a known {@code service_id} with any {@code amount} and any
 * {@code transaction_param}. The amount here is a suggestion to Click, not a
 * commitment by HorecaOS; the amount HorecaOS enforces is the one checked in Prepare,
 * against the intent. Nothing about a customer returning to {@code return_url}
 * credits anything.
 *
 * <p>Click's current documentation shows this link unsigned, on
 * {@code my.click.uz/services/pay/}, with lowercase parameters. The Django
 * reference instead builds a signed POST form against {@code my.click.uz/pay/}
 * with uppercase field names and a {@code SIGN_STRING} over
 * {@code sign_time ++ secret ++ service_id ++ merchant_trans_id ++ amount}. The
 * documentation is believed: two other current pages show the same unsigned
 * lowercase form, the deeplink examples are plainly unsigned, and the newer PHP
 * reference contains no payment-button form at all. <strong>Whether the signed
 * variant is still supported is an open question with CLICK.</strong>
 *
 * <p>{@code amount} is formatted {@code N.NN} here and as a whole number in the
 * MERCHANT API bodies. That difference is Click's, documented on the button page,
 * and it is the reason this formatting lives here rather than on
 * {@link ClickMerchantApi}.
 */
public final class ClickCheckoutLink {

    public static final String BASE = "https://my.click.uz/services/pay/";

    private ClickCheckoutLink() {
    }

    /**
     * @param merchantId Click's {@code merchant_id}, documented as mandatory.
     *                   Nullable here because the ADR 0013 merchant binding models
     *                   only {@code service_id} and {@code merchant_user_id}, so
     *                   until the binding carries it there is nothing to put in the
     *                   parameter. Omitted rather than guessed: a wrong
     *                   {@code merchant_id} would point a customer's payment at
     *                   another merchant's account
     * @param cardType   {@code uzcard} or {@code humo}, or null to let the customer
     *                   choose
     */
    public static String build(String merchantId, String serviceId, String merchantUserId,
            String transactionParam, SomAmount amount, String returnUrl, String cardType) {

        Map<String, String> parameters = new LinkedHashMap<>();
        putIfPresent(parameters, "merchant_id", merchantId);
        parameters.put("service_id", serviceId);
        parameters.put("transaction_param", transactionParam);
        parameters.put("amount", format(amount));
        putIfPresent(parameters, "merchant_user_id", merchantUserId);
        putIfPresent(parameters, "return_url", returnUrl);
        putIfPresent(parameters, "card_type", cardType);

        StringBuilder url = new StringBuilder(BASE).append('?');
        boolean first = true;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (!first) {
                url.append('&');
            }
            url.append(parameter.getKey()).append('=')
                    .append(URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return url.toString();
    }

    /**
     * Click's {@code N.NN}.
     *
     * <p>Built from the whole-som integer with two zero decimals appended by
     * {@code BigDecimal} scaling, never by dividing a minor-unit figure by 100.0 —
     * a double divide is how a 1,000,000 som order becomes 999999.99.
     */
    static String format(SomAmount amount) {
        return BigDecimal.valueOf(amount.value()).setScale(2).toPlainString();
    }

    private static void putIfPresent(Map<String, String> parameters, String name, String value) {
        if (value != null && !value.isBlank()) {
            parameters.put(name, value);
        }
    }
}
