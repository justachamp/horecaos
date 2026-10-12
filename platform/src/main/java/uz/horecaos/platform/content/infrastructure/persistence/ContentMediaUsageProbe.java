package uz.horecaos.platform.content.infrastructure.persistence;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.media.api.MediaUsageProbe;

/**
 * The content module's answer to "do you still use this asset?" (ADR 0157): yes if the latest revision
 * of any item uses it, or any draft names it.
 *
 * <p>Registered with the media module's release port, which asks every probe before an asset is allowed
 * to go. This one lives in the content module because only the content module may read its tables.
 */
@Component
public class ContentMediaUsageProbe implements MediaUsageProbe {

    private final JdbcContentStore store;

    public ContentMediaUsageProbe(JdbcContentStore store) {
        this.store = store;
    }

    @Override
    public String module() {
        return "content";
    }

    @Override
    public Set<UUID> inUse(UUID tenantId, Collection<UUID> assetIds) {
        return store.mediaStillUsed(tenantId, assetIds);
    }
}
