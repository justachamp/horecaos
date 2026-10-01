package uz.horecaos.platform.media.application;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.staff.StaffPhotos;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetIngestion;
import uz.horecaos.platform.media.api.MediaAssetIngestion.IngestOutcome;
import uz.horecaos.platform.media.api.MediaAssetIngestion.OwnerScope;
import uz.horecaos.platform.media.domain.MediaOwner;
import uz.horecaos.platform.media.domain.MediaVisibility;

/**
 * {@code iam}'s photo port (ADR 0139) over ADR 0010's pipeline.
 *
 * <p>Nothing but a translation, and it lives here rather than in {@code iam} for
 * the reason {@link StaffPhotos} gives: {@code media} already depends on {@code
 * iam}, so the adapter has to sit on this side of the boundary.
 *
 * <p>The strict part is {@link #isPrivateTenantAsset}: {@code
 * MediaAvailability#allDisplayable} would accept a public catalogue image or a
 * brand-owned one, and neither may become a person's profile photo. A staff
 * photo is personal data (ADR 0029), so it must be one the tenant itself owns
 * and only ever serves through a signed URL.
 */
@Component
class StaffPhotoAdapter implements StaffPhotos {

    private final MediaAssetIngestion ingestion;
    private final MediaAssetService assets;

    StaffPhotoAdapter(MediaAssetIngestion ingestion, MediaAssetService assets) {
        this.ingestion = ingestion;
        this.assets = assets;
    }

    @Override
    public Ingested ingest(UUID tenantId, byte[] content, @Nullable String originalFilename, @Nullable UUID actorId) {
        IngestOutcome outcome = ingestion.ingestFromBytes(
                tenantId, OwnerScope.TENANT, tenantId, false, content, originalFilename, actorId);
        MediaAssetId assetId = outcome.assetId();
        return new Ingested(outcome.accepted(), assetId == null ? null : assetId.value(), outcome.rejectionCode());
    }

    @Override
    public boolean isPrivateTenantAsset(UUID tenantId, UUID assetId) {
        return privateTenantAsset(tenantId, assetId);
    }

    @Override
    public Optional<URI> signedReadUrl(UUID tenantId, UUID assetId) {
        if (!privateTenantAsset(tenantId, assetId)) {
            return Optional.empty();
        }
        return assets.downloadUrl(tenantId, new MediaAssetId(assetId));
    }

    private boolean privateTenantAsset(UUID tenantId, UUID assetId) {
        return assets.find(tenantId, new MediaAssetId(assetId))
                .filter(asset -> asset.status().isDisplayable())
                .filter(asset -> asset.visibility() == MediaVisibility.PRIVATE)
                .filter(asset -> asset.owner().scope() == MediaOwner.Scope.TENANT
                        && tenantId.equals(asset.owner().id()))
                .isPresent();
    }
}
