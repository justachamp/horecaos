package uz.horecaos.platform.inventory.infrastructure.catalog;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.MenuAvailabilityLookup;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.ChannelContext;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * Inventory's answer to catalog's {@link MenuAvailabilityLookup} (ADR 0016,
 * ADR 0017; gap map rows 4.4c/4.4d, storefront half).
 *
 * <p>Two batched reads, never one per item: {@link
 * InventoryService#checkAvailabilityForChannel} for the orderable decision
 * (which also applies row 4.4c's per-channel-type stop threshold once the
 * channel code is resolved to its {@code system_type} below), and {@link
 * InventoryService#remainingQuantitiesFor} for the QUANTITY items' own
 * counts. Both are scoped to exactly the menu page's own variant set.
 *
 * <p><strong>Every requested id gets an answer.</strong> Unlike the port's
 * own "absent means no opinion" contract — deliberately lenient, so a test
 * double need not enumerate every id — this real implementation always
 * populates one, because {@code checkAvailabilityForChannel} already does:
 * a variant with no stock item at this location comes back {@code
 * NOT_STOCKED_AT_LOCATION}, unavailable, exactly as {@code
 * CheckoutReservationStep}'s own hold would refuse it. A menu that told a
 * customer an item was orderable only for checkout to refuse it moments
 * later is the exact inconsistency this class exists to remove.
 */
@Component
public class InventoryMenuAvailabilityLookup implements MenuAvailabilityLookup {

    private final InventoryService inventory;
    private final SalesChannelLookup channels;

    public InventoryMenuAvailabilityLookup(InventoryService inventory, SalesChannelLookup channels) {
        this.inventory = inventory;
        this.channels = channels;
    }

    @Override
    public Map<UUID, VariantAvailability> availabilityFor(
            UUID tenantId, UUID brandId, UUID locationId, String channelCode, Set<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }

        // An unregistered channel code resolves to no channel at all rather than to a
        // default one, the same "no plane matches" shape PricingMenuPriceLookup uses for
        // its own channel resolution. With no channel id only the stops that cover every
        // channel apply, and with no system type every per-channel-type threshold simply
        // fails to match: identical to how a null channel was always treated -- no early
        // cutoff, real stock exhaustion only.
        Optional<SalesChannel> channel = channels.byCode(tenantId, channelCode);
        ChannelContext context = new ChannelContext(
                channel.map(SalesChannel::id).orElse(null),
                channel.map(SalesChannel::systemType).map(Enum::name).orElse(null));

        AvailabilityDecision decision = inventory.checkAvailabilityOnMenu(tenantId, locationId, variantIds, context);
        Map<UUID, String> unavailableReasons = new HashMap<>();
        decision.unavailableItems().forEach(item -> unavailableReasons.put(item.variantId(), item.reason()));

        Map<UUID, BigDecimal> remaining = inventory.remainingQuantitiesFor(tenantId, locationId, variantIds);

        Map<UUID, VariantAvailability> result = new HashMap<>();
        for (UUID variantId : variantIds) {
            boolean orderable = !unavailableReasons.containsKey(variantId);
            BigDecimal own = remaining.get(variantId);
            boolean showRemaining =
                    orderable && own != null && own.compareTo(InventoryService.LOW_STOCK_DISPLAY_THRESHOLD) <= 0;
            result.put(variantId, new VariantAvailability(orderable, showRemaining ? own : null));
        }
        return result;
    }
}
