package uz.horecaos.platform.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuCategory;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuModifierGroup;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuProduct;

/**
 * What one channel would receive at one branch (ADR 0138): the composed answer
 * to "if I published right now", and the only thing a marketplace ruleset is
 * handed to judge.
 *
 * <p>The menu shapes are {@link StorefrontCatalogQuery}'s own, on purpose. A
 * projection is that class's {@code assemble} run over a draft, so the products,
 * variants, prices and image URLs below are the ones a customer would be shown
 * were the draft published to a non-marketplace channel — there is no second
 * vocabulary for a ruleset to learn, and none to drift.
 *
 * @param currency the price book's currency; null when the channel is priced
 *     externally (no amount below is Qoida's to state) or no book resolved
 * @param media the images each product, variant and category would show on this
 *     channel and where they came from, keyed by entity id
 */
public record ChannelProjection(
        UUID tenantId,
        UUID brandId,
        UUID channelId,
        String channelCode,
        UUID locationId,
        PriceAuthority priceAuthority,
        @Nullable String currency,
        List<MenuCategory> categories,
        List<MenuProduct> products,
        List<MenuModifierGroup> modifierGroups,
        Map<UUID, ResolvedMedia> media) {

    public ChannelProjection {
        categories = List.copyOf(categories);
        products = List.copyOf(products);
        modifierGroups = List.copyOf(modifierGroups);
        media = Map.copyOf(media);
    }

    /** Who sets the prices on this channel — ADR 0138 step 3. */
    public enum PriceAuthority {
        /** Qoida's price books, resolved CHANNEL over LOCATION over BRAND (ADR 0018). */
        HORECAOS,
        /** The aggregator sets the price; the projection carries none ({@code externally_priced}, ADR 0036). */
        EXTERNAL
    }

    /** Which layer an entity's images on this channel came from — ADR 0138 step 4. */
    public enum MediaSource {
        /** The entity's own images, shown on every channel without an override. */
        DEFAULT,
        /** A per-channel relation stored beside the defaults ({@code catalog.media_relations.channel_code}, V0223). */
        CHANNEL_RELATION,
        /** A {@code catalog.channel_media_overrides} row — the layer ADR 0138 adds, and the one that wins. */
        CHANNEL_OVERRIDE
    }

    /** The images an entity shows on this channel, in display order, and the layer they came from. */
    public record ResolvedMedia(List<String> mediaAssetIds, MediaSource source) {

        public ResolvedMedia {
            mediaAssetIds = List.copyOf(mediaAssetIds);
        }
    }
}
