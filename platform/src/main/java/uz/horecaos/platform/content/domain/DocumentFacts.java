package uz.horecaos.platform.content.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a validated document refers to outside itself (ADR 0157).
 *
 * <p>Nothing queries inside a document, so everything the gates and the read model need to ask
 * another module about is lifted out once, here, by the same code that validated it: the assets
 * (which become {@code content.revision_media} rows), the catalogue ids, the promotions and the
 * external hosts (which the allowlist is checked against at publish and again on every read).
 *
 * @param assets      every asset the document uses, one entry per use
 * @param productIds  products named by a {@code PRODUCT} link or a manual group
 * @param categoryIds categories named by a {@code CATEGORY} link or a by-category group
 * @param promotionIds promotions named by a {@code PROMOTION} link
 * @param externalRefs every {@code https} destination: an {@code EXTERNAL} link, a block
 *                    {@code href}, a vacancy's address
 * @param locales     the languages the document has a title in, canonical tags
 */
public record DocumentFacts(
        List<AssetUse> assets,
        Set<UUID> productIds,
        Set<UUID> categoryIds,
        Set<UUID> promotionIds,
        List<ExternalRef> externalRefs,
        Set<String> locales) {

    public DocumentFacts {
        assets = List.copyOf(assets);
        productIds = Set.copyOf(productIds);
        categoryIds = Set.copyOf(categoryIds);
        promotionIds = Set.copyOf(promotionIds);
        externalRefs = List.copyOf(externalRefs);
        locales = Set.copyOf(locales);
    }

    /**
     * One use of one asset.
     *
     * @param field          the path into the document, for a refusal that says where
     * @param posterAssetId  the poster the author named for a video, if any
     * @param declaredKind   {@code IMAGE} or {@code VIDEO} where the document says which (a story
     *                       slide); null where it does not
     * @param ratio          the width over the height the placement wants, or 0 for no requirement;
     *                       {@code CONTENT_MEDIA_ASPECT_MISMATCH} fires beyond 3% of it
     * @param minWidthPx     the narrowest the placement accepts, or 0 for no requirement
     */
    public record AssetUse(
            UUID assetId,
            MediaRole role,
            int position,
            String field,
            @Nullable UUID posterAssetId,
            @Nullable String declaredKind,
            double ratio,
            int minWidthPx) {}

    /** An {@code https} destination a customer would be sent to or shown. */
    public record ExternalRef(String field, String host, String url) {}
}
