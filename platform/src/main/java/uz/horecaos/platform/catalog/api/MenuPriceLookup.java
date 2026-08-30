package uz.horecaos.platform.catalog.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the published menu costs (ADR 0016, ADR 0018).
 *
 * <p>The sibling of {@link VariantPricingLookup}, which answers only whether a
 * variant has a price at all — enough to refuse publishing an unpriced dish, and
 * not enough to show a customer a menu. This answers with the amounts.
 *
 * <p>It lives in catalog's public interface because pricing implements it, the
 * same way round as {@code VariantPricingLookup}. The consumer declaring the
 * contract is what keeps the dependency pointing one way: catalog never learns
 * what a price book is, what an assignment is, or that a channel has a price
 * plane distinct from itself.
 *
 * <p><strong>The channel is not optional and must be the cart's own.</strong>
 * ADR 0036 makes the channel supply both the publication and the price plane, and
 * a channel-scoped price book outranks a location-scoped one. A menu priced
 * against a different channel than the cart is a menu whose prices change at
 * checkout, which is the one thing ADR 0018's quote exists to prevent.
 */
public interface MenuPriceLookup {

    /**
     * Current prices for one location on one channel.
     *
     * @return empty when no active price book resolves for this brand, location
     *         and channel. A menu with no price book is not a menu with free
     *         food: the caller renders it without prices rather than with zeros.
     */
    Optional<MenuPrices> pricesFor(UUID tenantId, UUID brandId, UUID locationId,
            String channelCode, Set<UUID> variantIds, Set<UUID> modifierOptionIds);

    /**
     * @param currency the price book's own currency. Every amount below is in its
     *        minor units — whole som for UZS (ADR 0018), never divided by a
     *        hundred.
     * @param variantPrices a variant absent from this map has no active price and
     *        is reported to the customer as unpriced rather than as free.
     */
    record MenuPrices(String currency, Map<UUID, Long> variantPrices,
            Map<UUID, Long> modifierOptionPrices) {

        public MenuPrices {
            variantPrices = variantPrices == null ? Map.of() : Map.copyOf(variantPrices);
            modifierOptionPrices =
                    modifierOptionPrices == null ? Map.of() : Map.copyOf(modifierOptionPrices);
        }
    }
}
