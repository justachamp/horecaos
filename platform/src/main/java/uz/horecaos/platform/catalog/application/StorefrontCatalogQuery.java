package uz.horecaos.platform.catalog.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.api.MenuPriceLookup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.LocationOffering;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PublicationItem;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;

/**
 * What a customer sees (ADR 0016).
 *
 * <p>Reads the immutable publication, never the authoring tables. That is the
 * whole mechanism preventing a half-finished draft from appearing on a live
 * menu — not a status flag anyone could forget to check.
 *
 * <p>Location offerings <em>are</em> read live, and deliberately so: marking a
 * dish sold out must take effect at once, and routing it through a republish
 * would mean re-validating an entire menu to hide one item.
 */
@Service
public class StorefrontCatalogQuery {

    private final JdbcCatalogStore store;
    private final MenuPriceLookup prices;

    public StorefrontCatalogQuery(JdbcCatalogStore store, MenuPriceLookup prices) {
        this.store = store;
        this.prices = prices;
    }

    /**
     * The live menu for one location.
     *
     * @return empty when the brand has never published; an empty menu and no
     *         menu are different answers, and a storefront needs to tell them apart
     */
    @Transactional(readOnly = true)
    public Optional<StorefrontMenu> menuFor(
            UUID tenantId, UUID brandId, UUID locationId, String locale, String channelCode) {
        // ADR 0036: the channel supplies the publication, and it is the caller's
        // own channel rather than a literal. This read was hardcoded to
        // 'STOREFRONT', which meant a kiosk browsing its own menu was served the
        // storefront's -- silently, with the kiosk publication live and unread.
        // CatalogPricingContext had already been corrected for exactly this and
        // this copy had not.
        Optional<UUID> publicationId = store.findActivePublicationId(tenantId, brandId, channelCode);
        if (publicationId.isEmpty()) {
            return Optional.empty();
        }
        UUID publication = publicationId.get();

        Map<UUID, OfferingStatus> offeringByVariant = store.offeringsForLocation(tenantId, locationId).stream()
                .collect(Collectors.toMap(
                        LocationOffering::variantId, LocationOffering::status, (first, second) -> first));

        List<PublicationItem> categoryItems = store.publicationItems(publication, EntityType.CATEGORY);
        List<PublicationItem> productItems = store.publicationItems(publication, EntityType.PRODUCT);
        List<PublicationItem> groupItems = store.publicationItems(publication, EntityType.MODIFIER_GROUP);

        List<MenuProduct> products = new ArrayList<>();
        for (PublicationItem item : productItems) {
            List<MenuVariant> variants = variantsOf(item, offeringByVariant);
            if (variants.isEmpty()) {
                // Not offered at this location at all. Absent rather than shown
                // as unavailable: the location genuinely does not sell it.
                continue;
            }
            List<String> mediaIds = mediaIds(item.content());
            products.add(new MenuProduct(
                    item.entityId(),
                    code(item.content()),
                    name(item.content(), locale),
                    description(item.content(), locale),
                    mediaIds,
                    imageUrls(tenantId, mediaIds),
                    variants,
                    // Prices are attached after the loop, once every variant on
                    // the menu is known, so the price book is read once rather
                    // than once per dish.
                    idList(item.content(), "modifierGroupIds")));
        }

        // A product the location does not offer was dropped above. Its id must
        // not survive in a category, or the customer taps a name with nothing
        // behind it.
        Set<UUID> servedProducts =
                products.stream().map(MenuProduct::productId).collect(Collectors.toUnmodifiableSet());

        List<MenuCategory> categories = categoryItems.stream()
                .map(item -> new MenuCategory(
                        item.entityId(),
                        code(item.content()),
                        name(item.content(), locale),
                        parentOf(item.content()),
                        intOf(item.content(), "sortOrder"),
                        idList(item.content(), "productIds").stream()
                                .filter(servedProducts::contains)
                                .toList()))
                // A category whose every product is unavailable here is not an
                // empty shelf to show the customer; it is not part of this
                // location's menu. Parents are kept: one holds children, not
                // products.
                .filter(category -> !category.productIds().isEmpty() || isParent(category.categoryId(), categoryItems))
                .sorted(java.util.Comparator.comparingInt(MenuCategory::sortOrder))
                .toList();

        List<MenuModifierGroup> modifierGroups = groupItems.stream()
                .map(item -> new MenuModifierGroup(
                        item.entityId(),
                        code(item.content()),
                        name(item.content(), locale),
                        Boolean.TRUE.equals(item.content().get("required")),
                        intOf(item.content(), "minimumSelections"),
                        intOf(item.content(), "maximumSelections"),
                        Boolean.TRUE.equals(item.content().get("allowSameOptionMultipleTimes")),
                        optionsOf(item)))
                .toList();

        // ADR 0018. Resolved against the same price book the quote will use, on
        // the same channel plane, so the number a customer reads is the number
        // checkout charges. A brand with no active price book yields no prices
        // rather than zeros: free food is a very different claim from "not
        // priced yet", and only one of them is true.
        Set<UUID> variantIds = products.stream()
                .flatMap(product -> product.variants().stream())
                .map(MenuVariant::variantId)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> optionIds = modifierGroups.stream()
                .flatMap(group -> group.options().stream())
                .map(MenuModifierOption::optionId)
                .collect(Collectors.toUnmodifiableSet());

        Optional<MenuPriceLookup.MenuPrices> resolved =
                prices.pricesFor(tenantId, brandId, locationId, channelCode, variantIds, optionIds);

        String currency = resolved.map(MenuPriceLookup.MenuPrices::currency).orElse(null);
        Map<UUID, Long> variantPrices =
                resolved.map(MenuPriceLookup.MenuPrices::variantPrices).orElse(Map.of());
        Map<UUID, Long> optionPrices =
                resolved.map(MenuPriceLookup.MenuPrices::modifierOptionPrices).orElse(Map.of());

        List<MenuProduct> pricedProducts = products.stream()
                .map(product -> product.withPrices(variantPrices))
                .toList();
        List<MenuModifierGroup> pricedGroups = modifierGroups.stream()
                .map(group -> group.withPrices(optionPrices))
                .toList();

        return Optional.of(new StorefrontMenu(publication, locale, currency, categories, pricedProducts, pricedGroups));
    }

