package uz.horecaos.platform.catalog.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.catalog.application.ChannelProjection.MediaSource;
import uz.horecaos.platform.catalog.application.ChannelProjection.ResolvedMedia;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Category;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Product;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PublicationItem;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore.MediaRelationRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcChannelProjectionStore.MediaOverrideRow;
import uz.horecaos.platform.tenancy.api.SalesChannel;

/**
 * The images one channel shows for an item (ADR 0138 step 4), as a pure function
 * of what the database holds — the single implementation both {@code publish} and
 * the channel preview go through, so the picture a preview draws and the picture a
 * customer is served cannot be two answers kept in agreement by discipline.
 *
 * <p>Each product, variant and category shows, on this channel, the first of
 *
 * <ol>
 *   <li>its {@code channel_media_overrides} rows for this channel, else
 *   <li>its {@code media_relations} rows naming this channel (V0223), else
 *   <li>its own <em>universal</em> images ({@code channel_code = 'ALL'}).
 * </ol>
 *
 * <p>The last is why this class exists. {@link CatalogSnapshotLoader} lists every
 * relation of an entity whatever channel it names — V0223 added the column and the
 * snapshot was never taught to filter on it — so a dish carrying Uzum Tezkor's own
 * crop was published to the storefront with that crop beside its photo, and a dish
 * whose only image was a Wolt crop was published to every channel with it. A
 * relation naming one channel is that channel's, and nobody else's.
 *
 * <p>An entity with nothing channel-specific is left exactly as the snapshot
 * published it — the same list in the same order — so a brand that never used
 * either layer publishes the content, and so the content hash, it always did.
 */
final class ChannelMediaLayers {

    /** The sentinel {@code catalog.media_relations.channel_code} for an entity's own, every-channel image. */
    private static final String UNIVERSAL = JdbcCatalogStore.ALL_CHANNELS;

    private ChannelMediaLayers() {}

    /**
     * What a channel shows, item by item, and the items that say so.
     *
     * @param items the drafted items with every product's {@code mediaAssetIds} replaced by what
     *     this channel shows; an item that shows what the draft already lists is the same instance
     * @param resolved the images of every product, variant and category and the layer each came from
     * @param overridesByEntity the override rows that were applied, by entity
     */
    record Plan(
            List<PublicationItem> items,
            Map<UUID, ResolvedMedia> resolved,
            Map<UUID, List<MediaOverrideRow>> overridesByEntity) {}

    /**
     * @param drafted the snapshot's channel-agnostic items, whose product {@code mediaAssetIds} are
     *     the order the draft publishes in
     * @param relations every {@code media_relations} row of the brand
     * @param overrides every override row of the brand on this channel; empty to leave that layer out
     */
    static Plan plan(
            SalesChannel channel,
            CatalogValidator.Snapshot snapshot,
            List<PublicationItem> drafted,
            List<MediaRelationRow> relations,
            List<MediaOverrideRow> overrides) {

        Map<UUID, List<MediaRelationRow>> relationsByEntity = new HashMap<>();
        for (MediaRelationRow relation : relations) {
            relationsByEntity
                    .computeIfAbsent(relation.entityId(), id -> new ArrayList<>())
                    .add(relation);
        }
        Map<UUID, List<MediaOverrideRow>> overridesByEntity = new HashMap<>();
        for (MediaOverrideRow row : overrides) {
            overridesByEntity
                    .computeIfAbsent(row.entityId(), id -> new ArrayList<>())
                    .add(row);
        }

        // The universal sentinel is also a legal channel code ("ALL" matches the
        // code pattern), and a channel by that name must not read the universal
        // images as its own relations.
        boolean channelCanOwnRelations = !UNIVERSAL.equals(channel.code());

        Map<UUID, List<String>> draftedProductMedia = new HashMap<>();
        for (PublicationItem item : drafted) {
            if (item.entityType() == EntityType.PRODUCT && item.content().get("mediaAssetIds") instanceof List<?> ids) {
                draftedProductMedia.put(
                        item.entityId(), ids.stream().map(String::valueOf).toList());
            }
        }

        Map<UUID, ResolvedMedia> resolved = new LinkedHashMap<>();
        for (Product product : snapshot.products()) {
            resolved.put(
                    product.id(),
                    resolve(
                            product.id(),
                            channel,
                            channelCanOwnRelations,
                            overridesByEntity,
                            relationsByEntity,
                            draftedProductMedia.get(product.id())));
        }
        for (Variant variant : snapshot.variants()) {
            resolved.put(
                    variant.id(),
                    resolve(variant.id(), channel, channelCanOwnRelations, overridesByEntity, relationsByEntity, null));
        }
        for (Category category : snapshot.categories()) {
            resolved.put(
                    category.id(),
                    resolve(
                            category.id(),
                            channel,
                            channelCanOwnRelations,
                            overridesByEntity,
                            relationsByEntity,
                            null));
        }

        List<PublicationItem> items = drafted.stream()
                .map(item -> {
                    if (item.entityType() != EntityType.PRODUCT) {
                        return item;
                    }
                    ResolvedMedia productMedia = resolved.get(item.entityId());
                    if (productMedia == null
                            || productMedia.mediaAssetIds().equals(draftedProductMedia.get(item.entityId()))) {
                        return item;
                    }
                    Map<String, Object> content = new LinkedHashMap<>(item.content());
                    content.put("mediaAssetIds", productMedia.mediaAssetIds());
                    return new PublicationItem(item.entityType(), item.entityId(), item.entityVersion(), content);
                })
                .toList();

        return new Plan(items, resolved, overridesByEntity);
    }

