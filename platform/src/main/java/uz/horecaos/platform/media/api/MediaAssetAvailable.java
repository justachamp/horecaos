package uz.horecaos.platform.media.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An upload was verified against the object store and may now be shown
 * (ADR 0010).
 *
 * <p>The fact, not the work. It says an asset became displayable and what it
 * turned out to be; it does not ask anybody to render anything. Derivative
 * rendering is owed by a {@code media.derivative_jobs} row written in the same
 * transaction, because the render is this module's own obligation and must
 * survive a broker that is down.
 *
 * @param verifiedContentType what the image's own header said, not what the
 *                            client declared and not what the store echoed back
 */
public record MediaAssetAvailable(
        UUID eventId,
        UUID tenantId,
        MediaAssetId assetId,
        Instant occurredAt,
        String ownerScope,
        UUID ownerId,
        String visibility,
        String verifiedContentType,
        long verifiedSizeBytes,
        int widthPx,
        int heightPx)
        implements MediaEvent {

    public MediaAssetAvailable {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(assetId, "Asset ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(ownerScope, "Owner scope is required");
        Objects.requireNonNull(ownerId, "Owner ID is required");
        Objects.requireNonNull(visibility, "Visibility is required");
        Objects.requireNonNull(verifiedContentType, "A verified content type is required");
        if (verifiedSizeBytes <= 0 || widthPx <= 0 || heightPx <= 0) {
            throw new IllegalArgumentException("An available asset has a positive size and positive dimensions");
        }
    }

    @Override
    public String eventType() {
        return "MediaAssetAvailable";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(
                assetId.value(),
                ownerScope,
                ownerId,
                visibility,
                verifiedContentType,
                verifiedSizeBytes,
                widthPx,
                heightPx);
    }

    /**
     * Enough for a consumer to decide whether it cares and to reserve a layout
     * box. Anything more — the key, a URL, the filename — is fetched through the
     * authorized API with the asset id.
     */
    public record Payload(
            UUID assetId,
            String ownerScope,
            UUID ownerId,
            String visibility,
            String contentType,
            long sizeBytes,
            int widthPx,
            int heightPx) {}
}
