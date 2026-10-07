package uz.horecaos.platform.configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Matching what a customer typed against what a tenant authored, across the three
 * scripts this platform's customers actually write in (ADR 0069, and the menu
 * search it needs).
 *
 * <p>A customer asks "plov bormi" in Latin Uzbek about a dish the menu names
 * "Плов" in Russian, or types "shashlik" for "Шашлык". A search that compared
 * those strings would answer "no such dish" about the most ordinary question the
 * bot receives, so both sides are first reduced to the same lossy Latin
 * <em>skeleton</em>: Cyrillic transliterated, apostrophes and diacritics dropped,
 * and the handful of Latin spellings that stand for one Cyrillic letter folded
 * together ({@code x}/{@code h}, {@code q}/{@code k}, {@code w}/{@code v}).
 *
 * <p><strong>The skeleton is for matching, never for display.</strong> It is
 * deliberately ugly, collapses distinct words, and is never shown to a customer
 * or written anywhere; the words a customer is shown are always the tenant's own.
 *
 * <p>Pure functions over strings, no state, no locale dependence: the same input
 * gives the same skeleton on every replica, which is what lets two of them agree
 * on a cache key built from it.
 */
public final class SearchText {

    private static final Map<Character, String> TRANSLITERATION = Map.ofEntries(
            Map.entry('а', "a"),
            Map.entry('б', "b"),
            Map.entry('в', "v"),
            Map.entry('г', "g"),
            Map.entry('д', "d"),
            Map.entry('е', "e"),
            Map.entry('ё', "e"),
            Map.entry('ж', "j"),
            Map.entry('з', "z"),
            Map.entry('и', "i"),
            Map.entry('й', "i"),
            Map.entry('к', "k"),
            Map.entry('л', "l"),
            Map.entry('м', "m"),
            Map.entry('н', "n"),
            Map.entry('о', "o"),
            Map.entry('п', "p"),
            Map.entry('р', "r"),
            Map.entry('с', "s"),
            Map.entry('т', "t"),
            Map.entry('у', "u"),
            Map.entry('ф', "f"),
            Map.entry('х', "h"),
            Map.entry('ц', "ts"),
            Map.entry('ч', "ch"),
            Map.entry('ш', "sh"),
            Map.entry('щ', "sh"),
            Map.entry('ъ', ""),
            Map.entry('ы', "i"),
            Map.entry('ь', ""),
            Map.entry('э', "e"),
            Map.entry('ю', "iu"),
            Map.entry('я', "ia"),
            // Uzbek Cyrillic
            Map.entry('ў', "u"),
            Map.entry('қ', "k"),
            Map.entry('ғ', "g"),
            Map.entry('ҳ', "h"),
            // Latin spellings that stand for the same sound as another Latin letter
            Map.entry('x', "h"),
            Map.entry('q', "k"),
            Map.entry('w', "v"),
            Map.entry('y', "i"));

    /** Everything that is an apostrophe in one keyboard layout or another. */
    private static final Set<Character> APOSTROPHES = Set.of('\'', '‘', '’', 'ʻ', 'ʼ', '`', '´');

    private SearchText() {}

    /**
     * The matching skeleton of a text: lower case, transliterated, apostrophes
     * dropped, anything that is not a letter or a digit a single space.
     */
    public static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        boolean pendingSpace = false;
        for (char raw : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (APOSTROPHES.contains(raw)) {
                continue;
            }
            String mapped = TRANSLITERATION.get(raw);
            if (mapped != null) {
                if (pendingSpace && out.length() > 0) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.append(mapped);
            } else if (Character.isLetterOrDigit(raw)) {
                if (pendingSpace && out.length() > 0) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.append(raw);
            } else {
                pendingSpace = true;
            }
        }
        // "zh" is how Latin spells the one letter ж is transliterated to above.
        return out.toString().replace("zh", "j");
    }

    /** The skeleton's words, in order. */
    public static List<String> tokens(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return List.of(normalized.split(" "));
    }

    /**
     * Whether one word the customer typed names one word of a candidate: the same
     * skeleton, a word that starts with it ("plov" for "plovlar"), or -- for a
     * word of five letters or more -- one slip of the finger away.
     *
     * <p>Both arguments are already skeleton words. Three letters is the shortest
     * word allowed to match by prefix, because a two-letter prefix matches half of
     * any menu and a search that answers with half the menu has answered nothing.
     */
    public static boolean wordMatches(String term, String candidate) {
        if (term.equals(candidate)) {
            return true;
        }
        if (term.length() >= 3 && candidate.startsWith(term)) {
            return true;
        }
        if (candidate.length() >= 4 && term.startsWith(candidate) && term.length() - candidate.length() <= 2) {
            return true;
        }
        return term.length() >= 5 && candidate.length() >= 5 && withinOneEdit(term, candidate);
    }

    /** Whether every term names some word of {@code text}. An empty term list names nothing. */
    public static boolean containsAll(List<String> terms, String text) {
        if (terms.isEmpty()) {
            return false;
        }
        List<String> words = tokens(text);
        for (String term : terms) {
            String skeleton = normalize(term);
            if (skeleton.isEmpty() || words.stream().noneMatch(word -> wordMatches(skeleton, word))) {
                return false;
            }
        }
        return true;
    }

    /**
     * How many of {@code terms} name some word of {@code text}. The knowledge
     * search ranks by this; a menu search requires all of them.
     */
    public static int countMatches(List<String> terms, String text) {
        List<String> words = tokens(text);
        int matches = 0;
        for (String term : terms) {
            String skeleton = normalize(term);
            if (!skeleton.isEmpty() && words.stream().anyMatch(word -> wordMatches(skeleton, word))) {
                matches++;
            }
        }
        return matches;
    }

    /**
     * Whether {@code text} contains the phrase as consecutive words, each text word
     * starting with the corresponding phrase word -- how a lexicon entry written
     * as a stem ("zhalob") matches every inflection of it.
     */
    public static boolean containsPhrase(String text, String phrase) {
        List<String> words = tokens(text);
        List<String> wanted = tokens(phrase);
        if (wanted.isEmpty() || words.size() < wanted.size()) {
            return false;
        }
        for (int start = 0; start + wanted.size() <= words.size(); start++) {
            boolean all = true;
            for (int offset = 0; offset < wanted.size(); offset++) {
                String word = words.get(start + offset);
                String stem = wanted.get(offset);
                boolean matches = stem.length() >= 4 ? word.startsWith(stem) : word.equals(stem);
                if (!matches) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /** The words of a skeleton with the ones that carry no meaning of their own removed. */
    public static List<String> without(List<String> words, Set<String> ignorable) {
        List<String> kept = new ArrayList<>();
        for (String word : words) {
            if (!ignorable.contains(word)) {
                kept.add(word);
            }
        }
        return kept;
    }

    private static boolean withinOneEdit(String a, String b) {
        int lengthDifference = a.length() - b.length();
        if (Math.abs(lengthDifference) > 1) {
            return false;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        int i = 0;
        int j = 0;
        boolean edited = false;
        while (i < shorter.length() && j < longer.length()) {
            if (shorter.charAt(i) == longer.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (edited) {
                return false;
            }
            edited = true;
            if (shorter.length() == longer.length()) {
                i++;
            }
            j++;
        }
        return true;
    }
}
