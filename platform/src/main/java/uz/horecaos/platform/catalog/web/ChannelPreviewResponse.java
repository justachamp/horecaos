package uz.horecaos.platform.catalog.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.catalog.application.ChannelPreviewService.ChannelPreview;
import uz.horecaos.platform.catalog.application.ChannelPreviewService.PreviewFinding;
import uz.horecaos.platform.catalog.application.ChannelPreviewService.PreviewTarget;
import uz.horecaos.platform.catalog.application.ChannelProjection.MediaSource;
import uz.horecaos.platform.catalog.application.ChannelProjection.ResolvedMedia;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuCategory;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuModifierGroup;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuProduct;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuVariant;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcChannelProjectionStore.MarketplaceBindingRow;
import uz.horecaos.platform.tenancy.api.SalesChannel;

/**
 * What one channel would receive at one branch (ADR 0138), as the console's
 * preview screen reads it.
 *
 * <p>The menu shapes are the live storefront menu's own — {@code amountMinor}
 * beside a single {@code currency}, {@code orderable}, {@code onSaleNow},
 * {@code imageUrls} — so the preview and what a customer is shown are comparable
 * field for field, which is the whole claim the endpoint makes. The additions are
 * what only a preview knows: where each image came from, whether the channel is
 * priced by the aggregator, and the findings.
 *
 * <p>{@code findings}, {@code categories} and {@code modifierGroups} are the
 * whole menu's and travel on the first page only (no cursor); later pages carry
 * products alone. Each page is recomputed from the current draft — a preview is
 * not cached or referenceable — so a client stitching pages reads one consistent
 * state only if the draft did not move between them, and says so rather than
 * pretending otherwise.
 *
 * @param publishable what {@code GET .../validation} says and what {@code publish}
 *     decides: no universal blocker
 * @param channelReady {@code publishable} and no blocker from the projection or a
 *     marketplace ruleset
 * @param items one page of products, ordered by product id
 * @param nextCursor null on the last page
 */
