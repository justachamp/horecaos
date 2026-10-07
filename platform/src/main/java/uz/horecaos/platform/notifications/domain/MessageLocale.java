package uz.horecaos.platform.notifications.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import uz.horecaos.platform.tenancy.api.PlatformLocale;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Tier;
import uz.horecaos.platform.tenancy.api.PlatformLocales;

/**
 * A language HorecaOS sends in (ADR 0035, ADR 0020, ADR 0149): a handle on an entry of the
 * platform's one registry, live in the {@link Tier#MESSAGES messages tier}.
 *
 * <p>It used to be an enum of its own, a closed three-locale set beside a dozen others. It is
 * now a thin value over {@link PlatformLocales}, so the set of languages it can hold is the
 * registry's and nothing here lists them; activating a fourth language is a change to the
 * registry's entry, not to this class. The named constants are handles on the three live
 * languages that call sites already spell out, not a list: they look the entry up and add nothing.
 *
 * <p>What "required" means changed with ADR 0149. A template version must have a wording in
 * every locale <em>the template's brand serves</em> before it can be activated
 * ({@code NotificationTemplateService}), not in every language the platform has: the rule exists
 * to keep a customer from getting nothing, and a brand-scoped rule prevents that just as well
 * without making a Tashkent restaurant author Georgian.
 */
public final class MessageLocale {

    public static final MessageLocale RU = handle("ru");
    public static final MessageLocale UZ_LATN = handle("uz-Latn");
    public static final MessageLocale EN = handle("en");

    /** The default when a customer has expressed no preference: the registry's fallback language. */
    public static final MessageLocale FALLBACK = new MessageLocale(PlatformLocales.fallback());

    private final PlatformLocale entry;

    private MessageLocale(PlatformLocale entry) {
        this.entry = Objects.requireNonNull(entry);
    }

    private static MessageLocale handle(String tag) {
        return new MessageLocale(PlatformLocales.byTag(tag).orElseThrow());
    }

    public String tag() {
        return entry.tag();
    }

    /** The registry entry behind this handle. */
    public PlatformLocale entry() {
        return entry;
    }

    /** Every language live in the messages tier, in the registry's fallback order. */
    public static List<MessageLocale> all() {
        return PlatformLocales.active(Tier.MESSAGES).stream()
                .map(MessageLocale::new)
                .toList();
    }

    /**
     * Every language the platform can send in. The set a <em>brand</em> must have wordings for is
     * this one narrowed to the brand's own choice ({@code NotificationTemplateService#requiredLocales}).
     */
    public static List<MessageLocale> required() {
        return all();
    }

    /**
     * Parses a stored or requested tag, or a registered alias of one.
     *
     * <p>Case-insensitive, because {@code uz-latn} and {@code uz-Latn} are the same language and a
     * customer profile written by a different client should not silently fall back to Russian; and
     * alias-aware, because the storefront writes a customer's language as the bare {@code uz} it has
     * always sent, which the registry reads as {@code uz-Latn} (ADR 0149, Decision 1).
     *
     * @return empty for anything outside the messages tier, which the caller resolves to
     *         {@link #FALLBACK} rather than treating as an error
     */
    public static Optional<MessageLocale> parse(String tag) {
        return PlatformLocales.parseActive(tag, Tier.MESSAGES).map(MessageLocale::new);
    }

    public static MessageLocale of(String tag) {
        return parse(tag)
                .orElseThrow(() -> new IllegalArgumentException("%s is not one of the supported locales %s"
                        .formatted(tag, PlatformLocales.describe(Tier.MESSAGES))));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MessageLocale that && entry.tag().equals(that.entry.tag());
    }

    @Override
    public int hashCode() {
        return entry.tag().hashCode();
    }

    @Override
    public String toString() {
        return entry.tag();
    }
}
