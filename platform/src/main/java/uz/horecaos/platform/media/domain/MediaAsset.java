package uz.horecaos.platform.media.domain;

import java.time.Instant;
import java.util.UUID;

import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetStatus;

/**
 * A stored media asset (ADR 0010).
 *
 * <p>The declared and verified fields are kept separate rather than overwritten.
 * Keeping both is what lets an operator see that a client claimed one thing and
 * uploaded another, which is otherwise invisible after the fact.
 *
 * @param declaredContentType what the client said it would upload
 * @param verifiedContentType what the object store reports. The trusted one
 */
public record MediaAsset(
        MediaAssetId assetId,
        UUID tenantId,
        MediaOwner owner,
        String objectKey,
        String bucket,
        MediaAssetStatus status,
        MediaVisibility visibility,
        String declaredContentType,
        long declaredSizeBytes,
        String declaredChecksumSha256,
        String verifiedContentType,
        Long verifiedSizeBytes,
        String verifiedChecksumSha256,
        String originalFilename,
        Integer widthPx,
        Integer heightPx,
        UUID createdBy,
        Instant createdAt) {
}
