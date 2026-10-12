package uz.horecaos.platform.media.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The bytes of a verified asset, for a consumer that has to draw it itself (ADR 0154: a receipt's logo is
 * thresholded to one bit at the roll's width).
 *
 * <p>Not a URL and not a stream: a small object, read whole, with a ceiling the caller states so that an
 * attacker-chosen ten-megabyte "logo" is refused and not pulled into the heap. Only an asset that is
 * displayable (verified, not quarantined, not pending) and belongs to this tenant or to one of its brands is
 * ever answered; anything else is empty, which is also what an unknown id gives.
 */
public interface MediaAssetContent {

    /**
     * The asset's original bytes, or empty when it is not this tenant's, not yet displayable, or larger
     * than {@code maximumBytes}.
     */
    Optional<byte[]> original(UUID tenantId, MediaAssetId assetId, int maximumBytes);
}
