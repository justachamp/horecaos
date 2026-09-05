package uz.horecaos.platform.legal.domain;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The platform's closed three-locale set for tenant-authored legal content.
 *
 * <p>Matches every other module's own {@code ru}/{@code uz-Latn}/{@code en}
 * vocabulary — see {@code notifications.domain.MessageLocale},
 * {@code ordering.application.OrderOutcomeReasonService.REQUIRED_LOCALES},
 * {@code marketing.domain.AudiencePredicate.SUPPORTED_LOCALES} — declared
 * locally here rather than imported from one of them, by the same established
 * convention: none of those types live in an {@code api} package another
 * module may depend on, and each module that needs this trio declares its
 * own rather than reaching across a boundary for three string literals.
 */
public enum TermsLocale {
    RU("ru"),
    UZ_LATN("uz-Latn"),
    EN("en");

    private final String tag;

    TermsLocale(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }

    /** Case-insensitive, matching {@code MessageLocale.parse}'s reasoning: a client's own casing should not fail. */
    public static Optional<TermsLocale> parse(String tag) {
        if (tag == null || tag.isBlank()) {
            return Optional.empty();
        }
        String normalized = tag.strip();
        for (TermsLocale locale : values()) {
            if (locale.tag.equalsIgnoreCase(normalized)) {
                return Optional.of(locale);
            }
        }
        return Optional.empty();
    }

    /** The exact tags a stored or published locale column may carry. */
    public static Set<String> tags() {
        Set<String> tags = new LinkedHashSet<>();
        for (TermsLocale locale : values()) {
            tags.add(locale.tag);
        }
        return Set.copyOf(tags);
    }
}
