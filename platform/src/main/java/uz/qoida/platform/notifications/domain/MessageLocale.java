package uz.qoida.platform.notifications.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The languages Qoida sends in (ADR 0035, ADR 0020).
 *
 * <p>A closed set rather than a {@link Locale}. Every template version must exist
 * in all three before it can be activated, and a set that is open at the edges
 * cannot express "all three" — a tenant would author {@code ru} and {@code en},
 * activate, and the first Uzbek-reading customer would get a message in a language
 * they did not choose or no message at all.
 */
public enum MessageLocale {

    RU("ru"),
    UZ_LATN("uz-Latn"),
    EN("en");

    /** The default when a customer has expressed no preference. See {@code qoida.notifications.default-locale}. */
    public static final MessageLocale FALLBACK = RU;

    private final String tag;

    MessageLocale(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    public static List<MessageLocale> required() {
        return List.of(values());
    }

    /**
     * Parses a stored or requested tag.
     *
     * <p>Case-insensitive on the tag, because {@code uz-latn} and {@code uz-Latn}
     * are the same language and a customer profile written by a different client
     * should not silently fall back to Russian.
     *
     * @return empty for anything outside the set, which the caller resolves to
     *         {@link #FALLBACK} rather than treating as an error
     */
    public static Optional<MessageLocale> parse(String tag) {
        if (tag == null || tag.isBlank()) {
            return Optional.empty();
        }
        String normalized = tag.strip();
        for (MessageLocale locale : values()) {
            if (locale.tag.equalsIgnoreCase(normalized)) {
                return Optional.of(locale);
            }
        }
        return Optional.empty();
    }

    public static MessageLocale of(String tag) {
        return parse(tag).orElseThrow(() -> new IllegalArgumentException(
                "%s is not one of the supported locales %s".formatted(tag, required())));
    }
}
