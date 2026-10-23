package uz.horecaos.platform.customers.api;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The sentence a customer reads with their one-time code, in the language they chose (ADR 0015,
 * ADR 0020; ADR 0184, D19).
 *
 * <p><strong>The sentence is the platform's, and a tenant cannot edit it.</strong> A one-time code is
 * not a message a tenant should be able to edit: a template with a tenant-editable body is a template
 * somebody can shorten until the code is the only thing left, or lengthen past a segment. It is also
 * sent by the customers module, which cannot depend on {@code notifications} (that direction is already
 * taken), so it never went through the template machinery and never could have. D19 records that as a
 * decision and not as an accident: Tab 1 of the notifications screen shows these sentences read-only
 * ({@code GET .../notification-templates/otp-wording}), and a template of the {@code SECURITY} class
 * cannot be created, because one would be wording nothing ever sends.
 *
 * <p>The text lives here, in the module that sends it, and the console reads it from here, so the screen
 * shows the sentence that is actually sent and not a copy that could drift from it. {@code
 * VerificationCodeText} in the SMS adapter renders through this class.
 *
 * <p><strong>Every variant is ASCII and under 70 characters.</strong> Both limits are deliberate. A
 * non-GSM-7 character forces the whole message into UCS-2, where a segment is 70 characters rather than
 * 160, and the provider charges and reports {@code parts} per segment, so a Cyrillic apostrophe in the
 * Russian variant would silently double the cost of every code this platform sends. The Russian variant
 * is written in the Latin alphabet for the same reason, and so is the Uzbek one, which is also the
 * alphabet the market's phones default to.
 *
 * <p>The validity is passed in rather than assumed, so the minutes the customer reads are the minutes
 * the challenge row will actually honour.
 */
public final class OtpWording {

    /** The placeholder for the code itself. */
    public static final String CODE = "{{code}}";

    /** The placeholder for the minutes the code stays valid. */
    public static final String MINUTES = "{{minutes}}";

    private OtpWording() {}

    /** One language's sentence, with its two placeholders still in it. */
    public record Sentence(String locale, String pattern) {}

    private static final String RU =
            "HorecaOS: kod {{code}}. Deystvitelen {{minutes}} min. Nikomu ego ne soobshchayte.";
    private static final String EN = "HorecaOS: your code is {{code}}. Valid for {{minutes}} min. Do not share it.";
    // Uzbek is the default rather than English: it is the market's language, and a customer with no
    // stored preference is far likelier to read it than to read ours.
    private static final String UZ = "HorecaOS: kod {{code}}. {{minutes}} daqiqa amal qiladi. Hech kimga aytmang.";

    /** Every sentence the platform sends a code with, in the order the console lists them. */
    public static List<Sentence> sentences() {
        return List.of(new Sentence("ru", RU), new Sentence("uz-Latn", UZ), new Sentence("en", EN));
    }

    /**
     * The sentence for a customer's locale, with the code and the validity filled in.
     *
     * @param locale the customer's stored language, or null for none; any tag whose language subtag is
     *               {@code ru} or {@code en} reads that language, and everything else reads Uzbek
     */
    public static String render(String code, Duration validFor, @Nullable String locale) {
        long minutes = Math.max(1, validFor.toMinutes());
        String pattern =
                switch (language(locale)) {
                    case "ru" -> RU;
                    case "en" -> EN;
                    default -> UZ;
                };
        return pattern.replace(CODE, code).replace(MINUTES, Long.toString(minutes));
    }

    /**
     * The language subtag only.
     *
     * <p>{@code ru-RU} and {@code ru} must render the same sentence, and a locale arrives from a
     * customer profile rather than from a fixed list.
     */
    private static String language(@Nullable String locale) {
        if (locale == null || locale.isBlank()) {
            return "";
        }
        return Locale.forLanguageTag(locale.replace('_', '-')).getLanguage();
    }
}
