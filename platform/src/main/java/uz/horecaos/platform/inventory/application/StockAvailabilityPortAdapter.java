package uz.horecaos.platform.inventory.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.inventory.api.StockAvailabilityPort;
import uz.horecaos.platform.inventory.api.StopSource;

/**
 * The {@code inventory.api} face of {@link InventoryService#setAvailabilityAudited}
 * (ADR 0060 §3), matching {@code OrderDecisionPortAdapter}'s shape: a
 * translation layer only, calling the identical, now-audited method the web
 * {@code InventoryController} calls.
 */
@Component
public class StockAvailabilityPortAdapter implements StockAvailabilityPort {

    private final InventoryService inventory;

    public StockAvailabilityPortAdapter(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Override
    public void toggle(
            UUID tenantId, UUID locationId, UUID variantId, boolean available, String reasonCode, String actorSubject) {
        // The Telegram /86 command is this port's one caller (the POS poll writes its own
        // stops through AvailabilityStopPort), so the source is BOT: the movement says so, and
        // a BOT stop on an UNTRACKED or QUANTITY dish is lifted by the same command.
        inventory.setAvailabilityAudited(
                tenantId, locationId, variantId, available, reasonCode, actorSubject, StopSource.BOT);
    }
}