    private static ResolvedMedia resolve(
            UUID entityId,
            SalesChannel channel,
            boolean channelCanOwnRelations,
            Map<UUID, List<MediaOverrideRow>> overridesByEntity,
            Map<UUID, List<MediaRelationRow>> relationsByEntity,
            @Nullable List<String> drafted) {

        List<MediaOverrideRow> overrides = overridesByEntity.getOrDefault(entityId, List.of());
        if (!overrides.isEmpty()) {
            return new ResolvedMedia(
                    overrides.stream()
                            .sorted(Comparator.comparing((MediaOverrideRow row) -> !"PRIMARY".equals(row.role()))
                                    .thenComparingInt(MediaOverrideRow::sortOrder)
                                    .thenComparing(MediaOverrideRow::mediaAssetId))
                            .map(row -> row.mediaAssetId().toString())
                            .toList(),
                    MediaSource.CHANNEL_OVERRIDE);
        }

        List<MediaRelationRow> relations = relationsByEntity.getOrDefault(entityId, List.of());
        if (channelCanOwnRelations) {
            List<MediaRelationRow> own = relations.stream()
                    .filter(relation -> channel.code().equals(relation.channelCode()))
                    .toList();
            if (!own.isEmpty()) {
                return new ResolvedMedia(orderedAssets(own), MediaSource.CHANNEL_RELATION);
            }
        }

        List<MediaRelationRow> universal = relations.stream()
                .filter(relation -> UNIVERSAL.equals(relation.channelCode()))
                .toList();
        if (drafted != null) {
            // The draft lists every relation of the product; keep its order and
            // drop what another channel owns. With no foreign relation this is the
            // draft's own list, untouched.
            if (universal.size() == relations.size()) {
                return new ResolvedMedia(drafted, MediaSource.DEFAULT);
            }
            java.util.Set<String> universalAssets = universal.stream()
                    .map(relation -> relation.mediaAssetId().toString())
                    .collect(java.util.stream.Collectors.toSet());
            return new ResolvedMedia(
                    drafted.stream().filter(universalAssets::contains).toList(), MediaSource.DEFAULT);
        }
        return new ResolvedMedia(orderedAssets(universal), MediaSource.DEFAULT);
    }

    private static List<String> orderedAssets(List<MediaRelationRow> relations) {
        return relations.stream()
                .sorted(Comparator.comparingInt(MediaRelationRow::sortOrder)
                        .thenComparing(MediaRelationRow::mediaAssetId))
                .map(MediaRelationRow::mediaAssetId)
                .map(UUID::toString)
                .distinct()
                .toList();
    }
}