public record ChannelPreviewResponse(
        PreviewChannelView channel,
        UUID locationId,
        @Nullable PreviewBindingView binding,
        String locale,
        PreviewPricing pricing,
        boolean publishable,
        boolean channelReady,
        List<PreviewFindingView> findings,
        List<ProjectedCategory> categories,
        List<MenuModifierGroup> modifierGroups,
        List<ProjectedProduct> items,
        @Nullable String nextCursor) {

    static ChannelPreviewResponse of(
            ChannelPreview preview, boolean firstPage, @Nullable String nextCursor, UUID tenantId) {
        Map<UUID, ResolvedMedia> media = preview.media();
        return new ChannelPreviewResponse(
                PreviewChannelView.of(preview.channel()),
                preview.locationId(),
                preview.binding() == null ? null : PreviewBindingView.of(preview.binding()),
                preview.locale(),
                new PreviewPricing(preview.priceAuthority().name(), preview.currency()),
                preview.publishable(),
                preview.channelReady(),
                firstPage
                        ? preview.findings().stream()
                                .map(PreviewFindingView::of)
                                .toList()
                        : List.of(),
                firstPage
                        ? preview.categories().stream()
                                .map(category -> ProjectedCategory.of(category, media, tenantId))
                                .toList()
                        : List.of(),
                firstPage ? preview.modifierGroups() : List.of(),
                preview.products().stream()
                        .map(product -> ProjectedProduct.of(product, media, tenantId))
                        .toList(),
                nextCursor);
    }

    /** @param authority {@code HORECAOS}, or {@code EXTERNAL} when the aggregator sets the price and no amount is stated */
    public record PreviewPricing(String authority, @Nullable String currency) {}

    public record PreviewChannelView(
            UUID id, String code, String displayName, String systemType, String status, boolean externallyPriced) {

        static PreviewChannelView of(SalesChannel channel) {
            return new PreviewChannelView(
                    channel.id(),
                    channel.code(),
                    channel.displayName(),
                    channel.systemType().name(),
                    channel.status().name(),
                    channel.externallyPriced());
        }
    }

    /** The marketplace binding at the previewed branch; a catalogue provider code and a tenant-typed name, never a credential. */
    public record PreviewBindingView(
            UUID bindingId, String status, @Nullable String rulesetCode, String providerType, String displayName) {

        static PreviewBindingView of(MarketplaceBindingRow row) {
            return new PreviewBindingView(
                    row.bindingId(), row.status(), row.rulesetCode(), row.providerType(), row.displayName());
        }
    }

    /**
     * A finding in {@code GET .../validation}'s own shape plus where it came from.
     *
     * @param source {@code CATALOG} (the universal rules), {@code PROJECTION} (a mechanical fact about
     *     what this channel would receive) or {@code MARKETPLACE} (a partner's ruleset)
     * @param productId the product that owns the entity the finding names, so a variant-scoped
     *     finding can deep-link to the editor that fixes it; null when it has none
     */
    public record PreviewFindingView(
            String severity,
            String code,
            @Nullable String entityType,
            @Nullable UUID entityId,
            @Nullable String entityCode,
            String detail,
            String source,
            @Nullable UUID productId) {

        static PreviewFindingView of(PreviewFinding preview) {
            CatalogPublicationController.FindingView view =
                    CatalogPublicationController.FindingView.of(preview.finding());
            return new PreviewFindingView(
                    view.severity(),
                    view.code(),
                    view.entityType(),
                    view.entityId(),
                    view.entityCode(),
                    view.detail(),
                    preview.source().name(),
                    preview.productId());
        }
    }

    public record ProjectedCategory(
            UUID categoryId,
            String code,
            String name,
            @Nullable UUID parentCategoryId,
            int sortOrder,
            List<UUID> productIds,
            List<String> mediaAssetIds,
            List<String> imageUrls,
            MediaSource mediaSource) {

        static ProjectedCategory of(MenuCategory category, Map<UUID, ResolvedMedia> media, UUID tenantId) {
            ResolvedMedia resolved = media.get(category.categoryId());
            List<String> assets = resolved == null ? List.of() : resolved.mediaAssetIds();
            return new ProjectedCategory(
                    category.categoryId(),
                    category.code(),
                    category.name(),
                    category.parentCategoryId(),
                    category.sortOrder(),
                    category.productIds(),
                    assets,
                    StorefrontCatalogQuery.imageUrls(tenantId, assets),
                    resolved == null ? MediaSource.DEFAULT : resolved.source());
        }
    }

    public record ProjectedProduct(
            UUID productId,
            String code,
            String name,
            @Nullable String description,
            List<String> mediaAssetIds,
            List<String> imageUrls,
            MediaSource mediaSource,
            List<ProjectedVariant> variants,
            List<UUID> modifierGroupIds) {

        static ProjectedProduct of(MenuProduct product, Map<UUID, ResolvedMedia> media, UUID tenantId) {
            ResolvedMedia resolved = media.get(product.productId());
            return new ProjectedProduct(
                    product.productId(),
                    product.code(),
                    product.name(),
                    product.description(),
                    product.mediaAssetIds(),
                    product.imageUrls(),
                    resolved == null ? MediaSource.DEFAULT : resolved.source(),
                    product.variants().stream()
                            .map(variant -> ProjectedVariant.of(variant, media, tenantId))
                            .toList(),
                    product.modifierGroupIds());
        }
    }

    /**
     * @param amountMinor null when the channel's price is the aggregator's to set, or the variant has no
     *     price on the plane this channel resolves to — never zero for "no price"
     */
    public record ProjectedVariant(
            UUID variantId,
            @Nullable String sku,
            @Nullable String unitCode,
            boolean isDefault,
            boolean orderable,
            boolean onSaleNow,
            @Nullable Long amountMinor,
            @Nullable BigDecimal remainingQuantity,
            List<String> mediaAssetIds,
            List<String> imageUrls,
            MediaSource mediaSource) {

        static ProjectedVariant of(MenuVariant variant, Map<UUID, ResolvedMedia> media, UUID tenantId) {
            ResolvedMedia resolved = media.get(variant.variantId());
            List<String> assets = resolved == null ? List.of() : resolved.mediaAssetIds();
            return new ProjectedVariant(
                    variant.variantId(),
                    variant.sku(),
                    variant.unitCode(),
                    variant.isDefault(),
                    variant.orderable(),
                    variant.onSaleNow(),
                    variant.amountMinor(),
                    variant.remainingQuantity(),
                    assets,
                    StorefrontCatalogQuery.imageUrls(tenantId, assets),
                    resolved == null ? MediaSource.DEFAULT : resolved.source());
        }
    }

    /** A branch a channel sells at, and the marketplace binding that covers it. */
    public record PreviewTargetView(
            UUID locationId, @Nullable PreviewBindingView binding) {

        static PreviewTargetView of(PreviewTarget target) {
            return new PreviewTargetView(
                    target.locationId(), target.binding() == null ? null : PreviewBindingView.of(target.binding()));
        }
    }
}
