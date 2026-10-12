package uz.horecaos.platform.media.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a creative needs to know about the assets it uses (ADR 0157, Media seam).
 *
 * <p>{@link MediaAvailability#allDisplayable} answers a single boolean about existence, tenant and
 * status, which is the right size for "may the menu publish this". A banner or a story needs
 * more: the pixel size (the aspect gate, and the response's {@code widthPx}/{@code heightPx}), the
 * visibility (the storefront redirect serves only a {@code PUBLIC} asset, so a banner on a
 * {@code PRIVATE} one would show every customer a broken image), which renditions exist (so the
 * response never names one that has not rendered) and, from Release 3, whether it is a video and
 * how long it plays.
 *
 * <p>Tenant-scoped on every call: an asset id of another tenant is simply absent from the answer,
 * the same shape as an id that does not exist, so a gate built on this cannot be used to probe
 * another tenant's library. Read-only and bounded by the caller's page size; it presigns nothing.
 */
public interface MediaCreativeLookup {

    /**
     * One batch read of the assets this tenant owns among {@code assetIds}.
     *
     * @return the assets that exist for the tenant and are not deleted, keyed by id; an asset that
     *         is absent from the map does not exist for this tenant
     */
    Map<UUID, Creative> describe(UUID tenantId, Collection<UUID> assetIds);

    /** What the platform knows about one asset: facts only, never a key or a URL (ADR 0010). */
    record Creative(
            UUID assetId,
            MediaAssetStatus status,
            Visibility visibility,
            Kind kind,
            @Nullable Integer widthPx,
            @Nullable Integer heightPx,
            @Nullable Integer durationMs,
            List<Rendition> renditions) {

        public Creative {
            renditions = List.copyOf(renditions);
        }

        /** Whether the storefront redirect would serve this asset: verified and public. */
        public boolean servable() {
            return status.isDisplayable() && visibility == Visibility.PUBLIC;
        }
    }

    /**
     * A rendition that exists, by its public code ({@code thumb}, {@code w400}, {@code w800},
     * {@code w1600}) and width. The code is what {@code ?variant=} takes on the storefront media
     * redirect.
     */
    record Rendition(String code, int widthPx) {}

    enum Visibility {
        PUBLIC,
        PRIVATE
    }

    enum Kind {
        IMAGE,
        VIDEO
    }
}
