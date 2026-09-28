package uz.horecaos.platform.catalog.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Whether the published menu can actually sell a variant right now (ADR 0016,
 * ADR 0017; gap map rows 4.4c/4.4d, storefront half).
 *
 * <p>The sibling of {@link MenuPriceLookup}: the same "consumer declares the
 * contract, producer implements it" shape that keeps the dependency pointing
 * one way. Catalog never learns what a stock item is, what a reservation is,
 * or that a channel has a system type distinct from its own {@code channel}
 * code — inventory implements this and resolves that mapping on its own side
 * (mirroring how {@code PricingMenuPriceLookup} resolves a channel's price
 * plane from the same {@code channelCode}).
 *
 * <p>Before this port existed, {@code StorefrontCatalogQuery} answered
 * "orderable" from {@code catalog.location_offerings} alone: a QUANTITY item
 * at zero remaining, or a BINARY item an operator had stopped, still rendered
 * orderable on the live menu, and a customer could add it to a cart that only
 * failed — confusingly — at checkout's own inventory hold. This is the read
 * that closes that gap: the same channel-aware decision {@link
 * uz.horecaos.platform.inventory.application.InventoryService#checkAvailabilityForChannel}
 * already gives the console (row 4.4c's own per-channel-type stop threshold,
 * V0407), reused here rather than a second, storefront-local notion of
 * "available".
 */
public interface MenuAvailabilityLookup {

    /**
     * One batched read for a whole menu page — never one call per item, so a
     * hundred-dish menu costs the same two queries a five-dish one does.
     *
     * @param channelCode {@code tenant.sales_channels.code}, the same string
     *     {@link MenuPriceLookup#pricesFor} already takes; the implementation
     *     resolves it to a {@code system_type} itself so the per-channel-type
     *     stop threshold applies to the channel actually asking.
     * @return one entry per id in {@code variantIds} that inventory has an
     *     opinion about. An id absent from the result carries no additional
     *     restriction — the caller's own offering-based {@code orderable}
     *     stands unchanged — which keeps a caller under no obligation to
     *     stand up inventory fixtures for a variant it does not track;
     *     {@code InventoryMenuAvailabilityLookup}'s own doc says what
     *     "absent" means for that real implementation specifically (it does
     *     not leave any queried id out).
     */
    Map<UUID, VariantAvailability> availabilityFor(
            UUID tenantId, UUID brandId, UUID locationId, String channelCode, Set<UUID> variantIds);

    /**
     * @param orderable false means sold out (or not stocked at all) — the
     *     menu shows the dish rather than hiding it, same as an 86'd offering.
     * @param remainingQuantity set only for a QUANTITY item {@code
     *     catalog.use_stock_logic} enforces, and only once remaining stock
     *     has dropped to a small displayed threshold — never above it. ADR
     *     0017's own "quantity need not be exposed publicly" is honoured by
     *     omitting the field rather than by refusing the read.
     */
    record VariantAvailability(boolean orderable, @Nullable BigDecimal remainingQuantity) {

        public static VariantAvailability available() {
            return new VariantAvailability(true, null);
        }
    }
}
