package uz.horecaos.platform.media.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Whether a module still refers to a media asset (ADR 0157, Media and video).
 *
 * <p>An asset is referenced from tables the media module does not own: a product's pictures, a
 * brand's logo, a channel's share image, a help-centre icon, a content revision. The media module may
 * not read those, and it must not delete a public asset that one of them still uses -- a broken image
 * on a live storefront is the failure this exists to prevent. So every module that stores an asset id
 * registers one probe, and {@link MediaRelease} asks them all before it lets an asset go.
 *
 * <p>Fail-closed by construction: a module without a probe is a module whose references the release
 * cannot see, and {@code MediaAssetReferenceCatalogTests} fails the build when a column that holds an
 * asset id is neither covered by a probe nor excluded for a stated reason.
 */
public interface MediaUsageProbe {

    /** The owning module, for the log line that explains a refused release. */
    String module();

    /**
     * Of {@code assetIds}, the ones this module's data still refers to -- in any state that could be
     * shown again. A draft counts: an asset that a draft names is one a person intends to publish.
     */
    Set<UUID> inUse(UUID tenantId, Collection<UUID> assetIds);
}
