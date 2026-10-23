package uz.horecaos.platform.catalog.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The menu one channel serves at one branch, as a partner is told it (ADR 0040 "Menu is
 * pull-first", ADR 0138 "push consumes the same projection").
 *
 * <p>Not a second projection: it is the storefront's own assembled menu for the channel -- the live
 * publication, the branch's offerings or its bound named menu, the channel's exclusions, the sale
 * windows, inventory's stop state, the price plane of the channel, and the images the channel shows
 * for each product, variant and category -- carried across the module boundary in neutral records so
 * a partner module does not import catalog's application types. A price, a stop and a picture here
 * are therefore the ones the preview draws and a customer is served, because they are read from the
 * same function; an implementation that assembled a menu any other way would let a partner sell what
 * the kitchen refuses.
 *
 * <p>Carries identifiers, wording, prices as integer minor units and image references. No
 * personal data, no cost, no stock quantity and no reason for a stop.
 */
public interface ChannelMenuPort {

    /**
     * The live menu of one channel at one branch.
     *
     * @param channelCode the channel the partner's orders arrive on, so the publication, the
     *     exclusions and the price plane are that channel's and nobody else's
     * @param locale the language the wording is resolved in; null for the brand's own default
     * @return empty when the brand has never published to this channel
     */
    Optional<ChannelMenu> liveMenu(
            UUID tenantId, UUID brandId, UUID locationId, String channelCode, @Nullable String locale);

    /**
     * @param publicationId the exact snapshot served
     * @param currency the price book's currency, or null when none resolves; every amount below is
     *     then null too, and a partner must never read an absent price as free
     */
    record ChannelMenu(
            UUID publicationId,
            String locale,
            @Nullable String currency,
            List<Category> categories,
            List<Item> items,
            List<ModifierGroup> modifierGroups) {}

    /** @param productIds in the category's own order, filtered to what this branch serves */
    record Category(
            UUID categoryId,
            String code,
            String name,
            @Nullable UUID parentCategoryId,
            int sortOrder,
            List<UUID> productIds,
            List<Image> images) {}

    /** One dish, with the sizes or forms it is sold in. */
    record Item(
            UUID productId,
            String code,
            String name,
            @Nullable String description,
            List<Image> images,
            List<Variant> variants,
            List<UUID> modifierGroupIds) {}

    /**
     * One orderable size or form.
     *
     * @param name the wording the author gave the size or form, null when it has none
     * @param orderable false means sold out or stopped on this channel here now: shown, not hidden
     * @param amountMinor null when no price resolves; never zero for "no price"
     * @param images the images this channel shows for the variant itself; empty means the dish's
     *     own stand for it
     */
    record Variant(
            UUID variantId,
            @Nullable String sku,
            @Nullable String unitCode,
            @Nullable String name,
            boolean isDefault,
            boolean orderable,
            boolean onSaleNow,
            @Nullable Long amountMinor,
            List<Image> images) {}

    record ModifierGroup(
            UUID modifierGroupId,
            String code,
            String name,
            boolean required,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameOptionMultipleTimes,
            List<ModifierOption> options) {}

    record ModifierOption(
            UUID optionId,
            String code,
            @Nullable String name,
            int maximumQuantity,
            @Nullable Long amountMinor) {}

    /**
     * One picture: the media asset and the platform URL it resolves through (stable, and answered
     * 404 once the asset is withdrawn, never a public bucket -- ADR 0010).
     */
    record Image(String assetId, String url) {}
}
