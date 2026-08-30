package uz.qoida.platform.pricing.infrastructure.catalog;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import uz.qoida.platform.catalog.api.MenuPriceLookup;
import uz.qoida.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.qoida.platform.tenancy.api.SalesChannel;
import uz.qoida.platform.tenancy.api.SalesChannelLookup;

/**
 * Pricing's answer to catalog's {@link MenuPriceLookup} (ADR 0016, ADR 0018).
 *
 * <p>Resolves the same price book the quote will, through the same
 * {@code resolvePriceBook} and the same channel price plane, so the number on
 * the menu is the number checkout charges. Reimplementing the resolution here —
 * or simplifying it to "the brand's book" — is how a menu and a receipt start
 * disagreeing on a brand that prices one channel differently.
 *
 * <p>The clock is injected rather than read from {@code Instant.now()} so a test
 * can put a price book's validity window on either side of the read.
 */
@Component
public class PricingMenuPriceLookup implements MenuPriceLookup {

    private final JdbcPricingStore store;
    private final SalesChannelLookup channels;
    private final Clock clock;

    public PricingMenuPriceLookup(JdbcPricingStore store, SalesChannelLookup channels, Clock clock) {
        this.store = store;
        this.channels = channels;
        this.clock = clock;
    }

    @Override
    public Optional<MenuPrices> pricesFor(UUID tenantId, UUID brandId, UUID locationId,
            String channelCode, Set<UUID> variantIds, Set<UUID> modifierOptionIds) {

        var now = clock.instant();

        // An unregistered channel code resolves to no channel rather than to a
        // default one, exactly as QuoteService does it: with a null pricing
        // channel no CHANNEL assignment can match, which is "this channel has no
        // special plane" and never "any plane will do".
        UUID pricingChannelId = channels.byCode(tenantId, channelCode)
                .map(SalesChannel::pricingChannelId)
                .orElse(null);

        return store.resolvePriceBook(tenantId, brandId, locationId, pricingChannelId, now)
                .map(book -> new MenuPrices(
                        book.currency(),
                        store.pricesFor(book.id(), "VARIANT", variantIds, now),
                        store.pricesFor(book.id(), "MODIFIER_OPTION", modifierOptionIds, now)));
    }
}
