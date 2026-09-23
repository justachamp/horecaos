package uz.horecaos.platform.tenancy.domain.channel;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The platform's closed three-locale set, declared locally here rather than
 * imported from {@code legal.domain.TermsLocale} — the same "none of these
 * types live in an {@code api} package another module may depend on"
 * convention {@code TermsLocale}'s own doc explains, repeated once more
 * rather than adding a cross-module dependency for three string literals.
 */
public enum ChannelPageLocale {
    RU("ru"),
    UZ_LATN("uz-Latn"),
    EN("en");

    private final String tag;

    ChannelPageLocale(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    public static Optional<ChannelPageLocale> parse(String tag) {
        if (tag == null || tag.isBlank()) {
            return Optional.empty();
        }
        String normalized = tag.strip();
        for (ChannelPageLocale locale : values()) {
            if (locale.tag.equalsIgnoreCase(normalized)) {
                return Optional.of(locale);
            }
        }
        return Optional.empty();
    }

    public static Set<String> tags() {
        Set<String> tags = new LinkedHashSet<>();
        for (ChannelPageLocale locale : values()) {
            tags.add(locale.tag);
        }
        return Set.copyOf(tags);
    }
}
