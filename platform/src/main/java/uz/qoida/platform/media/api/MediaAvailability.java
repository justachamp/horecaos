package uz.qoida.platform.media.api;

import java.util.Set;
import java.util.UUID;

/**
 * Whether assets are verified and safe to show (ADR 0010).
 *
 * <p>The one media question other modules need answered. Catalog asks it before
 * publishing, because a published reference to a pending upload becomes a live
 * menu of broken images that the immutable publication cannot heal.
 *
 * <p>Narrow on purpose: exposing the whole upload service would let any module
 * presign a URL or finalize someone else's upload.
 */
public interface MediaAvailability {

    /** True only when every asset exists, belongs to this tenant, and is verified. */
    boolean allDisplayable(UUID tenantId, Set<MediaAssetId> assetIds);
}
