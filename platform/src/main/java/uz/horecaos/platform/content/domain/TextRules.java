package uz.horecaos.platform.content.domain;

import java.text.Normalizer;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.PlatformLocale;
import uz.horecaos.platform.tenancy.api.PlatformLocales;

/**
 * What a piece of text may contain (ADR 0157, the typed documents).
 *
 * <p>Text is a plain string. Markup is not representable, so it is not sanitised, it is simply not
 * a thing the grammar can say; what is refused here is the small set of characters that change how
 * plain text <em>reads</em> without being visible: C0 and C1 controls (other than a newline where
 * a newline belongs), the line and paragraph separators {@code U+2028}/{@code U+2029}, and the
 * bidirectional override and isolate controls ({@code U+202A}-{@code U+202E},
 * {@code U+2066}-{@code U+2069}), which spoof text direction -- the trick that makes one string
 * display as another.
 */
public final class TextRules {

    private TextRules() {}

    /**
     * Whether this text carries a character it may not.
     *
     * @param allowNewline true where a line break belongs (a block's text, a caption); false for a
     *                     title, a label and anything that is one line
     */
    public static boolean hasForbiddenCharacter(String text, boolean allowNewline) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                if (!allowNewline) {
                    return true;
                }
                continue;
            }
            if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
                return true;
            }
            if (c == 0x2028 || c == 0x2029) {
                return true;
            }
            if ((c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)) {
                return true;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** NFC without stripping: a run of body text keeps the space that separates it from the next one. */
    public static String nfc(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    /** NFC, stripped: two ways of typing the same word are one word, and hash alike. */
    public static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).strip();
    }

    /** Length in code points, which is what an author sees as characters. */
    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /**
     * The stored tag of a language an author named, or empty when it is not a language the content
     * tier speaks. A bare {@code uz} is accepted at the API boundary and read as {@code uz-Latn}; it
     * is never stored (ADR 0149).
     */
    public static Optional<String> contentLocale(@Nullable String input) {
        return PlatformLocales.parseActive(input, PlatformLocale.Tier.CONTENT).map(PlatformLocale::tag);
    }
}
