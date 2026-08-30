package uz.horecaos.platform.media.domain;

import java.time.Instant;
import java.util.UUID;
import uz.horecaos.platform.media.api.MediaAssetId;

/**
 * A rendition of an asset (ADR 0010).
 *
 * <p>A row of its own rather than a column on the asset, because the set of
 * renditions changes over time and a failed render of one size must not make the
 * original unservable.
 *
 * @param processorVersion which renderer produced these bytes. Without it, a
 *                         change to the encoder is indistinguishable from a
 *                         corrupt file when someone asks why a thumbnail looks
 *                         different from its neighbours
 */
public record MediaDerivative(
        UUID derivativeId,
        UUID tenantId,
        MediaAssetId assetId,
        DerivativeVariant variant,
        String objectKey,
        String bucket,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        int widthPx,
        int heightPx,
        String processorVersion,
        Instant createdAt) {}
