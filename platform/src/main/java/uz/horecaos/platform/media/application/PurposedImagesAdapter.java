package uz.horecaos.platform.media.application;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetStatus;
import uz.horecaos.platform.media.api.MediaPurpose;
import uz.horecaos.platform.media.api.PurposedImages;
import uz.horecaos.platform.media.domain.MediaAsset;
import uz.horecaos.platform.media.domain.MediaOwner;
import uz.horecaos.platform.media.domain.MediaVisibility;

/**
 * {@link PurposedImages} over ADR 0010's asset record (ADR 0187, D25).
 *
 * <p>Every upload it allocates is a {@code PUBLIC} image owned by the tenant and carrying the purpose, and the
 * purpose is what the verification worker measures the picture against. The attach check re-reads the asset's
 * own verified columns, so it answers on what the pipeline found in the bytes and never on what the request said.
 */
@Component
class PurposedImagesAdapter implements PurposedImages {

    private final MediaAssetService assets;

    PurposedImagesAdapter(MediaAssetService assets) {
        this.assets = assets;
    }

    @Override
    @Transactional
    public UploadTicket requestUpload(
            UUID tenantId,
            MediaPurpose purpose,
            String contentType,
            long sizeBytes,
            @Nullable String originalFilename,
            @Nullable UUID actorId) {
        MediaAssetService.UploadTicket ticket = assets.requestUpload(
                tenantId,
                MediaOwner.tenant(tenantId),
                MediaVisibility.PUBLIC,
                contentType,
                sizeBytes,
                originalFilename,
                actorId,
                null,
                purpose);
        return new UploadTicket(
                ticket.assetId().value(), ticket.uploadUrl(), ticket.requiredHeaders(), ticket.expiresAt());
    }

    @Override
    @Transactional
    public Optional<MediaAssetStatus> finalizeUpload(UUID tenantId, UUID assetId, MediaPurpose purpose) {
        Optional<MediaAsset> found = purposed(tenantId, assetId, purpose);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(assets.finalizeUpload(tenantId, new MediaAssetId(assetId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UploadState> uploadState(UUID tenantId, UUID assetId, MediaPurpose purpose) {
        return purposed(tenantId, assetId, purpose)
                .map(asset -> new UploadState(
                        assetId,
                        asset.status(),
                        asset.status() == MediaAssetStatus.REJECTED
                                ? assets.rejectionCodeOf(tenantId, new MediaAssetId(assetId))
                                        .orElse(null)
                                : null));
    }

    @Override
    @Transactional(readOnly = true)
    public Verdict check(UUID tenantId, UUID assetId, MediaPurpose purpose) {
        Optional<MediaAsset> found = purposed(tenantId, assetId, purpose);
        if (found.isEmpty()) {
            return Verdict.UNKNOWN_ASSET;
        }
        MediaAsset asset = found.get();
        if (!asset.status().isDisplayable()) {
            return Verdict.NOT_AVAILABLE;
        }
        String contentType = asset.verifiedContentType();
        Integer width = asset.widthPx();
        Integer height = asset.heightPx();
        if (contentType == null
                || !contentType.startsWith("image/")
                || contentType.startsWith("image/svg")
                || !purpose.allowsContentType(contentType)
                || width == null
                || height == null) {
            return Verdict.NOT_A_RASTER_IMAGE;
        }
        if (!purpose.matchesRatio(width, height)) {
            return Verdict.WRONG_ASPECT_RATIO;
        }
        Long size = asset.verifiedSizeBytes();
        if (size == null || size > purpose.maximumBytes()) {
            return Verdict.TOO_LARGE;
        }
        return Verdict.ACCEPTED;
    }

    /** The asset when it is this tenant's, public, tenant-owned and of this purpose; empty otherwise (one answer). */
    private Optional<MediaAsset> purposed(UUID tenantId, UUID assetId, MediaPurpose purpose) {
        return assets.find(tenantId, new MediaAssetId(assetId))
                .filter(asset -> purpose.name().equals(asset.purpose()))
                .filter(asset -> asset.owner().scope() == MediaOwner.Scope.TENANT
                        && tenantId.equals(asset.owner().id()))
                .filter(asset -> asset.visibility() == MediaVisibility.PUBLIC);
    }
}
