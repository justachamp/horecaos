package uz.horecaos.platform.inventory.web;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Availability at a location (ADR 0017).
 *
 * <p>The first slice tracks availability, not quantity: a kitchen marks a dish on
 * or off, and there is no portion count to oversell. Marking something sold out
 * takes effect immediately, because a kitchen mid-service cannot wait for a
 * republish.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/inventory")
@Tag(name = "Inventory", description = "Binary availability and stock listing at a location")
public class InventoryController {

    private final InventoryService inventory;
    private final CurrentActor currentActor;

    public InventoryController(InventoryService inventory, CurrentActor currentActor) {
        this.inventory = inventory;
        this.currentActor = currentActor;
    }

    @PostMapping("/stock-items")
    @RequiresCapability(value = Capability.INVENTORY_ADJUST, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "List a variant as stocked at this location",
            description = "A variant with no stock item is unavailable rather than available, so "
                    + "listing it is what makes it orderable here.")
    public ResponseEntity<StockItemResponse> listVariant(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId,
            @Valid @RequestBody ListVariantRequest body) {
        try {
            UUID stockItemId = inventory.listVariantAtLocation(
                    tenantId, brandId, locationId, body.variantId(), body.trackingMode());
            return ResponseEntity.ok(new StockItemResponse(stockItemId, body.trackingMode().name()));
        } catch (InventoryService.UnsupportedTrackingModeException unsupported) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, unsupported.getMessage());
        }
    }

    @PutMapping("/variants/{variantId}/availability")
    @RequiresCapability(value = Capability.INVENTORY_ADJUST, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "Mark a dish available or sold out",
            description = "Takes effect immediately. Every change records a movement with its "
                    + "reason, so \"why was this sold out at 19:00\" has an answer.")
    public ResponseEntity<Void> setAvailability(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId, @PathVariable UUID variantId,
            @Valid @RequestBody AvailabilityRequest body) {
        try {
            inventory.setAvailability(tenantId, locationId, variantId, body.available(),
                    body.reasonCode(), actorId());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        } catch (IllegalStateException wrongMode) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, wrongMode.getMessage());
        }
    }

    @GetMapping("/availability")
    @RequiresCapability(value = Capability.INVENTORY_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "Check whether variants can be fulfilled here",
            description = "Names every unavailable item, because a customer told only that "
                    + "something is unavailable has to guess which.")
    public ResponseEntity<AvailabilityDecision> checkAvailability(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam @NotEmpty @Size(max = 100) List<UUID> variantIds) {
        return ResponseEntity.ok(
                inventory.checkAvailability(tenantId, locationId, Set.copyOf(variantIds)));
    }

    private UUID actorId() {
        try {
            return UUID.fromString(currentActor.get().subject());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    public record ListVariantRequest(@NotNull UUID variantId, @NotNull TrackingMode trackingMode) { }

    public record AvailabilityRequest(boolean available, @Size(max = 64) String reasonCode) { }

    public record StockItemResponse(UUID stockItemId, String trackingMode) { }
}
