package uz.horecaos.platform.tenancy.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the languages a brand has chosen to support (row 10.12, {@code
 * tenant.brand_locales}).
 *
 * <p>The locale set is owned by {@code tenancy} (V0242) but consumed wherever a
 * localized field is read or authored: the catalog resolves a list screen's names
 * in the brand's default language, and the tenant-scoped vocabularies (preset
 * product comments, regions) are edited in the union of the tenant's brands. A
 * port here spares every one of them a dependency on tenancy's persistence.
 *
 * <p>Every method takes the tenant id and every implementation puts it in the
 * query: a brand id is a UUID a caller may have received from anywhere, and a
 * lookup that matched on the brand id alone would answer another tenant's brand.
 */
public interface BrandLocaleLookup {

    /**
     * The default language the brand chose, or empty when it has chosen none
     * (nothing configured, or the brand is not this tenant's). Empty is
     * "not configured" and never "supports nothing" — the caller falls back to its
     * own platform default, the way {@code BrandProfile.locales} documents.
     */
    Optional<String> brandDefaultLocale(UUID tenantId, UUID brandId);

    /**
     * The locale set a tenant-scoped vocabulary is edited in: the union of the
     * tenant's brands, the first brand's default. See {@link TenantLocaleSet} for
     * the decision and its edge cases.
     */
    TenantLocaleSet tenantLocaleSet(UUID tenantId);

    /**
     * A lookup for callers that have no tenancy to ask -- a unit test, a tool: no
     * brand has a default of its own and every tenant sits on the platform
     * fallback ({@link TenantLocaleSet#platformFallback()}).
     */
    static BrandLocaleLookup platformFallback() {
        return new BrandLocaleLookup() {
            @Override
            public Optional<String> brandDefaultLocale(UUID tenantId, UUID brandId) {
                return Optional.empty();
            }

            @Override
            public TenantLocaleSet tenantLocaleSet(UUID tenantId) {
                return TenantLocaleSet.platformFallback();
            }
        };
    }
}
