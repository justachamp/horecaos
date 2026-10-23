package uz.horecaos.platform.notifications.infrastructure.persistence;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.media.api.MediaUsageProbe;

/**
 * The notifications module's answer to "do you still use this asset?" (ADR 0157, ADR 0182): the cover
 * picture of a push wording.
 *
 * <p>Every version counts, drafts and superseded ones included. A version is frozen onto the messages
 * sent from it and a person can base a new version on an old one, so an asset any version names is one
 * somebody may show again.
 */
@Component
public class NotificationsMediaUsageProbe implements MediaUsageProbe {

    private final JdbcClient jdbc;

    public NotificationsMediaUsageProbe(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "notifications";
    }

    @Override
    public Set<UUID> inUse(UUID tenantId, Collection<UUID> assetIds) {
        if (assetIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                SELECT DISTINCT cover_asset_id FROM notifications.template_versions
                WHERE tenant_id = :tenantId AND cover_asset_id = ANY(:ids)
                """)
                .param("tenantId", tenantId)
                .param("ids", assetIds.toArray(UUID[]::new))
                .query(UUID.class)
                .list());
    }
}
