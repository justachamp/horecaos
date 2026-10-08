package uz.horecaos.platform.tenancy.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One language the platform knows (ADR 0149, Decision 1): an entry of {@link PlatformLocales}.
 *
 * <p>A language is declared once, here, with everything the platform needs to know about it that
 * is not a catalogue of words: its BCP 47 tag (script-tagged where a language has two scripts,
 * so {@code uz-Latn} and never a bare {@code uz}), the script and direction, the face that draws
 * it, the order it falls back in, and <em>which tiers it is live in</em>. A language with no
 * active tier is <strong>declared, not live</strong>: it costs nothing at runtime and makes the
 * price of activating it visible in one place.
 *
 * @param tag          the BCP 47 tag every store that follows ADR 0035 holds
 * @param catalogCode  what {@code catalog.translations}, the published menu snapshots,
 *                     {@code horecaos.catalog.default-locale} and the storefront's {@code ?locale=}
 *                     hold. Equal to {@code tag} except for {@code uz-Latn}, whose {@code uz}
 *                     predates the registry and cannot be rewritten (ADR 0149, Decision 3: the
 *                     published snapshots are hashed over their keys)
 * @param inputAliases spellings accepted at the API boundary for this language and never stored:
 *                     {@code uz} for {@code uz-Latn}
 * @param fallbackRank where the language sits when a reader falls back to whatever exists; lower
 *                     first, the fallback language {@code ru} at 0 (replaces the SQL {@code CASE})
 * @param face         the identifier of the face that draws it: bundled for the Latin and Cyrillic
 *                     languages, lazily loaded for a script beyond them (ADR 0149, Decision 7)
 * @param tiers        where the language is live; empty means declared, not live
 * @param names        the language's display name, keyed by the tag of the language it is
 *                     displayed in (including its own): for a picker
 */
public record PlatformLocale(
        String tag,
        String catalogCode,
        Set<String> inputAliases,
        Script script,
        Direction direction,
        int fallbackRank,
        String face,
        Set<Tier> tiers,
        Map<String, String> names) {

    /** The tiers a language can be live in (ADR 0149, Decision 2): what a tenant's brand can choose, what the platform says, what staff read. */
    public enum Tier {
        /** Tenant-authored data and the storefront and mobile catalogues. */
        CONTENT,
        /** Notification wordings, the Telegram bot, owner and staff emails, SMS. */
        MESSAGES,
        /** The operations and control-plane catalogues. */
        STAFF_UI
    }

    public enum Script {
        CYRL("Cyrl"),
        LATN("Latn"),
        GEOR("Geor");

        private final String subtag;

        Script(String subtag) {
            this.subtag = subtag;
        }

        /** The ISO 15924 subtag a BCP 47 tag spells this script with. */
        public String subtag() {
            return subtag;
        }
    }

    /**
     * Every entry says {@code LTR}. No right-to-left language is registered, and registering one
     * needs its own record because it reaches order lines, receipts, fiscal documents and SMS
     * (ADR 0149, Decision 6).
     */
    public enum Direction {
        LTR
    }

    public PlatformLocale {
        Objects.requireNonNull(tag, "A locale has a tag");
        Objects.requireNonNull(catalogCode, "A locale has a catalog code");
        inputAliases = Set.copyOf(inputAliases);
        tiers = tiers.isEmpty() ? Set.of() : Set.copyOf(tiers);
        names = Map.copyOf(names);
    }

    /** Whether the language is live in this tier. */
    public boolean active(Tier tier) {
        return tiers.contains(tier);
    }

    /** Whether the language is live anywhere at all. */
    public boolean live() {
        return !tiers.isEmpty();
    }

    /** The language's name as a reader of {@code displayIn} would see it, falling back to its own name. */
    public String nameIn(String displayIn) {
        return names.getOrDefault(displayIn, ownName());
    }

    /** The language's name in itself: what a picker shows beside the flag a person recognises. */
    public String ownName() {
        return Objects.requireNonNull(names.get(tag), "A locale names itself");
    }
}