    @SuppressWarnings("unchecked")
    private static List<MenuVariant> variantsOf(PublicationItem item, Map<UUID, OfferingStatus> offeringByVariant) {

        Object raw = item.content().get("variants");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }

        List<MenuVariant> variants = new ArrayList<>();
        for (Object element : list) {
            Map<String, Object> variant = (Map<String, Object>) element;
            UUID variantId = UUID.fromString(String.valueOf(variant.get("variantId")));
            OfferingStatus offering = offeringByVariant.get(variantId);

            if (offering == null || offering == OfferingStatus.HIDDEN) {
                continue;
            }
            variants.add(new MenuVariant(
                    variantId,
                    // Nullable, and read as null rather than the string "null".
                    // A variant genuinely may have no SKU, and String.valueOf on
                    // an absent key hands the customer app a real-looking value.
                    string(variant, "sku"),
                    string(variant, "unitCode"),
                    Boolean.TRUE.equals(variant.get("isDefault")),
                    // Shown but not orderable, which is what a customer needs to
                    // see rather than an item that silently vanished.
                    offering == OfferingStatus.AVAILABLE,
                    // Attached after the whole menu is read; see menuFor.
                    null));
        }
        return variants;
    }

    @SuppressWarnings("unchecked")
    private static List<MenuModifierOption> optionsOf(PublicationItem item) {
        Object raw = item.content().get("options");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<MenuModifierOption> options = new ArrayList<>();
        for (Object element : list) {
            Map<String, Object> option = (Map<String, Object>) element;
            options.add(new MenuModifierOption(
                    UUID.fromString(String.valueOf(option.get("optionId"))),
                    code(option),
                    intOf(option, "maximumQuantity"),
                    null));
        }
        return options;
    }

    /**
     * Resolves a name in the requested locale, falling back to any published one.
     *
     * <p>Publication already refused to proceed without a name in the brand
     * default, so this cannot normally return the code — but it returns the code
     * rather than throwing if it somehow does, because a menu with one odd label
     * beats a menu that fails to load.
     */
    @SuppressWarnings("unchecked")
    private static String name(Map<String, Object> content, String locale) {
        Object raw = content.get("names");
        if (raw instanceof Map<?, ?> names && !names.isEmpty()) {
            Map<String, Map<String, String>> byLocale = (Map<String, Map<String, String>>) names;
            Map<String, String> requested = byLocale.get(locale);
            if (requested != null) {
                String requestedName = requested.get("name");
                if (requestedName != null) {
                    return requestedName;
                }
            }
            String fallback = byLocale.values().iterator().next().get("name");
            if (fallback != null) {
                return fallback;
            }
        }
        // The loader writes a code on every item, so this is reached with a real
        // value; the empty string is the same "odd label over failed menu" choice
        // for content hand-written around the loader.
        String code = string(content, "code");
        return code != null ? code : "";
    }

    @SuppressWarnings("unchecked")
    private static @Nullable String description(Map<String, Object> content, String locale) {
        Object raw = content.get("names");
        if (raw instanceof Map<?, ?> names) {
            Map<String, Map<String, String>> byLocale = (Map<String, Map<String, String>>) names;
            Map<String, String> requested = byLocale.get(locale);
            if (requested != null) {
                return requested.get("description");
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> mediaIds(Map<String, Object> content) {
        Object raw = content.get("mediaAssetIds");
        return raw instanceof List<?> list ? (List<String>) list : List.of();
    }

    /**
     * A published list of identifier strings.
     *
     * <p>Absent on publications written before membership was carried. Those are
     * immutable and still served, so this reads as "no membership" rather than
     * failing the whole menu.
     */
    private static List<UUID> idList(Map<String, Object> content, String key) {
        Object raw = content.get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(value -> UUID.fromString(String.valueOf(value)))
                .toList();
    }

    /** Whether any other category names this one as its parent. */
    private static boolean isParent(UUID categoryId, List<PublicationItem> categoryItems) {
        return categoryItems.stream().anyMatch(item -> categoryId.equals(parentOf(item.content())));
    }

    private static @Nullable UUID parentOf(Map<String, Object> content) {
        // Absent means a root category. The "null" string check is kept for
        // publications written before absent values were omitted, which are
        // immutable and therefore still out there.
        String parent = string(content, "parentCategoryId");
        return parent == null || "null".equals(parent) ? null : UUID.fromString(parent);
    }

    private static @Nullable String string(Map<String, Object> content, String key) {
        Object value = content.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * The entity's code, which the loader writes on every published item.
     *
     * <p>The empty string covers only content written around the loader, on the
     * same reasoning as {@link #name}: one odd label beats a menu that fails to
     * load.
     */
    private static String code(Map<String, Object> content) {
        String code = string(content, "code");
        return code != null ? code : "";
    }

    /**
     * One platform URL per media asset, in the order the publication lists them.
     *
     * <p>Built as a string here rather than resolved through the media module,
     * and that is deliberate: catalog knows an asset id and nothing else about
     * media, and the endpoint at the other end is what enforces that the asset is
     * PUBLIC and AVAILABLE. An id that names a private or withdrawn asset
     * therefore yields a URL that answers 404 -- a broken image, which is what a
     * retired object looks like behind any CDN, rather than a leak.
     */
    private static List<String> imageUrls(UUID tenantId, List<String> mediaAssetIds) {
        return mediaAssetIds.stream()
                .map(assetId -> "/api/v1/storefront/tenants/%s/media/%s".formatted(tenantId, assetId))
                .toList();
    }

    private static int intOf(Map<String, Object> content, String key) {
        Object value = content.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * One location's live menu, exactly as a customer is shown it.
     *
     * @param publicationId the exact snapshot served, so a cache can key on it
     * @param currency the price book's currency, or null when this brand has no
     *     active price book for this location and channel. Null means every
     *     amount below is null too, and a client must render the menu without
     *     prices rather than as free.
     */
    public record StorefrontMenu(
            UUID publicationId,
            String locale,
            @Nullable String currency,
            List<MenuCategory> categories,
            List<MenuProduct> products,
            List<MenuModifierGroup> modifierGroups) {}

    /**
     * One shelf of the menu, holding only what this location serves.
     *
     * @param productIds in the category's own order, filtered to what this location serves
     */
    public record MenuCategory(
            UUID categoryId,
            String code,
            String name,
            @Nullable UUID parentCategoryId,
            int sortOrder,
            List<UUID> productIds) {}

    /**
     * One dish as the customer sees it, variants and pictures included.
     *
     * @param imageUrls one platform URL per entry of {@code mediaAssetIds}, in the
     *     same order. Served rather than signed here: the URL is stable, cacheable
     *     with the menu, and resolves to a short-lived signed one at the moment a
     *     browser asks. ADR 0010 forbids a public bucket outright and its CDN
     *     origin does not exist yet, so this is the only shape that works today
     *     and the same URL can be fronted by the CDN when it lands.
     */
    public record MenuProduct(
            UUID productId,
            String code,
            String name,
            @Nullable String description,
            List<String> mediaAssetIds,
            List<String> imageUrls,
            List<MenuVariant> variants,
            List<UUID> modifierGroupIds) {

        MenuProduct withPrices(Map<UUID, Long> variantPrices) {
            return new MenuProduct(
                    productId,
                    code,
                    name,
                    description,
                    mediaAssetIds,
                    imageUrls,
                    variants.stream()
                            .map(variant -> variant.withPrice(variantPrices.get(variant.variantId())))
                            .toList(),
                    modifierGroupIds);
        }
    }

    /**
     * One orderable size or form of a product.
     *
     * @param sku null when the variant has none; never the string "null"
     * @param orderable false means shown as sold out rather than hidden
     * @param amountMinor null when this variant has no active price. Not zero:
     *     an unpriced variant is a menu that is not finished, and showing it as
     *     free is how a brand sells a dish for nothing.
     */
    public record MenuVariant(
            UUID variantId,
            @Nullable String sku,
            @Nullable String unitCode,
            boolean isDefault,
            boolean orderable,
            @Nullable Long amountMinor) {

        MenuVariant withPrice(@Nullable Long price) {
            return new MenuVariant(variantId, sku, unitCode, isDefault, orderable, price);
        }
    }

    /**
     * A choice offered on a product, published with its selection rules.
     *
     * @param allowSameOptionMultipleTimes whether one option may be taken more
     *     than once, without which a client cannot honour maximumQuantity and
     *     has to pin it to one
     */
    public record MenuModifierGroup(
            UUID modifierGroupId,
            String code,
            String name,
            boolean required,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameOptionMultipleTimes,
            List<MenuModifierOption> options) {

        MenuModifierGroup withPrices(Map<UUID, Long> optionPrices) {
            return new MenuModifierGroup(
                    modifierGroupId,
                    code,
                    name,
                    required,
                    minimumSelections,
                    maximumSelections,
                    allowSameOptionMultipleTimes,
                    options.stream()
                            .map(option -> option.withPrice(optionPrices.get(option.optionId())))
                            .toList());
        }
    }

    /**
     * One selectable option within a modifier group.
     *
     * @param amountMinor null when unpriced, and never zero for "no price".
     */
    public record MenuModifierOption(
            UUID optionId,
            String code,
            int maximumQuantity,
            @Nullable Long amountMinor) {

        MenuModifierOption withPrice(@Nullable Long price) {
            return new MenuModifierOption(optionId, code, maximumQuantity, price);
        }
    }
}
