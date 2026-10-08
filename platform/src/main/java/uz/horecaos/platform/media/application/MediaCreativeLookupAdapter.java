package uz.horecaos.platform.media.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.media.api.MediaCreativeLookup;
import uz.horecaos.platform.media.domain.MediaAsset;
import uz.horecaos.platform.media.domain.MediaDerivative;
import uz.horecaos.platform.media.domain.MediaVisibility;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaAssetStore;

/**
 * {@link MediaCreativeLookup} over the asset and derivative tables (ADR 0157).
 *
 * <p>Two reads however many assets are asked about: the assets, then every rendition of them. The
 * content read model calls this once per request with the handful of assets a home screen uses,
 * which is why it takes a collection and never a single id.
 */
@Service
public class MediaCreativeLookupAdapter implements MediaCreativeLookup {

    private final JdbcMediaAssetStore assets;
    private final MediaDerivativeStore derivatives;

    public MediaCreativeLookupAdapter(JdbcMediaAssetStore assets, MediaDerivativeStore derivatives) {
        this.assets = assets;
        this.derivatives = derivatives;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Creative> describe(UUID tenantId, Collection<UUID> assetIds) {
        List<MediaAsset> found = assets.findAllOwned(tenantId, assetIds);
        if (found.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<MediaDerivative>> renditionsByAsset = new HashMap<>();
        for (MediaDerivative derivative : derivatives.findAllFor(
                tenantId, found.stream().map(a -> a.assetId().value()).collect(Collectors.toList()))) {
            renditionsByAsset
                    .computeIfAbsent(derivative.assetId().value(), ignored -> new java.util.ArrayList<>())
                    .add(derivative);
        }
        Map<UUID, Creative> described = new LinkedHashMap<>();
        for (MediaAsset asset : found) {
            UUID id = asset.assetId().value();
            List<Rendition> renditions = renditionsByAsset.getOrDefault(id, List.of()).stream()
                    .map(d -> new Rendition(d.variant().code(), d.widthPx()))
                    .toList();
            described.put(
                    id,
                    new Creative(
                            id,
                            asset.status(),
                            asset.visibility() == MediaVisibility.PUBLIC ? Visibility.PUBLIC : Visibility.PRIVATE,
                            isVideo(asset) ? Kind.VIDEO : Kind.IMAGE,
                            asset.widthPx(),
                            asset.heightPx(),
                            asset.durationMs(),
                            renditions));
        }
        return Map.copyOf(described);
    }

    private static boolean isVideo(MediaAsset asset) {
        String type = asset.verifiedContentType() != null ? asset.verifiedContentType() : asset.declaredContentType();
        return type != null && type.startsWith("video/");
    }
}
