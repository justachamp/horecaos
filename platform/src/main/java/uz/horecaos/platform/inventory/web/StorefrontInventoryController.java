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
 * how much is left. {@code remainingQuantity} is only ever populated for a
 * QUANTITY item with {@code catalog.use_stock_logic} on for the tenant — ADR
 * 0017's own "quantity need not be exposed publicly" is honoured by omitting
 * the field rather than by refusing the whole read, the same shape {@code
 * AvailabilityDecision} already uses for "unavailable, and here is why".
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
            description = "The same channel-aware check the operations console uses (GET "
                    + ".../availability?channel=...), scoped to one variant. The channel is "
                    + "required (tenant.sales_channels.system_type, e.g. WEB) so a per-channel-type "
                    + "stop threshold (gap map row 4.4c) applies to the channel actually asking.")
    public ResponseEntity<StorefrontAvailabilityResponse> availability(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId,
            @PathVariable UUID variantId,
            @RequestParam String channel) {
        AvailabilityDecision decision =
                inventory.checkAvailabilityForChannel(tenantId, locationId, Set.of(variantId), channel);
        BigDecimal remaining = decision.available()
                ? inventory
                        .findStockPosition(tenantId, locationId, variantId)
                        .map(InventoryService.StockPositionView::remainingQuantity)
                        .orElse(null)
                : null;
        return ResponseEntity.ok(new StorefrontAvailabilityResponse(decision.available(), remaining));
    }

    /** {@code remainingQuantity} is null whenever the item is not QUANTITY-tracked, or is unavailable. */
    public record StorefrontAvailabilityResponse(
            boolean available, @Nullable BigDecimal remainingQuantity) {}
}
