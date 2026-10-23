package uz.horecaos.platform.media.api;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The upload lifecycle and the attach check for an image with a {@link MediaPurpose} (ADR 0187, D25), for a module whose
 * managers hold a capability of its own and not {@code media.upload}.
 *
 * <p>A branch manager holds {@code kiosk.device.manage} and neither the tenant-wide {@code media.upload} nor
 * {@code channel.manage}; a card that needed either would be unusable by the people it is for. So the media module
 * lends the narrow thing: allocate an upload that can only ever be a {@code PUBLIC}, tenant-owned image of this purpose
 * and at most this purpose's size, finalize one that is, and answer whether an asset may be attached for the purpose.
 * Nothing here reads, lists or deletes an asset, and an asset of another purpose (or none) answers like one that does
 * not exist, so an id cannot be probed.
 */
public interface PurposedImages {

    /**
     * Reserves an id and a key for a purposed image and returns a URL constrained to them.
     *
     * @throws IllegalArgumentException the declared type is not JPEG, PNG or WebP, or the size is over the purpose's cap
     */
    UploadTicket requestUpload(
            UUID tenantId,
            MediaPurpose purpose,
            String contentType,
            long sizeBytes,
            @Nullable String originalFilename,
            @Nullable UUID actorId);

    /**
     * Records the client's claim that the upload is complete and queues verification.
     *
     * @return the asset's status after the call, or empty when no asset of this tenant has this purpose
     */
    Optional<MediaAssetStatus> finalizeUpload(UUID tenantId, UUID assetId, MediaPurpose purpose);

    /**
     * Where a purposed upload's verification stands, so a console can wait for it and explain a refusal. Empty for an asset
     * that is not this tenant's, not public and tenant-owned, or of another purpose: one answer.
     */
    Optional<UploadState> uploadState(UUID tenantId, UUID assetId, MediaPurpose purpose);

    /** Whether the asset may be attached for the purpose now, and if not the one reason that applies first. */
    Verdict check(UUID tenantId, UUID assetId, MediaPurpose purpose);

    /** @param rejectionCode set only for a {@code REJECTED} asset, e.g. {@code PURPOSE_ASPECT_RATIO} */
    record UploadState(
            UUID assetId, MediaAssetStatus status, @Nullable String rejectionCode) {}

    record UploadTicket(UUID assetId, URI uploadUrl, Map<String, String> requiredHeaders, Instant expiresAt) {}

    enum Verdict {
        ACCEPTED,
        /** No such asset, not this tenant's, or an asset of another purpose: one answer. */
        UNKNOWN_ASSET,
        /** Not yet verified, rejected or deleted. */
        NOT_AVAILABLE,
        /** Not a raster image whose dimensions the pipeline read, or not of a type the purpose takes (JPEG, PNG or WebP). */
        NOT_A_RASTER_IMAGE,
        WRONG_ASPECT_RATIO,
        TOO_LARGE
    }
}
