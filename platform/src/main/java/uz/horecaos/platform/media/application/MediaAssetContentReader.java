package uz.horecaos.platform.media.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.media.api.MediaAssetContent;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.media.domain.MediaAsset;

/**
 * {@link MediaAssetContent} over the asset registry and the object store (ADR 0010, ADR 0154).
 *
 * <p>The same ranged read {@code MediaDerivativeService} uses for a whole object: a store that cannot
 * serve it returns nothing, and nothing is an empty answer here, never a partial picture.
 */
@Component
class MediaAssetContentReader implements MediaAssetContent {

    private final MediaAssetService assets;
    private final ObjectStorage storage;

    MediaAssetContentReader(MediaAssetService assets, ObjectStorage storage) {
        this.assets = assets;
        this.storage = storage;
    }

    @Override
    public Optional<byte[]> original(UUID tenantId, MediaAssetId assetId, int maximumBytes) {
        Optional<MediaAsset> found =
                assets.find(tenantId, assetId).filter(asset -> asset.status().isDisplayable());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MediaAsset asset = found.get();
        Long size = asset.verifiedSizeBytes();
        if (size == null || size <= 0 || size > maximumBytes) {
            return Optional.empty();
        }
        byte[] content = storage.readPrefix(asset.bucket(), asset.objectKey(), Math.toIntExact(size));
        return content.length == size ? Optional.of(content) : Optional.empty();
    }
}
