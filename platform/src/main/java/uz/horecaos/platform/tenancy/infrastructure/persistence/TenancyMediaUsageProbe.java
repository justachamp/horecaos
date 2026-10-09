package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.media.api.MediaUsageProbe;

/**
 * Tenancy's answer to "do you still use this asset?" (ADR 0157): a brand's logo or banner, and a sales
 * channel's share image.
 */
@Component
public class TenancyMediaUsageProbe implements MediaUsageProbe {

    private final JdbcClient jdbc;

    public TenancyMediaUsageProbe(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "tenancy";
    }

    @Override
    public Set<UUID> inUse(UUID tenantId, Collection<UUID> assetIds) {
        if (assetIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                SELECT media_asset_id FROM tenant.brand_media
                WHERE tenant_id = :tenantId AND media_asset_id = ANY(:ids)
                UNION
                SELECT og_image_asset_id FROM tenant.channel_presentation
                WHERE tenant_id = :tenantId AND og_image_asset_id = ANY(:ids)
                """)
                .param("tenantId", tenantId)
                .param("ids", assetIds.toArray(UUID[]::new))
                .query(UUID.class)
                .list());
    }
}
