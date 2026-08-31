package uz.horecaos.platform.media.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetStatus;

/**
 * A stored media asset (ADR 0010).
 *
 * <p>The declared and verified fields are kept separate rather than overwritten.
 * Keeping both is what lets an operator see that a client claimed one thing and
 * uploaded another, which is otherwise invisible after the fact.
 *
 * @param declaredContentType   what the client said it would upload
 * @param declaredChecksumSha256 null unless the client declared one
 * @param verifiedContentType   what the object store reports. The trusted one.
 *                              Null until {@code finalizeUpload} verifies the
 *                              upload
 * @param verifiedSizeBytes     null until verified, for the same reason
 * @param verifiedChecksumSha256 null until verified, for the same reason
 * @param originalFilename      a display label only; null when none was given
 *                              or it was blank after stripping control
 *                              characters
 * @param widthPx               null until the image's own header is read at
 *                              verification
 * @param heightPx              null until the image's own header is read at
 *                              verification
 * @param createdBy             the uploading principal, or null when its
 *                              Keycloak subject was not a UUID — recorded for
 *                              attribution only, so an upload is never refused
 *                              over it
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
        @Nullable String declaredChecksumSha256,
        @Nullable String verifiedContentType,
        @Nullable Long verifiedSizeBytes,
        @Nullable String verifiedChecksumSha256,
        @Nullable String originalFilename,
        @Nullable Integer widthPx,
        @Nullable Integer heightPx,
        @Nullable UUID createdBy,
        Instant createdAt) {}
