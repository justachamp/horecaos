package uz.horecaos.platform.integration.camel.sms;

import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The sentence a customer reads, in the language they chose.
 *
 * <p>Rendered here rather than through the ADR 0020 template machinery, because
 * customers cannot depend on notifications — that direction is already taken and
 * reversing it would make the two modules cyclic — and because a one-time code is
 * not a message a tenant should be able to edit. A template with a
 * tenant-editable body is a template somebody can accidentally shorten to the
 * point where the code is the only thing left, or lengthen past a segment.
 *
 * <p><strong>Every variant is ASCII and under 70 characters.</strong> Both limits
 * are deliberate. A non-GSM-7 character forces the whole message into UCS-2,
 * where a segment is 70 characters rather than 160, and the provider charges and
 * reports {@code parts} per segment — so a Cyrillic apostrophe in the Russian
 * variant would silently double the cost of every code this platform sends. The
 * Uzbek variant is written in the Latin alphabet for the same reason, which is
 * also the alphabet the market's phones default to.
 *
 * <p>The validity is passed in rather than assumed, so the minutes the customer
 * reads are the minutes the challenge row will actually honour.
 */
final class VerificationCodeText {

    private VerificationCodeText() {}

    static String render(String code, Duration validFor, @Nullable String locale) {
        long minutes = Math.max(1, validFor.toMinutes());
        return switch (language(locale)) {
            case "ru" -> "HorecaOS: kod %s. Deystvitelen %d min. Nikomu ego ne soobshchayte.".formatted(code, minutes);
            case "en" -> "HorecaOS: your code is %s. Valid for %d min. Do not share it.".formatted(code, minutes);
            // Uzbek is the default rather than English: it is the market's
            // language, and a customer with no stored preference is far likelier
            // to read it than to read ours.
            default -> "HorecaOS: kod %s. %d daqiqa amal qiladi. Hech kimga aytmang.".formatted(code, minutes);
        };
    }

    /**
     * The language subtag only.
     *
     * <p>{@code ru-RU} and {@code ru} must render the same sentence, and a locale
     * arrives from a customer profile rather than from a fixed list.
     */
    private static String language(@Nullable String locale) {
        if (locale == null || locale.isBlank()) {
            return "";
        }
        return Locale.forLanguageTag(locale.replace('_', '-')).getLanguage();
    }
}
