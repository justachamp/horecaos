package uz.horecaos.platform.assistant.domain;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.configuration.SearchText;

/**
 * Which language to answer in: Russian, Uzbek (Latin script) or English -- the
 * three the platform's customers write in.
 *
 * <p>The question's own language wins over a stored preference, because a
 * customer who has just written in English wants an English answer whatever
 * their account says. Detection is script first (Cyrillic is Russian unless it
 * carries a letter only Uzbek Cyrillic has), then, for Latin, a count of words
 * that are unmistakably Uzbek against words that are unmistakably English; a
 * message with neither is answered in the fallback, which the caller takes from
 * the customer's own preference, then the brand's default.
 */
public final class ReplyLocale {

    public static final List<String> SUPPORTED = List.of("ru", "uz", "en");

    private static final Set<Character> UZBEK_CYRILLIC_ONLY = Set.of('ў', 'қ', 'ғ', 'ҳ');

    private static final Set<String> UZBEK_WORDS = skeletons(
            "salom",
            "assalomu",
            "alaykum",
            "bormi",
            "qancha",
            "necha",
            "narx",
            "narxi",
            "kerak",
            "menga",
            "yoq",
            "rahmat",
            "iltimos",
            "bugun",
            "buyurtma",
            "buyurtmam",
            "manzil",
            "qayerda",
            "qachon",
            "ochiq",
            "yopiq",
            "filial",
            "yetkazib",
            "sizda",
            "nima",
            "bilan",
            "uchun",
            "bormikan",
            "qanday",
            "ishlaysiz",
            "kechirasiz");

    private static final Set<String> ENGLISH_WORDS = skeletons(
            "the", "you", "have", "how", "much", "what", "where", "is", "are", "do", "does", "open", "price", "your",
            "please", "thanks", "hello", "hi", "can", "order", "my", "when", "any", "today");

    private ReplyLocale() {}

    /** The language of this message, or {@code fallback} when the message does not say. */
    public static String detect(String text, String fallback) {
        int cyrillic = 0;
        int latin = 0;
        boolean uzbekCyrillic = false;
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (UZBEK_CYRILLIC_ONLY.contains(c)) {
                uzbekCyrillic = true;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(c);
            if (script == Character.UnicodeScript.CYRILLIC) {
                cyrillic++;
            } else if (script == Character.UnicodeScript.LATIN) {
                latin++;
            }
        }
        if (cyrillic > latin) {
            return uzbekCyrillic ? "uz" : "ru";
        }
        if (latin == 0) {
            return normalise(fallback);
        }
        int uzbekHits = 0;
        int englishHits = 0;
        for (String word : SearchText.tokens(text)) {
            if (UZBEK_WORDS.contains(word)) {
                uzbekHits++;
            }
            if (ENGLISH_WORDS.contains(word)) {
                englishHits++;
            }
        }
        if (uzbekHits > englishHits) {
            return "uz";
        }
        if (englishHits > 0) {
            return "en";
        }
        return normalise(fallback);
    }

    /**
     * A stored preference or brand default -- {@code ru}, {@code uz-Latn}, {@code
     * en-GB}, anything -- reduced to one of the three supported languages, or
     * {@code fallback} when it names none.
     */
    public static String fromPreference(@Nullable String tag, String fallback) {
        if (tag == null || tag.isBlank()) {
            return normalise(fallback);
        }
        String primary = tag.strip().toLowerCase(Locale.ROOT);
        for (String supported : SUPPORTED) {
            if (primary.equals(supported)
                    || primary.startsWith(supported + "-")
                    || primary.startsWith(supported + "_")) {
                return supported;
            }
        }
        return normalise(fallback);
    }

    /** The supported language a code stands for, Russian when it stands for none. */
    public static String normalise(String code) {
        return SUPPORTED.contains(code) ? code : "ru";
    }

    private static Set<String> skeletons(String... words) {
        Set<String> out = new java.util.HashSet<>();
        for (String word : words) {
            out.addAll(SearchText.tokens(word));
        }
        return Set.copyOf(out);
    }
}
