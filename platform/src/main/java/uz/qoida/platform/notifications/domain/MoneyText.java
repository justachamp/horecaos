package uz.qoida.platform.notifications.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Turning integer minor units into something a customer can read.
 *
 * <p>{@link BigDecimal#valueOf(long, int)} rather than any arithmetic on a
 * {@code double}. Money is integer minor units with a currency everywhere in this
 * codebase, and the one place that has to produce a decimal is the one place most
 * likely to reintroduce a float by accident.
 *
 * <p>The scale comes from the platform's own convention and deliberately
 * <strong>not</strong> from ISO 4217. Asking {@code java.util.Currency} looks like
 * the careful choice and is the bug: ISO lists UZS with a two-digit tiyin
 * sub-unit, while this platform stores UZS in whole som — {@code Money} says so
 * outright, and so does the comment on
 * {@code payments.payment_intents.requested_amount_minor}. Scaling a som figure as
 * though it were tiyin told a customer who owed 75 000 som that their order came
 * to 750.00, in the body of the message they actually received.
 *
 * <p>A currency the platform has made no decision about renders unscaled. That is
 * visibly odd — a large integer where a decimal was expected — rather than quietly
 * wrong by a factor of a hundred, and quietly wrong is the failure that reaches a
 * customer without anybody noticing. Adding a currency here is a deliberate act,
 * which is the point: the exponent is a platform decision, not a lookup.
 *
 * <p>Grouping separators and symbol placement are deliberately absent. Those are
 * locale presentation decisions that belong in the template a tenant writes, not
 * in a helper that would impose one country's convention on three languages.
 */
public final class MoneyText {

    /**
     * How many decimal places each currency the platform actually handles is
     * stored with. ADR 0018's exponent, not ISO 4217's.
     */
    private static final Map<String, Integer> PLATFORM_EXPONENT = Map.of("UZS", 0);

    private MoneyText() {
    }

    public static String format(long minor, String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            return Long.toString(minor);
        }
        Integer exponent = PLATFORM_EXPONENT.get(currencyCode.toUpperCase(java.util.Locale.ROOT));
        if (exponent == null || exponent == 0) {
            return Long.toString(minor);
        }
        return BigDecimal.valueOf(minor, exponent).toPlainString();
    }
}
