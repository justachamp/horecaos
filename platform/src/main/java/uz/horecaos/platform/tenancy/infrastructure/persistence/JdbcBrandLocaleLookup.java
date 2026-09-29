package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet.BrandChoice;

/**
 * {@link BrandLocaleLookup} over {@code tenant.brand_locales} (V0242).
 *
 * <p>Brands are read in {@code display_name, id} order — the order {@code
 * JdbcTenantControlPlaneStore#findBrands} lists them in and therefore the order
 * the console's "first brand" is resolved by — so the tenant default this returns
 * is the same brand's default the operator's own brand-scoped screens use.
 */
@Repository
public class JdbcBrandLocaleLookup implements BrandLocaleLookup {

    private final JdbcClient jdbc;

    public JdbcBrandLocaleLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> brandDefaultLocale(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT locale FROM tenant.brand_locales
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND is_default
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(String.class)
                .optional();
    }

    @Override
    public TenantLocaleSet tenantLocaleSet(UUID tenantId) {
        List<UUID> brandsInOrder =
                jdbc.sql("""
                SELECT id FROM tenant.brands WHERE tenant_id = :tenantId ORDER BY display_name, id
                """).param("tenantId", tenantId).query(UUID.class).list();

        Map<UUID, List<String>> localesByBrand = new LinkedHashMap<>();
        Map<UUID, String> defaultByBrand = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT brand_id, locale, is_default FROM tenant.brand_locales
                WHERE tenant_id = :tenantId ORDER BY brand_id, locale
                """)
                .param("tenantId", tenantId)
                .query((row, number) -> {
                    UUID brandId = row.getObject("brand_id", UUID.class);
                    String locale = row.getString("locale");
                    localesByBrand
                            .computeIfAbsent(brandId, ignored -> new ArrayList<>())
                            .add(locale);
                    if (row.getBoolean("is_default")) {
                        defaultByBrand.put(brandId, locale);
                    }
                    return locale;
                })
                .list();

        List<BrandChoice> choices = brandsInOrder.stream()
                .map(brandId -> new BrandChoice(
                        localesByBrand.getOrDefault(brandId, List.of()), defaultOf(defaultByBrand, brandId)))
                .toList();
        return TenantLocaleSet.union(choices);
    }

    private static @Nullable String defaultOf(Map<UUID, String> defaults, UUID brandId) {
        return defaults.get(brandId);
    }
}
