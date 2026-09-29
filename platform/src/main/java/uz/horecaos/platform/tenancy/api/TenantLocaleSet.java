package uz.horecaos.platform.tenancy.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The locale set a <em>tenant-scoped</em> vocabulary is edited in (row 10.12).
 *
 * <p>A brand owns a supported-locale set and a default ({@code
 * tenant.brand_locales}, V0242) and every brand-scoped editor authors against
 * it. Some vocabularies belong to the tenant rather than to a brand — the
 * preset product comments (V0378), regions (V0025) — and there is no single
 * brand set to author them against.
 *
 * <p><strong>The decision, recorded here because it is a decision:</strong> a
 * tenant-scoped vocabulary is edited in the <em>union</em> of its brands'
 * supported locales, with the default taken from the tenant's <em>first</em>
 * brand (the same "first brand, display-name order" the console's own scope
 * resolution picks, {@code CurrentBrand}). A brand that has configured no
 * locale set supports the platform triple — the fallback every brand-scoped
 * editor already applies — so an unconfigured brand widens the union to the
 * triple rather than narrowing it to nothing. A tenant with no brand at all
 * falls back to the triple with {@code ru} as default.
 *
 * <p>The union is a <em>menu</em>, not a constraint: a locale outside it that is
 * already stored on a row is never deleted by an edit (the editors keep it hidden,
 * and the write side merges rather than replaces).
 *
 * @param locales       every locale the tenant's brands support, the default
 *                      first, then the platform's canonical order, then any other
 *                      in code order
 * @param defaultLocale the first brand's default
 * @param configured    whether at least one brand has chosen its own set (as
 *                      opposed to the whole tenant sitting on the platform
 *                      fallback)
 */
public record TenantLocaleSet(List<String> locales, String defaultLocale, boolean configured) {

    /** The platform's own supported triple, in canonical order. */
    public static final List<String> PLATFORM_LOCALES = List.of("ru", "uz-Latn", "en");

    /** The default a brand falls back to when it has chosen none. */
    public static final String PLATFORM_DEFAULT_LOCALE = "ru";

    public TenantLocaleSet {
        Objects.requireNonNull(defaultLocale, "A locale set names its default");
        locales = List.copyOf(locales);
    }

    /** No brand has chosen anything: the platform triple, {@code ru} first. */
    public static TenantLocaleSet platformFallback() {
        return new TenantLocaleSet(PLATFORM_LOCALES, PLATFORM_DEFAULT_LOCALE, false);
    }

    /**
     * One brand's choice, as {@link #union} reads it.
     *
     * @param locales       the brand's configured locales; empty means the brand
     *                      has configured nothing
     * @param defaultLocale the configured default, or null when none is marked
     */
    public record BrandChoice(
            List<String> locales, @Nullable String defaultLocale) {

        public BrandChoice {
            locales = List.copyOf(locales);
        }

        boolean configured() {
            return !locales.isEmpty();
        }
    }

    /**
     * Folds the tenant's brands into one set.
     *
     * @param brandsInOrder every brand of the tenant in the console's own brand
     *                      order (display name, then id); the first decides the
     *                      default
     */
    public static TenantLocaleSet union(List<BrandChoice> brandsInOrder) {
        if (brandsInOrder.isEmpty()) {
            return platformFallback();
        }
        Set<String> union = new LinkedHashSet<>();
        boolean anyConfigured = false;
        for (BrandChoice brand : brandsInOrder) {
            if (brand.configured()) {
                anyConfigured = true;
                union.addAll(brand.locales());
            } else {
                union.addAll(PLATFORM_LOCALES);
            }
        }

        BrandChoice first = brandsInOrder.getFirst();
        String defaultLocale;
        if (!first.configured()) {
            defaultLocale = PLATFORM_DEFAULT_LOCALE;
        } else if (first.defaultLocale() != null && first.locales().contains(first.defaultLocale())) {
            defaultLocale = first.defaultLocale();
        } else {
            // A configured brand always names exactly one default (the service
            // requires it, uq_brand_locales_default keeps it to one), so this is a
            // hand-edited row; the first locale is a safe, deterministic answer.
            defaultLocale = first.locales().getFirst();
        }

        List<String> ordered = new ArrayList<>();
        ordered.add(defaultLocale);
        for (String platform : PLATFORM_LOCALES) {
            if (union.contains(platform) && !ordered.contains(platform)) {
                ordered.add(platform);
            }
        }
        union.stream().filter(locale -> !ordered.contains(locale)).sorted().forEach(ordered::add);
        return new TenantLocaleSet(ordered, defaultLocale, anyConfigured);
    }
}
