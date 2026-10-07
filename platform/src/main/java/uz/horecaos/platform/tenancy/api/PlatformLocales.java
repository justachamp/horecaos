package uz.horecaos.platform.tenancy.api;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Direction;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Script;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Tier;

/**
 * The one place in code that says which languages exist (ADR 0149).
 *
 * <p>It replaces the enums, constants and {@code CASE} expressions that each module used to
 * keep for itself ({@code BrandProfile.KNOWN_LOCALES}, {@code TenantLocaleSet.PLATFORM_LOCALES},
 * {@code MessageLocale}, {@code TermsLocale}, {@code ChannelPageLocale}, the three
 * all-locales-required validators, the invitation and reset sets, the SQL ordering), each of
 * which was right and none of which was the source of the others. Closed vocabularies live in
 * code on this platform ({@code Capability}, {@code PlatformRole}, {@code DevicePrincipalClass}):
 * a language carries a face and a catalogue, which are code, so adding one is a release and a
 * tenant never makes a language exist; it only chooses, per brand, among the ones that do.
 *
 * <p><strong>No language is activated by the registry's existence.</strong> {@code ru}, {@code
 * uz-Latn} and {@code en} are live in every tier. {@code kk} and {@code ka} are declared with no
 * tier live, so the cost of each is visible here and nothing else changes. Activating one is a
 * change to its entry, a catalogue per client and, for a script beyond Latin and Cyrillic, a face
 * (ADR 0149, Decision 2 and 7), started by a tenant that needs it.
 *
 * <p>The spelling of Uzbek is settled here and nowhere else: the tag is {@code uz-Latn}; a bare
 * {@code uz} is an <em>input alias</em> read at the API boundary and never stored, and the
 * catalog's own {@code uz} is the entry's one named {@code catalogCode}.
 */
public final class PlatformLocales {

    private static final List<PlatformLocale> ALL = List.of(
            new PlatformLocale(
                    "ru",
                    "ru",
                    Set.of(),
                    Script.CYRL,
                    Direction.LTR,
                    0,
                    "ibm-plex-sans",
                    Set.of(Tier.CONTENT, Tier.MESSAGES, Tier.STAFF_UI),
                    Map.of("ru", "Русский", "uz-Latn", "Rus tili", "en", "Russian")),
            new PlatformLocale(
                    "uz-Latn",
                    "uz",
                    Set.of("uz"),
                    Script.LATN,
                    Direction.LTR,
                    1,
                    "ibm-plex-sans",
                    Set.of(Tier.CONTENT, Tier.MESSAGES, Tier.STAFF_UI),
                    Map.of("ru", "Узбекский", "uz-Latn", "Oʻzbekcha", "en", "Uzbek")),
            new PlatformLocale(
                    "en",
                    "en",
                    Set.of(),
                    Script.LATN,
                    Direction.LTR,
                    2,
                    "ibm-plex-sans",
                    Set.of(Tier.CONTENT, Tier.MESSAGES, Tier.STAFF_UI),
                    Map.of("ru", "Английский", "uz-Latn", "Inglizcha", "en", "English")),
            // Declared, not live (ADR 0149): the face for Kazakh waits on a glyph audit of the
            // bundled one against its extra Cyrillic letters; Georgian gets an openly licensed
            // face chosen at activation, loaded only when the locale is active.
            new PlatformLocale(
                    "kk",
                    "kk",
                    Set.of(),
                    Script.CYRL,
                    Direction.LTR,
                    3,
                    "ibm-plex-sans",
                    Set.of(),
                    Map.of("ru", "Казахский", "uz-Latn", "Qozoqcha", "en", "Kazakh", "kk", "Қазақша")),
            new PlatformLocale(
                    "ka",
                    "ka",
                    Set.of(),
                    Script.GEOR,
                    Direction.LTR,
                    4,
                    "georgian-lazy",
                    Set.of(),
                    Map.of("ru", "Грузинский", "uz-Latn", "Gruzincha", "en", "Georgian", "ka", "ქართული")));

    private static final PlatformLocale FALLBACK = ALL.getFirst();

    private PlatformLocales() {}

    /** Every language the platform has declared, live or not, in fallback order. */
    public static List<PlatformLocale> all() {
        return ALL;
    }

    /** The languages live in a tier, in fallback order. */
    public static List<PlatformLocale> active(Tier tier) {
        return ALL.stream().filter(locale -> locale.active(tier)).toList();
    }

    /** The tags of {@link #active}: the closed set a store of that tier accepts. */
    public static List<String> activeTags(Tier tier) {
        return active(tier).stream().map(PlatformLocale::tag).toList();
    }

    /** The language every reader falls back to when a customer or a brand names none: {@code ru}. */
    public static PlatformLocale fallback() {
        return FALLBACK;
    }

