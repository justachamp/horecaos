package uz.horecaos.platform.tenancy.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One locale's own localized content for a branch (row 10.2b): the name and
 * description a storefront would show a guest in their own language.
 *
 * <p>Distinct from {@link Location#displayName()}: that field is the
 * operational name every internal screen, report and export already uses, in
 * no particular language — the same role {@code Brand#displayName} plays for
 * a brand. This is customer-facing content, one row per supported locale, the
 * same shape {@link BrandProfile.BrandLocale} already establishes for a
 * brand's own per-language description — widened here to also carry a
 * localized display name, since a branch's public name legitimately differs
 * from its operational one (a mall unit's own naming for the same branch, for
 * example).
 *
 * <p>Locale codes are drawn from {@link BrandProfile#KNOWN_LOCALES}, the same
 * closed set a brand's own storefront locales are drawn from — there is no
 * separate registry for a branch to support a language its brand does not.
 */
public record LocationLocale(
        String locale,
        @Nullable String displayName,
        @Nullable String description) {

    public LocationLocale {
        Objects.requireNonNull(locale, "Locale code is required");
        if (!BrandProfile.KNOWN_LOCALES.contains(locale)) {
            throw new IllegalArgumentException(
                    "Unsupported locale '" + locale + "'; must be one of " + BrandProfile.KNOWN_LOCALES);
        }
        displayName = displayName == null || displayName.isBlank() ? null : displayName.strip();
        description = description == null || description.isBlank() ? null : description.strip();
    }
}
