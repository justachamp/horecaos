package uz.horecaos.platform.inventory.application;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.StockListingPort;
import uz.horecaos.platform.inventory.api.TrackingMode;

/**
 * The {@code inventory.api} face of {@link InventoryService#listVariantAtLocation}
 * (ADR 0099), matching {@link StockAvailabilityPortAdapter}'s shape: a
 * translation layer only, calling the same method {@code InventoryController}
 * calls.
 *
 * <p>The "already listed?" question is asked through {@link
 * InventoryService#checkAvailability} rather than a store read of its own, for
 * two reasons. It binds the tenant's RLS session the way every other inventory
 * read does, and it is the exact question {@code ACTIVATION_SMOKE_TEST} will ask
 * afterwards — so a stock item this adapter reports as present is one that check
 * will also find.
 */
@Component
public class StockListingPortAdapter implements StockListingPort {

    /** {@code AvailabilityDecision.Unavailable#notStocked}: no stock item exists at all. */
    private static final String NOT_STOCKED = "NOT_STOCKED_AT_LOCATION";

    private final InventoryService inventory;

    public StockListingPortAdapter(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Override
    @Transactional
    public boolean ensureListed(UUID tenantId, UUID brandId, UUID locationId, UUID variantId) {
        AvailabilityDecision decision = inventory.checkAvailability(tenantId, locationId, Set.of(variantId));
        boolean missing = decision.unavailableItems().stream()
                .anyMatch(item ->
                        NOT_STOCKED.equals(item.reason()) && item.variantId().equals(variantId));
        if (!missing) {
            // Either available, or present and deliberately sold out. Both are
            // states this must not overwrite.
            return false;
        }
        // BINARY rather than UNTRACKED: a kitchen has to be able to 86 a sample
        // dish like any other, and a BINARY item starts available.
        inventory.listVariantAtLocation(tenantId, brandId, locationId, variantId, TrackingMode.BINARY);
        return true;
    }
}
