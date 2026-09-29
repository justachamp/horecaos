package uz.horecaos.platform.catalog.domain;

/**
 * The catalog's own locale vocabulary, and how a brand's locale maps onto it.
 *
 * <p>{@code catalog.translations.locale} is free text and its established
 * Uzbek code is {@code uz}, not the platform-wide {@code uz-Latn} that
 * {@code tenant.brand_locales} (V0242) and every other module use (ADR 0035);
 * {@code horecaos.catalog.default-locale} is {@code uz} for the same reason. The
 * console's {@code toCatalogLocale} is this mapping's frontend twin, so a name the
 * console writes and a name the list reads resolve through the same code.
 */
public final class CatalogLocales {

    private CatalogLocales() {}

    /** The code {@code catalog.translations} rows carry for a brand's locale. */
    public static String forBrandLocale(String brandLocale) {
        return "uz-Latn".equals(brandLocale) ? "uz" : brandLocale;
    }
}