    /** The entry whose tag this is, ignoring case ({@code uz-latn} is {@code uz-Latn}). An alias is not a tag. */
    public static Optional<PlatformLocale> byTag(@Nullable String tag) {
        if (tag == null || tag.isBlank()) {
            return Optional.empty();
        }
        String wanted = tag.strip();
        return ALL.stream()
                .filter(locale -> locale.tag().equalsIgnoreCase(wanted))
                .findFirst();
    }

    /**
     * What an API boundary calls to read a language a client sent: the tag, or a registered input
     * alias ({@code uz} for {@code uz-Latn}), ignoring case. The answer is the entry; what is stored
     * is its {@code tag}, never the alias.
     */
    public static Optional<PlatformLocale> parse(@Nullable String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String wanted = input.strip().toLowerCase(Locale.ROOT);
        return ALL.stream()
                .filter(locale -> locale.tag().toLowerCase(Locale.ROOT).equals(wanted)
                        || locale.inputAliases().stream()
                                .anyMatch(
                                        alias -> alias.toLowerCase(Locale.ROOT).equals(wanted)))
                .findFirst();
    }

    /** {@link #parse}, restricted to a language live in this tier. */
    public static Optional<PlatformLocale> parseActive(@Nullable String input, Tier tier) {
        return parse(input).filter(locale -> locale.active(tier));
    }

    /** The entry a {@code catalog.translations} code belongs to ({@code uz} is {@code uz-Latn}'s). */
    public static Optional<PlatformLocale> byCatalogCode(@Nullable String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String wanted = code.strip();
        return ALL.stream()
                .filter(locale -> locale.catalogCode().equals(wanted))
                .findFirst();
    }

    /**
     * The tag of the language an input denotes in this tier, or the fallback's when it denotes none
     * (a missing preference, a language the tier does not speak, a tag nobody has registered).
     * Case-insensitive and alias-aware ({@code uz} reads as {@code uz-Latn}). The answer is always a
     * stored tag, never an alias, so a caller can key a wording table by it.
     */
    public static String resolve(@Nullable String input, Tier tier) {
        return parseActive(input, tier)
                .or(() -> byLanguage(input, tier))
                .orElse(FALLBACK)
                .tag();
    }

    /**
     * A region-qualified tag a client sent ({@code en-US}, {@code uz_UZ}) read as the one live entry
     * of that language, provided the client did not name a script the entry is not written in
     * ({@code uz-Cyrl} is not {@code uz-Latn}: Uzbek has two scripts, which is why the tag carries
     * one). Empty when two entries share the language or none is live. Lenient on purpose and used
     * only where a caller falls back anyway; validation of what gets stored uses {@link #parseActive}.
     */
    private static Optional<PlatformLocale> byLanguage(@Nullable String input, Tier tier) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String[] parts = input.strip().replace('_', '-').split("-");
        List<PlatformLocale> sameLanguage = active(tier).stream()
                .filter(locale -> locale.tag().split("-")[0].equalsIgnoreCase(parts[0]))
                .toList();
        if (sameLanguage.size() != 1) {
            return Optional.empty();
        }
        PlatformLocale candidate = sameLanguage.getFirst();
        boolean namesAnotherScript = parts.length > 1
                && parts[1].length() == 4
                && !parts[1].equalsIgnoreCase(candidate.script().subtag());
        return namesAnotherScript ? Optional.empty() : Optional.of(candidate);
    }

    /** Whether this tag names a language live in the tier. */
    public static boolean isActive(@Nullable String tag, Tier tier) {
        return byTag(tag).filter(locale -> locale.active(tier)).isPresent();
    }

    /**
     * The tags of the active set of a tier as one human sentence fragment ({@code ru, uz-Latn, en}),
     * for a refusal that names what would have been accepted.
     */
    public static String describe(Tier tier) {
        return active(tier).stream().map(PlatformLocale::tag).collect(Collectors.joining(", "));
    }

    /**
     * A SQL ordering expression that ranks a locale column by {@code fallbackRank}: the registry's
     * answer to the {@code CASE t.locale WHEN 'ru' THEN 0 ...} the catalog's reads used to spell by
     * hand. Both the tag and the catalog code of an entry rank alike, so {@code uz} and {@code uz-Latn}
     * are one language here. Built from constants of this class only, never from input.
     *
     * @param column a column reference the caller wrote ({@code t.locale}), not a value
     */
    public static String fallbackOrderSql(String column) {
        if (!column.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
            throw new IllegalArgumentException("A column reference is expected, not " + column);
        }
        StringBuilder sql = new StringBuilder("CASE ").append(column);
        ALL.stream()
                .sorted(Comparator.comparingInt(PlatformLocale::fallbackRank))
                .forEach(locale -> {
                    sql.append(" WHEN '").append(locale.tag()).append("' THEN ").append(locale.fallbackRank());
                    if (!locale.catalogCode().equals(locale.tag())) {
                        sql.append(" WHEN '")
                                .append(locale.catalogCode())
                                .append("' THEN ")
                                .append(locale.fallbackRank());
                    }
                });
        return sql.append(" ELSE ").append(ALL.size()).append(" END").toString();
    }
}
