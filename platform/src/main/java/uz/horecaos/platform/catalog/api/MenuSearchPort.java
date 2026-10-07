package uz.horecaos.platform.catalog.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Looking a dish up by what a customer called it, in the menu a channel
 * actually serves at one location (ADR 0069).
 *
 * <p>This is not a second way to price anything. It is the storefront's own
 * assembled menu -- the live publication, the location's offerings or bound
 * menu, the channel's exclusions, the sale windows, inventory's stop state and
 * the price book {@link MenuPriceLookup} resolves for the channel -- filtered by
 * name. A price returned here is therefore the number the storefront shows and
 * ADR 0018's quote charges for that variant before modifiers, promotions and
 * delivery, because it is that number read from that path. An implementation
 * that priced a dish any other way would let a chat answer and a checkout
 * disagree, which is the one failure ADR 0069 exists to keep out.
 *
 * <p>Every method takes the tenant, and every implementation puts it in its
 * queries: a brand or location id is a UUID a caller may have received from
 * anywhere.
 */
public interface MenuSearchPort {

    /**
     * Dishes of the live menu one location serves on one channel whose names
     * match every term.
     *
     * @param channelCode the channel the customer would order through, so the
     *                    publication, the exclusions and the price plane are that
     *                    channel's and nobody else's
     * @param locales     the languages to look the names up in, most likely
     *                    first; the later ones are tried only when the earlier
     *                    ones find nothing
     * @param terms       what the customer called the dish, already stripped of
     *                    the words that only express the question; an empty list
     *                    matches nothing
     */
    MenuSearchResult search(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String channelCode,
            List<String> locales,
            List<String> terms,
            int limit);

    /**
     * @param menuPublished false when the brand has no live publication on this
     *                      channel -- "no menu" and "no match" are different
     *                      answers and a customer is owed the true one
     * @param currency      the price book's currency, null when no price book
     *                      resolves for this brand, location and channel; every
     *                      amount in {@code hits} is then null too
     */
    record MenuSearchResult(boolean menuPublished, @Nullable String currency, List<Dish> hits) {

        public MenuSearchResult {
            hits = List.copyOf(hits);
        }

        public static MenuSearchResult noMenu() {
            return new MenuSearchResult(false, null, List.of());
        }
    }

    /** One dish and its sellable forms. */
    record Dish(UUID productId, String name, @Nullable String description, String locale, List<Form> forms) {

        public Dish {
            forms = List.copyOf(forms);
        }
    }

    /**
     * One orderable size or form of a dish.
     *
     * @param amountMinor     null when the variant has no active price -- never
     *                        zero, and never to be rendered as free
     * @param orderable       false means sold out or not stocked right now
     * @param onSaleNow       false means the variant has its own sale schedule and
     *                        this moment is outside it
     * @param remainingQuantity set only when inventory publishes a low remaining
     *                        quantity
     * @param pricePerGrams   when the price is per a weight and not per unit
     *                        (ADR 0137's catchweight): the weight the price is for
     */
    record Form(
            UUID variantId,
            @Nullable String unitCode,
            boolean isDefault,
            boolean orderable,
            boolean onSaleNow,
            @Nullable Long amountMinor,
            @Nullable BigDecimal remainingQuantity,
            @Nullable Integer pricePerGrams) {}
}
