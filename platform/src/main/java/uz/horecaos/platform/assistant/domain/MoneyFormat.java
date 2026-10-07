package uz.horecaos.platform.assistant.domain;

import java.util.Map;
import java.util.Optional;

/**
 * An amount of money as a customer reads it in a chat message.
 *
 * <p>Integer minor units in, text out, and the platform's own exponent per
 * currency -- ADR 0018's whole som, not ISO 4217's tiyin -- exactly the decision
 * {@code notifications.domain.MoneyText} records and for the same reason: scaling
 * a som figure as though it were tiyin once told a customer who owed 75 000 som
 * that their order came to 750.00.
 *
 * <p><strong>A currency the platform has decided nothing about renders as
 * nothing.</strong> {@code MoneyText} falls back to an unscaled integer, which is
 * visibly odd in a notification. A chat answer has no such luxury -- it would
 * simply state a wrong number fluently -- so the answer here is {@link
 * Optional#empty()} and the caller retrieves no price fact, which the assistant
 * then refuses to quote.
 */
public final class MoneyFormat {

    private static final Map<String, Integer> PLATFORM_EXPONENT = Map.of("UZS", 0);

    private static final Map<String, Map<String, String>> SUFFIX =
            Map.of("UZS", Map.of("ru", "сум", "uz", "so'm", "en", "UZS"));

    private MoneyFormat() {}

    /**
     * @return e.g. {@code "45 000 сум"}, grouped in threes with a plain space
     *         (U+0020) so a reply that copies it is checked against the same text
     */
    public static Optional<String> format(long amountMinor, String currency, String locale) {
        Integer exponent = PLATFORM_EXPONENT.get(currency);
        Map<String, String> suffixes = SUFFIX.get(currency);
        if (exponent == null || suffixes == null || amountMinor < 0 || exponent != 0) {
            return Optional.empty();
        }
        String suffix = suffixes.getOrDefault(locale, suffixes.get("en"));
        return Optional.of(group(amountMinor) + " " + suffix);
    }

    /** Whether the platform has decided how this currency's minor units read. */
    public static boolean supports(String currency) {
        return PLATFORM_EXPONENT.containsKey(currency) && SUFFIX.containsKey(currency);
    }

    static String group(long value) {
        String digits = Long.toString(value);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append(' ');
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }
}
