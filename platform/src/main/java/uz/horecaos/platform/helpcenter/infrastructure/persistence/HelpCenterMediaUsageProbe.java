package uz.horecaos.platform.helpcenter.infrastructure.persistence;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.media.api.MediaUsageProbe;

/**
 * The help centre's answer to "do you still use this asset?" (ADR 0157): the custom icon on a brand's
 * social link.
 */
@Component
public class HelpCenterMediaUsageProbe implements MediaUsageProbe {

    private final JdbcClient jdbc;

    public HelpCenterMediaUsageProbe(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "helpcenter";
    }

    @Override
    public Set<UUID> inUse(UUID tenantId, Collection<UUID> assetIds) {
        if (assetIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                SELECT media_asset_id FROM support.social_links
                WHERE tenant_id = :tenantId AND media_asset_id = ANY(:ids)
                """)
                .param("tenantId", tenantId)
                .param("ids", assetIds.toArray(UUID[]::new))
                .query(UUID.class)
                .list());
    }
}
