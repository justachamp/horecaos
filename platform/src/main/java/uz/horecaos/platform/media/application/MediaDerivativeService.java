package uz.horecaos.platform.media.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.media.api.ImageDerivativeRenderer;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.media.domain.DerivativeVariant;
import uz.horecaos.platform.media.domain.MediaAsset;
import uz.horecaos.platform.media.domain.MediaDerivative;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaAssetStore;

/**
 * Renders the fixed set of derivatives for a verified asset (ADR 0010).
 *
 * <p>Separate from {@link MediaAssetService} because the two have opposite shapes.
 * Verification is bounded — a head and a header read — and belongs on the request
 * that is waiting for its verdict. Rendering decodes a raster and re-encodes it
 * once per variant; its cost scales with the picture rather than with the
 * request, and a menu import of four hundred dishes would otherwise be four
 * hundred request threads holding a decoder each.
 *
 * <p>Deliberately not {@code @Transactional}. Every step here is a round-trip to
 * the object store, and holding a pooled connection across three renders and
 * three uploads is the failure ADR 0010's finalize path already avoids. Each
 * write is idempotent on its own, which is what makes a retry safe without one.
 */
@Service
public class MediaDerivativeService {

    private static final Logger log = LoggerFactory.getLogger(MediaDerivativeService.class);

    private final JdbcMediaAssetStore assets;
    private final MediaDerivativeStore derivatives;
    private final ObjectStorage storage;
    private final ImageDerivativeRenderer renderer;
    private final Clock clock;

    public MediaDerivativeService(
            JdbcMediaAssetStore assets,
            MediaDerivativeStore derivatives,
            ObjectStorage storage,
            ImageDerivativeRenderer renderer,
            Clock clock) {
        this.assets = assets;
        this.derivatives = derivatives;
        this.storage = storage;
        this.renderer = renderer;
        this.clock = clock;
    }

    /**
     * Renders whatever this asset is still missing.
     *
     * <p>Safe to call again after a partial failure or a redelivered event: a
     * variant already recorded is left alone, so a retry costs a lookup rather
     * than a re-render.
     *
     * @throws IllegalArgumentException if no such asset belongs to this tenant
     */
    public DerivativeReport renderMissing(UUID tenantId, MediaAssetId assetId) {
        MediaAsset asset = assets.findOwned(tenantId, assetId)
                .orElseThrow(() -> new IllegalArgumentException("No such media asset"));

        if (!asset.status().isDisplayable()) {
            // Not an error. An availability event and a deletion request can
            // cross, and rendering a rendition of something nobody may see is
            // work whose only product is an orphaned object.
            log.debug("Skipping derivatives for asset {} in status {}", assetId, asset.status());
            return new DerivativeReport(List.of(), List.of(), null);
        }

        List<DerivativeVariant> existing = new ArrayList<>();
        List<DerivativeVariant> missing = new ArrayList<>();
        for (DerivativeVariant variant : DerivativeVariant.values()) {
            if (derivatives.find(tenantId, assetId, variant).isPresent()) {
                existing.add(variant);
            } else {
                missing.add(variant);
            }
        }
        if (missing.isEmpty()) {
            // Nothing is owed, so the original is never read and never decoded.
            // A redelivered trigger costs three lookups, which is the property
            // that makes at-least-once delivery affordable here.
            return new DerivativeReport(List.of(), List.copyOf(existing), null);
        }

        byte[] source =
                storage.readPrefix(asset.bucket(), asset.objectKey(), Math.toIntExact(asset.verifiedSizeBytes()));
        if (source.length == 0) {
            throw new IllegalStateException("The original object for asset %s could not be read".formatted(assetId));
        }

        // One call for every missing variant, so the source is decoded once.
        // Three decodes of the same raster was three times the peak allocation
        // and three times the exposure for three outputs that differ only in a
        // scale factor.
        ImageDerivativeRenderer.RenderOutcome outcome = renderer.render(source, missing);

        List<DerivativeVariant> created = new ArrayList<>();
        String unsupportedReason = null;
        switch (outcome) {
            case ImageDerivativeRenderer.Rendered produced -> {
                for (var rendition : produced.renditions().entrySet()) {
                    if (record(tenantId, asset, rendition.getKey(), rendition.getValue())) {
                        created.add(rendition.getKey());
                    } else {
                        existing.add(rendition.getKey());
                    }
                }
            }
            case ImageDerivativeRenderer.Unsupported settled -> {
                // The renderer will never read these bytes. Recorded once and
                // moved past rather than retried: a WebP original will still be
                // a WebP original on the next delivery, and a source too large
                // to decode will still declare the same header.
                unsupportedReason = settled.reason();
                log.info(
                        "Asset {} has no renderable derivative ({}): {} was not decoded here",
                        assetId,
                        unsupportedReason,
                        asset.verifiedContentType());
            }
            case ImageDerivativeRenderer.Failed failed ->
                // Not settled, and emphatically not a success. Thrown so the
                // caller's retry-then-abandon budget applies, rather than
                // returned as an asset that simply has no thumbnail.
                throw new DerivativeRenderFailedException(failed.errorCode());
        }

        // Sorted, because the report names variants and a caller comparing two
        // runs should not see a difference that is only iteration order.
        created.sort(null);
        existing.sort(null);
        return new DerivativeReport(List.copyOf(created), List.copyOf(existing), unsupportedReason);
    }

    public List<MediaDerivative> findAll(UUID tenantId, MediaAssetId assetId) {
        return derivatives.findAll(tenantId, assetId);
    }

    private boolean record(
            UUID tenantId, MediaAsset asset, DerivativeVariant variant, ImageDerivativeRenderer.Rendition rendered) {

        String key = variant.objectKey(asset.objectKey());
        // The object is written before the row. The other order leaves a row
        // pointing at nothing, which a storefront discovers as a broken image;
        // this order can at worst leave an unreferenced object, which a lifecycle
        // rule sweeps up.
        storage.put(asset.bucket(), key, rendered.contentType(), rendered.content());

        Instant now = clock.instant();
        return derivatives.insertIfAbsent(new MediaDerivative(
                UUID.randomUUID(),
                tenantId,
                asset.assetId(),
                variant,
                key,
                asset.bucket(),
                rendered.contentType(),
                rendered.content().length,
                sha256(rendered.content()),
                rendered.widthPx(),
                rendered.heightPx(),
                renderer.processorVersion(),
                now));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    /**
     * What this asset now has, and — when it has nothing — why that is settled.
     *
     * <p>There is no third field for a failure, on purpose. A report is only
     * produced when the outcome is final for this asset; a failed attempt
     * leaves by way of {@link DerivativeRenderFailedException} instead, so a
     * caller cannot accidentally treat "not yet" as "no".
     *
     * @param unsupportedReason null when the source was renderable. Otherwise a
     *                          stable code saying why no renderer here will ever
     *                          produce a rendition from it, so some variants
     *                          will never exist and a caller must be able to
     *                          fall back to the original
     */
    public record DerivativeReport(
            List<DerivativeVariant> created, List<DerivativeVariant> alreadyPresent, String unsupportedReason) {

        public boolean sourceUnsupported() {
            return unsupportedReason != null;
        }
    }
}
