package uz.horecaos.platform.tenancy.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A brand's customer-facing profile: how it presents itself everywhere a
 * customer sees it, regardless of which branch is being ordered from.
 *
 * <p>Deliberately separate from {@link Brand}'s own identity (code, slug,
 * display name, status). That triple is what a receipt, an operator export
 * and onboarding name a brand by, and correcting it is
 * {@code TenantControlPlaneService#reviseBrand}'s job. This is the storefront
 * content behind it — logo, banner, the languages it offers, its contact
 * details — which changes on its own schedule and by a different act.
 *
 * @param logoAssetId a {@code media.assets} row with {@code owner_scope =
 *                     'BRAND'} and this brand's id, verified before it can be
 *                     displayed (ADR 0010) — this record does not itself
 *                     re-check that; see {@code
 *                     uz.horecaos.platform.media.api.MediaAvailability} for
 *                     where that is asked, the same "attach now, verify at
 *                     display" split {@code CatalogAuthoringService.attachMedia}
 *                     already uses
 * @param locales      which languages this brand's storefront offers, each
 *                     with its own description, and which is default. Empty
 *                     means the brand has not configured this yet — not the
 *                     same as "supports nothing" — and every localized field
 *                     in the console falls back to the platform's
 *                     ru/uz-Latn/en triple until it is
 */
public record BrandProfile(
        @Nullable String contactPhone,
        @Nullable String telegramHandle,
        @Nullable UUID logoAssetId,
        @Nullable UUID bannerAssetId,
        List<BrandLocale> locales) {

    /**
     * E.164, the same pattern {@link LocationPlace} enforces for a branch's own
     * phone — one brand-wide number rather than one per branch.
     */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    /** No leading {@code @}; Telegram's own handle rules (5-32 chars, letters/digits/underscore, starts with a letter). */
    private static final Pattern TELEGRAM_HANDLE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{4,31}$");

    /**
     * The closed set of storefront locales this console can author content in
     * today — the same triple {@code marketing.AudiencePredicate
     * .SUPPORTED_LOCALES} already hard-codes. A brand's own set is a subset of
     * this, never wider than it: the console has no editor for a fourth
     * language yet, so accepting one here would record a choice nothing can
     * render.
     */
    public static final List<String> KNOWN_LOCALES = List.of("ru", "uz-Latn", "en");

    public BrandProfile {
        Objects.requireNonNull(locales, "Brand locales is required (empty, not null, when unconfigured)");
        locales = List.copyOf(locales);

        if (contactPhone != null && !E164.matcher(contactPhone).matches()) {
            throw new IllegalArgumentException("Brand contact phone must be E.164, for example +998712000000");
        }
        if (telegramHandle != null && !TELEGRAM_HANDLE.matcher(telegramHandle).matches()) {
            throw new IllegalArgumentException(
                    "Telegram handle must be 5-32 letters/digits/underscore, starting with a letter, no leading @");
        }

        List<String> codes = locales.stream().map(BrandLocale::locale).toList();
        if (codes.size() != Set.copyOf(codes).size()) {
            throw new IllegalArgumentException("A brand cannot list the same locale twice");
        }
        for (String code : codes) {
            if (!KNOWN_LOCALES.contains(code)) {
                throw new IllegalArgumentException(
                        "Unsupported locale '" + code + "'; must be one of " + KNOWN_LOCALES);
            }
        }
        long defaultCount = locales.stream().filter(BrandLocale::isDefault).count();
        if (!locales.isEmpty() && defaultCount != 1) {
            throw new IllegalArgumentException(
                    "Exactly one supported locale must be marked default when any are configured");
        }
    }

    /** A brand that has configured none of this yet — the state every brand starts in. */
    public static BrandProfile empty() {
        return new BrandProfile(null, null, null, null, List.of());
    }

    /** One language a brand's storefront offers, and its description in it. */
    public record BrandLocale(String locale, @Nullable String description, boolean isDefault) {

        public BrandLocale {
            Objects.requireNonNull(locale, "Locale code is required");
            description = description == null || description.isBlank() ? null : description.strip();
        }
    }
}
