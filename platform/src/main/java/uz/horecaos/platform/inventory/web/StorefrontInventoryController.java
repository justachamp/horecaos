package uz.horecaos.platform.inventory.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.application.InventoryService;

/**
 * The storefront's own availability read (ADR 0017, gap map row 4.4c) —
 * "the storefront/operator availability read returning remaining quantity
 * where tracking is QUANTITY."
 *
 * <p>Unauthenticated by design, matching {@code StorefrontCatalogController}'s
 * own stance: a customer browsing before they have an account still needs to
 * know whether a dish is orderable and, where the tenant tracks it, roughly
 * how much is left. {@code remainingQuantity} is only ever populated for an
 * orderable QUANTITY item with {@code catalog.use_stock_logic} on for the
 * tenant, and even then only at or below {@link
 * InventoryService#LOW_STOCK_DISPLAY_THRESHOLD} — ADR 0017's own "quantity
 * need not be exposed publicly" is honoured by omitting the field, the same
 * gate {@code InventoryMenuAvailabilityLookup} applies to the published
 * menu's own read, rather than by refusing the whole read the way {@code
 * AvailabilityDecision} already handles "unavailable, and here is why".
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/locations/{locationId}")
@Tag(name = "Storefront inventory", description = "Availability and remaining quantity a customer can see")
public class StorefrontInventoryController {

    private final InventoryService inventory;

    public StorefrontInventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/variants/{variantId}/availability")
    @Operation(
            summary = "Whether a variant is orderable here, and its remaining quantity if tracked",
            description = "The same channel-aware check the storefront menu and the cart make, "
                    + "scoped to one variant. The channel is required and is the tenant's own "
                    + "channel code, as on the menu read (ADR 0141): the stops that cover that "
                    + "channel apply, and so does its channel type's stop threshold (gap map row "
                    + "4.4c). A value that names no registered channel is read as a bare channel "
                    + "type (e.g. WEB), for which only the stops covering every channel apply.")
    public ResponseEntity<StorefrontAvailabilityResponse> availability(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId,
            @PathVariable UUID variantId,
            @RequestParam String channel) {
        AvailabilityDecision decision =
                inventory.checkAvailabilityOnChannelCode(tenantId, locationId, Set.of(variantId), channel);
        BigDecimal own = decision.available()
                ? inventory
                        .findStockPosition(tenantId, locationId, variantId)
                        .map(InventoryService.StockPositionView::remainingQuantity)
                        .orElse(null)
                : null;
        // The same low-stock gate the menu read applies (gap map row
        // 4.4c/4.4d's storefront half, InventoryMenuAvailabilityLookup): a
        // customer sees the count only at or below the shared threshold,
        // never an exact large number. This endpoint used to return `own`
        // unconditionally, leaking the raw remainingQuantity above it.
        BigDecimal remaining =
                own != null && own.compareTo(InventoryService.LOW_STOCK_DISPLAY_THRESHOLD) <= 0 ? own : null;
        return ResponseEntity.ok(new StorefrontAvailabilityResponse(decision.available(), remaining));
    }

    /**
     * {@code remainingQuantity} is null whenever the item is not
     * QUANTITY-tracked, is unavailable, or its own count sits above {@link
     * InventoryService#LOW_STOCK_DISPLAY_THRESHOLD}.
     */
    public record StorefrontAvailabilityResponse(
            boolean available, @Nullable BigDecimal remainingQuantity) {}
}
