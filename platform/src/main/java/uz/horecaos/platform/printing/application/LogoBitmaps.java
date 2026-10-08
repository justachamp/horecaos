package uz.horecaos.platform.printing.application;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.media.api.MediaAssetContent;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.printing.render.Bitmap1;
import uz.horecaos.platform.printing.render.LogoThresholder;
import uz.horecaos.platform.web.cache.CacheRegistry;

/**
 * A brand's logo, thresholded to one bit at a roll's width and cached by asset id and width (ADR 0154,
 * Decision 3; ADR 0033).
 *
 * <p>An ADR 0033 registered accelerator and nothing more: a miss, an eviction or a restart costs one read
 * of the object and one threshold, and the answer is never kept in a place that outlives the asset (an
 * asset is immutable, so a new logo is a new id and the old key is simply never asked for again). Only a
 * logo that came out is cached; a failure is retried on the next print.
 */
@Component
public class LogoBitmaps {

    private static final Logger log = LoggerFactory.getLogger(LogoBitmaps.class);

    /** A logo larger than this is not a logo. */
    static final int MAXIMUM_SOURCE_BYTES = 2 * 1024 * 1024;

    private final MediaAssetContent media;
    private final CacheManager caches;

    public LogoBitmaps(MediaAssetContent media, CacheManager caches) {
        this.media = media;
        this.caches = caches;
    }

    public Optional<Bitmap1> forAsset(UUID tenantId, String assetId, int rollWidthDots) {
        UUID asset;
        try {
            asset = UUID.fromString(assetId);
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
        Cache cache = caches.getCache(CacheRegistry.PRINTING_LOGO_BITMAPS.cacheName());
        String key = tenantId + ":" + asset + ":" + rollWidthDots;
        if (cache != null) {
            Bitmap1 cached = cache.get(key, Bitmap1.class);
            if (cached != null) {
                return Optional.of(cached);
            }
        }
        Optional<Bitmap1> bitmap = media.original(tenantId, new MediaAssetId(asset), MAXIMUM_SOURCE_BYTES)
                .flatMap(bytes -> LogoThresholder.threshold(bytes, rollWidthDots));
        if (bitmap.isEmpty()) {
            log.debug("The logo asset {} could not be read as a picture; the receipt prints without it", asset);
            return bitmap;
        }
        if (cache != null) {
            cache.put(key, bitmap.get());
        }
        return bitmap;
    }
}
