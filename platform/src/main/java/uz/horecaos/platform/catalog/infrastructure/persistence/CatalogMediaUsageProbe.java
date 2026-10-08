package uz.horecaos.platform.catalog.infrastructure.persistence;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.media.api.MediaUsageProbe;

/**
 * The catalog's answer to "do you still use this asset?" (ADR 0157): a product, category, variant or
 * modifier picture (including a per-channel one), whatever the state of the catalog it sits in.
 */
@Component
public class CatalogMediaUsageProbe implements MediaUsageProbe {

    private final JdbcClient jdbc;

    public CatalogMediaUsageProbe(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "catalog";
    }

    @Override
    public Set<UUID> inUse(UUID tenantId, Collection<UUID> assetIds) {
        if (assetIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                SELECT media_asset_id FROM catalog.media_relations
                WHERE tenant_id = :tenantId AND media_asset_id = ANY(:ids)
                UNION
                SELECT media_asset_id FROM catalog.channel_media_overrides
                WHERE tenant_id = :tenantId AND media_asset_id = ANY(:ids)
                """)
                .param("tenantId", tenantId)
                .param("ids", assetIds.toArray(UUID[]::new))
                .query(UUID.class)
                .list());
    }
}
