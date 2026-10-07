package uz.horecaos.platform.catalog.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.catalog.api.MenuSearchPort;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuProduct;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuVariant;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.StorefrontMenu;
import uz.horecaos.platform.configuration.SearchText;

/**
 * {@link MenuSearchPort} over {@link StorefrontCatalogQuery#menuFor}: the same
 * assembled menu the storefront renders, searched by name (ADR 0069).
 *
 * <p>Holds no rule of its own beyond matching. Whether a dish is on this
 * channel's menu, what it costs, whether it is sold out -- every one of those is
 * {@code menuFor}'s answer, taken as given. In particular a variant {@code
 * menuFor} reports unpriced stays unpriced here, so the assistant can say "I do
 * not have a price for that" and never "it is free".
 */
@Service
public class StorefrontMenuSearch implements MenuSearchPort {

    private final StorefrontCatalogQuery storefront;

    public StorefrontMenuSearch(StorefrontCatalogQuery storefront) {
        this.storefront = storefront;
    }

    @Override
    public MenuSearchResult search(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String channelCode,
            List<String> locales,
            List<String> terms,
            int limit) {
        if (terms.isEmpty() || limit < 1) {
            return new MenuSearchResult(true, null, List.of());
        }
        boolean anyMenu = false;
        String currency = null;
        for (String locale : locales) {
            Optional<StorefrontMenu> menu = storefront.menuFor(tenantId, brandId, locationId, locale, channelCode);
            if (menu.isEmpty()) {
                // No live publication on this channel is the same in every language.
                return MenuSearchResult.noMenu();
            }
            anyMenu = true;
            currency = menu.get().currency();
            List<Dish> hits = new ArrayList<>();
            for (MenuProduct product : menu.get().products()) {
                if (SearchText.containsAll(terms, product.name())) {
                    hits.add(dishOf(product, locale));
                }
                if (hits.size() >= limit) {
                    break;
                }
            }
            if (!hits.isEmpty()) {
                return new MenuSearchResult(true, currency, hits);
            }
        }
        return new MenuSearchResult(anyMenu, currency, List.of());
    }

    private static Dish dishOf(MenuProduct product, String locale) {
        List<Form> forms = new ArrayList<>();
        for (MenuVariant variant : product.variants()) {
            Integer perGrams = variant.physical() != null
                            && variant.physical().catchweight()
                            && variant.physical().catchweightQuantumGrams() != null
                    ? variant.physical().catchweightQuantumGrams()
                    : null;
            forms.add(new Form(
                    variant.variantId(),
                    variant.unitCode(),
                    variant.isDefault(),
                    variant.orderable(),
                    variant.onSaleNow(),
                    variant.amountMinor(),
                    variant.remainingQuantity(),
                    perGrams));
        }
        return new Dish(product.productId(), product.name(), product.description(), locale, forms);
    }
}
