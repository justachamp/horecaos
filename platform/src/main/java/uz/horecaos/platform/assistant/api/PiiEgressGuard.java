package uz.horecaos.platform.assistant.api;

import java.util.regex.Pattern;

/**
 * What may leave for the model provider, enforced as code (ADR 0069: "the
 * provider receives the customer's question, the retrieved facts, and
 * pseudonymous identifiers -- never a name, phone number, or address ... it is
 * enforced by a test rather than by care").
 *
 * <p>ADR 0029 applied to a new egress: the model provider is a processor the
 * tenant did not choose, so nothing that identifies a person goes to it. This
 * class is the one place that decides what that means for free text the
 * customer typed, and {@link AssistantModelRequest} refuses to be built with text
 * this class would still change -- so a caller that forgot to redact fails at
 * construction rather than at the provider.
 *
 * <p><strong>What it can and cannot see.</strong> It removes what has a shape: a
 * phone or card number (nine or more digits, however separated), an email
 * address, a Telegram handle, and a street address written with a marker word
 * and a number ("ул. Навои 12", "dom 5", "mahalla 3"). It cannot remove a name
 * or a landmark a customer types in a sentence -- nothing can, reliably -- and
 * this class does not pretend to. That residual is the customer's own question,
 * which the ADR sends to the processor by design; the first reply of every
 * conversation says so and asks the customer not to include personal details
 * ({@code CustomerWording#disclosure}). Retrieved facts are the tenant's own
 * published content and are not passed through this class: a branch's published
 * telephone number is the tenant's, not a person's.
 *
 * <p>Idempotent: redacting redacted text changes nothing, which is what makes
 * "refuse to build a request from text {@link #redact} would change" a sound test
 * of "this text is clean".
 */
public final class PiiEgressGuard {

    static final String PHONE = "[phone]";
    static final String EMAIL = "[email]";
    static final String HANDLE = "[handle]";
    static final String ADDRESS = "[address]";

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+");

    /** Nine or more digits, with the separators people type between groups of them. */
    private static final Pattern LONG_NUMBER = Pattern.compile("\\+?\\d(?:[\\s().\\-]{0,2}\\d){8,}");

    private static final Pattern HANDLE_PATTERN = Pattern.compile("(?<![\\p{L}\\p{N}_])@[A-Za-z][A-Za-z0-9_]{4,31}");

    /**
     * A marker word that introduces an address, then up to two words, then a token
     * carrying a digit. The digit is what keeps "street food" and "дом" in a
     * sentence about a house special from being mistaken for an address, and the
     * lookahead after the marker keeps "домашний" from being read as "дом".
     */
    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:улица|ул|проспект|пр-т|переулок|пер|дом|д|квартира|кв|подъезд|этаж|"
                    + "mahalla|mfy|kocha|ko['\\u2018\\u2019\\u02BB\\u02BC`]?cha|uy|xonadon|"
                    + "street|st|avenue|ave|apartment|apt|flat|floor|block)(?![\\p{L}\\p{N}])"
                    + "\\.?\\s*(?:\\u2116|#|no\\.?)?\\s*(?:[\\p{L}\\-]+\\s+){0,2}[\\p{L}\\p{N}\\-/]*\\d[\\p{L}\\p{N}\\-/]*");

    /**
     * A street named before its marker, the English and Uzbek order ("Amir Temur
     * street 7", "Navoiy ko'chasi 12"): one to three capitalised words, the marker,
     * and a number. The capitals and the number are what keep "the street food
     * stall" and "a short street" out.
     */
    private static final Pattern NAMED_STREET_PATTERN = Pattern.compile(
            "(?u)(?:\\p{Lu}[\\p{L}\\-]*\\s+){1,3}(?i:street|st|avenue|ave|road|rd|ko['\\u2018\\u2019\\u02BB\\u02BC`]?cha\\p{L}*|mahalla\\p{L}*)"
                    + "(?![\\p{L}\\p{N}])\\.?\\s*(?:\\u2116|#|no\\.?)?\\s*\\d[\\p{L}\\p{N}\\-/]*");

    private PiiEgressGuard() {}

    /** The text with every shape this class recognises replaced by a neutral placeholder. */
    public static String redact(String text) {
        String redacted = EMAIL_PATTERN.matcher(text).replaceAll(EMAIL);
        redacted = HANDLE_PATTERN.matcher(redacted).replaceAll(HANDLE);
        redacted = NAMED_STREET_PATTERN.matcher(redacted).replaceAll(ADDRESS);
        redacted = ADDRESS_PATTERN.matcher(redacted).replaceAll(ADDRESS);
        redacted = LONG_NUMBER.matcher(redacted).replaceAll(PHONE);
        return redacted;
    }

    /** Whether {@link #redact} would change this text. */
    public static boolean containsPersonalData(String text) {
        return !redact(text).equals(text);
    }

    /**
     * @throws IllegalArgumentException when the text still carries a recognisable
     *         personal value; the message never contains the text
     */
    public static String requireClean(String text, String what) {
        if (containsPersonalData(text)) {
            throw new IllegalArgumentException(
                    what + " carries a personal value and may not be sent to a model provider");
        }
        return text;
    }
}
