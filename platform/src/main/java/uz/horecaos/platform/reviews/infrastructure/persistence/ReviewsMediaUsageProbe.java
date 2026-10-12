package uz.horecaos.platform.reviews.infrastructure.persistence;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.media.api.MediaUsageProbe;

/**
 * The reviews module's answer to "do you still use this asset?" (ADR 0157, ADR 0159): the icon on a
 * feedback tag.
 *
 * <p>A retired tag still counts. It stays on every review that carries it and a person can bring it
 * back, so an asset it names is one somebody intends to show again.
 */
@Component
public class ReviewsMediaUsageProbe implements MediaUsageProbe {

    private final JdbcClient jdbc;

    public ReviewsMediaUsageProbe(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "reviews";
    }

    @Override
    public Set<UUID> inUse(UUID tenantId, Collection<UUID> assetIds) {
        if (assetIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                SELECT icon_asset_id FROM reviews.feedback_tags
                WHERE tenant_id = :tenantId AND icon_asset_id = ANY(:ids)
                """)
                .param("tenantId", tenantId)
                .param("ids", assetIds.toArray(UUID[]::new))
                .query(UUID.class)
                .list());
    }
}
