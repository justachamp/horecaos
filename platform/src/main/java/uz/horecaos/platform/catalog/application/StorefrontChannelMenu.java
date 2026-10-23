package uz.horecaos.platform.catalog.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.catalog.api.ChannelMenuPort;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuCategory;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuModifierGroup;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuProduct;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuVariant;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.StorefrontMenu;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;

/**
 * {@link ChannelMenuPort} over {@link StorefrontCatalogQuery#menuFor}: the storefront's own assembled
 * menu for a channel, handed to the partner module in neutral records (ADR 0040, ADR 0138).
 *
 * <p>Holds no rule of its own. Whether a dish is on the channel's menu here, what it costs, whether it
 * is stopped and which pictures it shows are {@code menuFor}'s answers taken as given, so the menu a
 * partner pulls cannot disagree with the one a customer is shown or the one the channel preview draws.
 */
@Service
public class StorefrontChannelMenu implements ChannelMenuPort {

    private final StorefrontCatalogQuery storefront;
    private final BrandLocaleLookup brandLocales;
    private final String defaultLocale;

    public StorefrontChannelMenu(
            StorefrontCatalogQuery storefront,
            BrandLocaleLookup brandLocales,
            @Value("${horecaos.catalog.default-locale:uz}") String defaultLocale) {
        this.storefront = storefront;
        this.brandLocales = brandLocales;
        this.defaultLocale = defaultLocale;
    }

    @Override
    public Optional<ChannelMenu> liveMenu(
            UUID tenantId, UUID brandId, UUID locationId, String channelCode, @Nullable String locale) {
        String wanted = locale == null || locale.isBlank()
                ? brandLocales.brandDefaultLocale(tenantId, brandId).orElse(defaultLocale)
                : locale;
        return storefront
                .menuFor(tenantId, brandId, locationId, wanted, channelCode)
                .map(this::translate);
    }

    private ChannelMenu translate(StorefrontMenu menu) {
        return new ChannelMenu(
                menu.publicationId(),
                menu.locale(),
                menu.currency(),
                menu.categories().stream()
                        .map(StorefrontChannelMenu::categoryOf)
                        .toList(),
                menu.products().stream().map(StorefrontChannelMenu::itemOf).toList(),
                menu.modifierGroups().stream()
                        .map(StorefrontChannelMenu::groupOf)
                        .toList());
    }

    private static Category categoryOf(MenuCategory category) {
        return new Category(
                category.categoryId(),
                category.code(),
                category.name(),
                category.parentCategoryId(),
                category.sortOrder(),
                category.productIds(),
                images(category.mediaAssetIds(), category.imageUrls()));
    }

    private static Item itemOf(MenuProduct product) {
        return new Item(
                product.productId(),
                product.code(),
                product.name(),
                product.description(),
                images(product.mediaAssetIds(), product.imageUrls()),
                product.variants().stream()
                        .map(StorefrontChannelMenu::variantOf)
                        .toList(),
                product.modifierGroupIds());
    }

    private static Variant variantOf(MenuVariant variant) {
        return new Variant(
                variant.variantId(),
                variant.sku(),
                variant.unitCode(),
                variant.name(),
                variant.isDefault(),
                variant.orderable(),
                variant.onSaleNow(),
                variant.amountMinor(),
                images(variant.mediaAssetIds(), variant.imageUrls()));
    }

    private static ModifierGroup groupOf(MenuModifierGroup group) {
        return new ModifierGroup(
                group.modifierGroupId(),
                group.code(),
                group.name(),
                group.required(),
                group.minimumSelections(),
                group.maximumSelections(),
                group.allowSameOptionMultipleTimes(),
                group.options().stream()
                        .map(option -> new ModifierOption(
                                option.optionId(),
                                option.code(),
                                option.name(),
                                option.maximumQuantity(),
                                option.amountMinor()))
                        .toList());
    }

    private static List<Image> images(List<String> assetIds, List<String> urls) {
        List<Image> images = new ArrayList<>();
        for (int index = 0; index < assetIds.size() && index < urls.size(); index++) {
            images.add(new Image(assetIds.get(index), urls.get(index)));
        }
        return List.copyOf(images);
    }
}
