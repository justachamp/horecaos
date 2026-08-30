package uz.horecaos.platform.media.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.domain.DerivativeVariant;
import uz.horecaos.platform.media.domain.MediaDerivative;

/**
 * Derivative persistence (ADR 0010).
 *
 * <p>An interface, unlike {@code JdbcMediaAssetStore} next to it. It was one
 * because {@code media.derivatives} did not exist and the pipeline's rules —
 * render each variant once, never twice, never for an unverified asset — were
 * worth proving before the table landed. The table landed in V0058 and the
 * in-memory implementation is gone; the interface stays because rendering is the
 * part of this module ADR 0010 expects to move out of process, and an
 * out-of-process renderer would bring its own store with it.
 */
public interface MediaDerivativeStore {

    /**
     * Records a rendition, or does nothing if this variant is already recorded.
     *
     * <p>Idempotent on {@code (asset_id, variant)}, because two renders racing is
     * an ordinary consequence of an at-least-once event delivery and must not
     * leave two rows claiming to be the same rendition.
     *
     * @return true when this call is the one that recorded it
     */
    boolean insertIfAbsent(MediaDerivative derivative);

    Optional<MediaDerivative> find(UUID tenantId, MediaAssetId assetId, DerivativeVariant variant);

    /** Every rendition of an asset, in variant order. */
    List<MediaDerivative> findAll(UUID tenantId, MediaAssetId assetId);
}
