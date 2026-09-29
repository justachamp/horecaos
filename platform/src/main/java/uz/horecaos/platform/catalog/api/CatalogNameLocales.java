package uz.horecaos.platform.catalog.api;

import java.util.UUID;
import uz.horecaos.platform.catalog.domain.CatalogLocales;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;

/**
 * The two locales a server-side read of a catalog name resolves in (row 10.12): the
 * brand's own default language, and the server's configured one for an entity that has
 * no name in the first.
 *
 * <p>{@code CatalogQueryService} (the console's list screens) answers a name this way,
 * and so must every other reader of {@code catalog.translations} whose answer ends up in
 * front of somebody: the publication gate, the description a quote line snapshots onto an
 * order, the option names a kitchen ticket prints. A reader left on the server's locale
 * alone would fall to a bare code (or refuse to publish) for a brand that names its menu
 * in its own default language and never in {@code uz} -- the language the console's
 * editors stopped forcing into the tab strip once the list reads followed the brand.
 *
 * @param preferred the brand's default language on the catalog's locale vocabulary
 *                  ({@link CatalogLocales}), or the server's when the brand has chosen none
 * @param fallback  {@code horecaos.catalog.default-locale}: where a menu imported or
 *                  sampled without a brand-specific language put its names
 */
public record CatalogNameLocales(String preferred, String fallback) {

    /** Resolves the pair for one brand; the brand lookup is tenant-scoped. */
    public static CatalogNameLocales of(
            BrandLocaleLookup brandLocales, UUID tenantId, UUID brandId, String serverLocale) {
        String preferred = brandLocales
                .brandDefaultLocale(tenantId, brandId)
                .map(CatalogLocales::forBrandLocale)
                .orElse(serverLocale);
        return new CatalogNameLocales(preferred, serverLocale);
    }

    /** The pair for a caller with no brand to ask about: the server's locale, twice. */
    public static CatalogNameLocales serverOnly(String serverLocale) {
        return new CatalogNameLocales(serverLocale, serverLocale);
    }
}
