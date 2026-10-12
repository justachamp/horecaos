package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.tenancy.api.BrandLogoLookup;

/** {@link BrandLogoLookup} over {@code tenant.brand_media} (V0243). */
@Component
class JdbcBrandLogoLookup implements BrandLogoLookup {

    private final JdbcClient jdbc;

    JdbcBrandLogoLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> logoAssetId(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT media_asset_id
                  FROM tenant.brand_media
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND role = 'LOGO'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(UUID.class)
                .optional();
    }
}
