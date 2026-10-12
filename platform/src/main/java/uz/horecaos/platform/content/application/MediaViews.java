package uz.horecaos.platform.content.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.content.application.HomeDocument.Media;
import uz.horecaos.platform.media.api.MediaCreativeLookup;
import uz.horecaos.platform.media.api.MediaCreativeLookup.Creative;

/**
 * How an asset is shown to a customer (ADR 0157): the renditions that exist, never the original,
 * and nothing at all for an asset the storefront redirect would answer 404 for.
 *
 * <p>One implementation for the home read and the editorial reads, so an image cannot be
 * servable on one and broken on the other.
 */
final class MediaViews {

    private MediaViews() {}

    /** The URL the storefront redirect serves for an asset, optionally as one of its renditions. */
    static String url(UUID tenantId, UUID asset, @Nullable String variantCode) {
        String base = "/api/v1/storefront/tenants/" + tenantId + "/media/" + asset;
        return variantCode == null ? base : base + "?variant=" + variantCode;
    }

    /**
     * A servable image or video, or null when the asset cannot be served: a missing, unverified or
     * private asset, an image with no rendition yet, a video without a servable poster.
     */
    static @Nullable Media of(
            UUID tenantId, UUID asset, @Nullable String alt, @Nullable UUID poster, Map<UUID, Creative> creatives) {
        Creative creative = creatives.get(asset);
        if (creative == null || !creative.servable()) {
            return null;
        }
        if (creative.kind() == MediaCreativeLookup.Kind.VIDEO) {
            Creative posterAsset = poster == null ? null : creatives.get(poster);
            if (poster == null
                    || posterAsset == null
                    || !posterAsset.servable()
                    || posterAsset.renditions().isEmpty()) {
                return null;
            }
            var posterRendition = posterAsset.renditions().stream()
                    .filter(r -> r.widthPx() >= 400)
                    .min(Comparator.comparingInt(MediaCreativeLookup.Rendition::widthPx))
                    .orElse(posterAsset.renditions().getLast());
            return new Media(
                    "VIDEO",
                    alt,
                    creative.widthPx(),
                    creative.heightPx(),
                    List.of(),
                    url(tenantId, asset, null),
                    url(tenantId, poster, posterRendition.code()),
                    creative.durationMs());
        }
        if (creative.renditions().isEmpty()) {
            return null;
        }
        List<HomeDocument.Rendition> renditions = creative.renditions().stream()
                .sorted(Comparator.comparingInt(MediaCreativeLookup.Rendition::widthPx))
                .map(r -> new HomeDocument.Rendition(r.widthPx(), url(tenantId, asset, r.code())))
                .toList();
        return new Media("IMAGE", alt, creative.widthPx(), creative.heightPx(), renditions, null, null, null);
    }

    /** An image only: a body or an album never plays a video (ADR 0157, Decision 6). */
    static @Nullable Media image(UUID tenantId, UUID asset, @Nullable String alt, Map<UUID, Creative> creatives) {
        Creative creative = creatives.get(asset);
        if (creative == null || creative.kind() != MediaCreativeLookup.Kind.IMAGE) {
            return null;
        }
        return of(tenantId, asset, alt, null, creatives);
    }
}
