package uz.qoida.platform.marketing.domain;

/**
 * How many segments one body actually costs (ADR 0044).
 *
 * <p>The failure this exists to prevent is stated in the ADR and is not a rounding
 * error. Uzbek marketing copy is trilingual. Latin script encodes as GSM-7 at 153
 * characters per concatenated segment; the same message in Cyrillic falls to UCS-2
 * at 67. A two-hundred-character body is two segments in uz-Latn and three in ru.
 * An estimator that counts recipients rather than segments per recipient locale is
 * wrong by more than a factor of two, and whoever discovers that is reading the
 * Eskiz invoice.
 *
 * <p>The encoding is derived from the text rather than from the locale tag. A
 * tenant who writes their Russian template in Latin transliteration pays the
 * Latin price, and a tenant who puts one Cyrillic character in an otherwise Latin
 * body pays the Cyrillic price for the whole message — which is exactly what the
 * gateway will charge, because a single non-GSM character forces the entire body
 * to UCS-2.
 */
public final class SmsSegments {

    /** GSM 03.38 basic characters, as one string for a membership test. */
    private static final String GSM_BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    /**
     * Characters that exist in GSM-7 only as an escape sequence, and therefore
     * cost two septets each. Forgetting this makes a body of square brackets look
     * half its real length.
     */
    private static final String GSM_EXTENDED = "^{}\\[~]|€";

    private static final int GSM_SINGLE = 160;
    private static final int GSM_CONCATENATED = 153;
    private static final int UCS2_SINGLE = 70;
    private static final int UCS2_CONCATENATED = 67;

    private SmsSegments() {
    }

    /** Which alphabet the whole body will be sent in. */
    public enum Encoding {
        GSM_7,
        UCS_2
    }

    public static Encoding encodingOf(String body) {
        if (body == null) {
            return Encoding.GSM_7;
        }
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (GSM_BASIC.indexOf(character) < 0 && GSM_EXTENDED.indexOf(character) < 0) {
                return Encoding.UCS_2;
            }
        }
        return Encoding.GSM_7;
    }

    /**
     * The billable segment count for one rendered body.
     *
     * <p>An empty body is one segment rather than zero. A gateway asked to send
     * nothing still sends something and still charges for it, and reporting zero
     * would make an estimate that is exactly wrong in the safe-looking direction.
     */
    public static int segmentsFor(String body) {
        String text = body == null ? "" : body;
        Encoding encoding = encodingOf(text);

        int units = encoding == Encoding.GSM_7 ? septets(text) : units(text);
        int single = encoding == Encoding.GSM_7 ? GSM_SINGLE : UCS2_SINGLE;
        int concatenated = encoding == Encoding.GSM_7 ? GSM_CONCATENATED : UCS2_CONCATENATED;

        if (units <= single) {
            return 1;
        }
        // Ceiling division. The concatenation header takes seven characters out of
        // every part, which is why the per-part budget drops the moment a message
        // needs a second part rather than only for the parts after the first.
        return (units + concatenated - 1) / concatenated;
    }

    /** Septets, counting an escaped character as the two it actually occupies. */
    private static int septets(String body) {
        int total = 0;
        for (int index = 0; index < body.length(); index++) {
            total += GSM_EXTENDED.indexOf(body.charAt(index)) >= 0 ? 2 : 1;
        }
        return total;
    }

    /**
     * UCS-2 code units, which is what the gateway counts.
     *
     * <p>{@code String.length()} rather than {@code codePointCount()} on purpose:
     * an emoji outside the basic plane occupies two UTF-16 units on the wire and
     * is billed as two.
     */
    private static int units(String body) {
        return body.length();
    }
}
