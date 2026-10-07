package uz.horecaos.platform.catalog.domain;

import uz.horecaos.platform.tenancy.api.PlatformLocale;
import uz.horecaos.platform.tenancy.api.PlatformLocales;

/**
 * How a brand's locale maps onto the catalog's own locale vocabulary, and back (ADR 0149).
 *
 * <p>{@code catalog.translations.locale} is free text and its established Uzbek code is {@code uz},
 * not the platform-wide {@code uz-Latn} that {@code tenant.brand_locales} (V0242) and every other
 * module use (ADR 0035); {@code horecaos.catalog.default-locale} is {@code uz} for the same reason,
 * and so are the {@code names} keys inside every published menu snapshot, which are hashed and
 * never rewritten. The mapping is the registry's: {@code uz-Latn}'s {@code catalogCode}, the one
 * named place a bare {@code uz} is still the stored spelling. This class is only the question
 * "what code does the catalog hold for this brand locale?" put to it, so the answer is no longer a
 * ternary here and a conversion in each console.
 */
public final class CatalogLocales {

    private CatalogLocales() {}

    /** The code {@code catalog.translations} rows carry for a brand's locale. */
    public static String forBrandLocale(String brandLocale) {
        return PlatformLocales.byTag(brandLocale)
                .map(PlatformLocale::catalogCode)
                .orElse(brandLocale);
    }

    /**
     * The platform-wide locale for a code a customer's client sends (the storefront's
     * {@code ?locale=}) -- {@link #forBrandLocale}'s inverse. The vocabularies that follow
     * ADR 0035 (the per-locale preset, region and zone tables, {@code tenant.brand_locales})
     * know Uzbek as {@code uz-Latn}, never a bare {@code uz}.
     */
    public static String toPlatformLocale(String catalogLocale) {
        return PlatformLocales.byCatalogCode(catalogLocale)
                .map(PlatformLocale::tag)
                .orElse(catalogLocale);
    }
}
