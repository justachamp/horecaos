package uz.horecaos.platform.inventory.infrastructure.catalog;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.MenuAvailabilityLookup;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
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

    /**
     * The remaining count is shown at or below this many units, and never
     * above it — ADR 0017's own "quantity need not be exposed publicly".
     * Conservative and undecided rather than tenant-configurable: no ADR
     * names a merchant-facing "low stock" setting, and inventing one is a
     * decision this wave does not need to make to close the gap (a fixed,
     * documented threshold does).
     */
    static final BigDecimal LOW_STOCK_DISPLAY_THRESHOLD = BigDecimal.valueOf(5);

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

        // An unregistered channel code resolves to no system type rather than
        // to a default one, the same "no plane matches" shape
        // PricingMenuPriceLookup uses for its own channel resolution. Every
        // per-channel-type threshold simply fails to match, which is
        // identical to how checkAvailabilityForChannel already treats a null
        // channel: no early cutoff, real stock exhaustion only.
        String channelSystemType = channels.byCode(tenantId, channelCode)
                .map(SalesChannel::systemType)
                .map(Enum::name)
                .orElse(null);

        AvailabilityDecision decision =
                inventory.checkAvailabilityForChannel(tenantId, locationId, variantIds, channelSystemType);
        Map<UUID, String> unavailableReasons = new HashMap<>();
        decision.unavailableItems().forEach(item -> unavailableReasons.put(item.variantId(), item.reason()));

        Map<UUID, BigDecimal> remaining = inventory.remainingQuantitiesFor(tenantId, locationId, variantIds);

        Map<UUID, VariantAvailability> result = new HashMap<>();
        for (UUID variantId : variantIds) {
            boolean orderable = !unavailableReasons.containsKey(variantId);
            BigDecimal own = remaining.get(variantId);
            boolean showRemaining = orderable && own != null && own.compareTo(LOW_STOCK_DISPLAY_THRESHOLD) <= 0;
            result.put(variantId, new VariantAvailability(orderable, showRemaining ? own : null));
        }
        return result;
    }
}
