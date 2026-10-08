package uz.horecaos.platform.media.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.TenantImageCheck;
import uz.horecaos.platform.media.domain.MediaAsset;
import uz.horecaos.platform.media.domain.MediaOwner;
import uz.horecaos.platform.media.domain.MediaVisibility;

/**
 * {@link TenantImageCheck} over ADR 0010's asset record.
 *
 * <p>The aspect ratio is checked with a one percent tolerance on the cross product, so a 300x200 and a
 * 301x200 icon are both 3:2 and a 4:3 one is not. The size is the store's own verified size, never the
 * client's declared one.
 */
@Component
class TenantImageCheckAdapter implements TenantImageCheck {

    /** One percent: an image cropped to the pixel by a browser is rarely exactly its ratio. */
    private static final double RATIO_TOLERANCE = 0.01;

    private final MediaAssetService assets;

    TenantImageCheckAdapter(MediaAssetService assets) {
        this.assets = assets;
    }

    @Override
    @Transactional(readOnly = true)
    public Verdict check(UUID tenantId, UUID assetId, int ratioWidth, int ratioHeight, long maximumBytes) {
        Optional<MediaAsset> found = assets.find(tenantId, new MediaAssetId(assetId));
        if (found.isEmpty()) {
            return Verdict.UNKNOWN_ASSET;
        }
        MediaAsset asset = found.get();
        if (asset.owner().scope() != MediaOwner.Scope.TENANT
                || !tenantId.equals(asset.owner().id())) {
            return Verdict.NOT_TENANT_OWNED;
        }
        if (asset.visibility() != MediaVisibility.PUBLIC) {
            return Verdict.NOT_PUBLIC;
        }
        if (!asset.status().isDisplayable()) {
            return Verdict.NOT_AVAILABLE;
        }
        String contentType = asset.verifiedContentType();
        Integer width = asset.widthPx();
        Integer height = asset.heightPx();
        if (contentType == null
                || !contentType.startsWith("image/")
                || contentType.startsWith("image/svg")
                || width == null
                || height == null
                || width < 1
                || height < 1) {
            return Verdict.NOT_A_RASTER_IMAGE;
        }
        double expected = (double) height * ratioWidth;
        double actual = (double) width * ratioHeight;
        if (Math.abs(actual - expected) > RATIO_TOLERANCE * expected) {
            return Verdict.WRONG_ASPECT_RATIO;
        }
        Long size = asset.verifiedSizeBytes();
        if (size == null || size > maximumBytes) {
            return Verdict.TOO_LARGE;
        }
        return Verdict.ACCEPTED;
    }
}
