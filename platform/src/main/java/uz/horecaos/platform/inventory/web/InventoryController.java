package uz.horecaos.platform.inventory.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryBulkAvailabilityService;
import uz.horecaos.platform.inventory.application.InventoryBulkAvailabilityService.ItemOutcome;
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
    private final InventoryBulkAvailabilityService bulkAvailability;
    private final CurrentActor currentActor;

    public InventoryController(
            InventoryService inventory, InventoryBulkAvailabilityService bulkAvailability, CurrentActor currentActor) {
        this.inventory = inventory;
        this.bulkAvailability = bulkAvailability;
        this.currentActor = currentActor;
    }

    @PostMapping("/stock-items")
    @RequiresCapability(value = Capability.INVENTORY_ADJUST, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "List a variant as stocked at this location",
            description = "A variant with no stock item is unavailable rather than available, so "
                    + "listing it is what makes it orderable here.")
    public ResponseEntity<StockItemResponse> listVariant(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody ListVariantRequest body) {
        // InventoryService.UnsupportedTrackingModeException (QUANTITY is not
        // implemented) is mapped to a legible Problem Details response by
        // InventoryApiErrorHandler, shared with every other endpoint on this
        // controller rather than caught here alone.
        UUID stockItemId =
                inventory.listVariantAtLocation(tenantId, brandId, locationId, body.variantId(), body.trackingMode());
        return ResponseEntity.ok(
                new StockItemResponse(stockItemId, body.trackingMode().name()));
    }

    @PutMapping("/variants/{variantId}/availability")
    @RequiresCapability(value = Capability.INVENTORY_ADJUST, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Mark a dish available or sold out",
            description = "Takes effect immediately. Every change records a movement with its "
                    + "reason, so \"why was this sold out at 19:00\" has an answer.")
    public ResponseEntity<Void> setAvailability(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID variantId,
            @Valid @RequestBody AvailabilityRequest body) {
        try {
            inventory.setAvailabilityAudited(
                    tenantId,
                    locationId,
                    variantId,
                    body.available(),
                    body.reasonCode(),
                    currentActor.get().subject());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        } catch (IllegalStateException wrongMode) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, wrongMode.getMessage());
        }
    }

    @PostMapping("/variants/bulk-availability")
    @RequiresCapability(value = Capability.INVENTORY_AVAILABILITY_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Marks many dishes available or sold out in one gesture",
            description = "The stop list's own bulk stop/unstop (gap map row 2.5), modelled on "
                    + "ADR 0039's bulk contract: every variant is applied independently through "
                    + "the same audited single-item toggle and reported with its own outcome, "
                    + "capped at 200 per request. Idempotent like every other mutating endpoint "
                    + "(ADR 0031's Idempotency-Key); a variant already in its target state is "
                    + "reported applied with nothing changed, the single toggle's own no-op rule.")
    public ResponseEntity<BulkAvailabilityResponse> bulkSetAvailability(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody BulkAvailabilityRequest body) {
        try {
            List<ItemOutcome> outcomes = bulkAvailability.apply(
                    tenantId,
                    locationId,
                    body.variantIds(),
                    body.available(),
                    body.reasonCode(),
                    currentActor.get().subject());
            long applied = outcomes.stream()
                    .filter(outcome -> outcome.status() == InventoryBulkAvailabilityService.ItemStatus.APPLIED)
                    .count();
            return ResponseEntity.ok(new BulkAvailabilityResponse(
                    body.variantIds().size(),
                    (int) applied,
                    body.variantIds().size() - (int) applied,
                    outcomes.stream().map(BulkAvailabilityOutcome::of).toList()));
        } catch (IllegalArgumentException tooMany) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, tooMany.getMessage());
        }
    }

    @GetMapping("/availability")
    @RequiresCapability(value = Capability.INVENTORY_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Check whether variants can be fulfilled here",
            description = "Names every unavailable item, because a customer told only that "
                    + "something is unavailable has to guess which.")
    public ResponseEntity<AvailabilityDecision> checkAvailability(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam @NotEmpty @Size(max = 100) List<UUID> variantIds) {
        return ResponseEntity.ok(inventory.checkAvailability(tenantId, locationId, Set.copyOf(variantIds)));
    }

    public record ListVariantRequest(
            @NotNull UUID variantId, @NotNull TrackingMode trackingMode) {}

    /**
     * {@code reasonCode} is a short enumerated code — never free text an
     * operator typed, per ADR 0029 — matching {@code
     * CatalogAuthoringController.SetChannelOfferingRequest}'s own convention:
     * it is kept permanently in the ADR 0027 audit trail and echoed back on
     * every read of this variant's stop reason.
     */
    public record AvailabilityRequest(
            boolean available,

            @Pattern(regexp = "^[A-Z_]{1,48}$") @Size(max = 64)
            String reasonCode) {}

    public record StockItemResponse(UUID stockItemId, String trackingMode) {}

    /** {@code reasonCode} is a short enumerated code — see {@link AvailabilityRequest}'s own doc. */
    public record BulkAvailabilityRequest(
            @NotEmpty @Size(max = InventoryBulkAvailabilityService.MAX_ITEMS)
            List<UUID> variantIds,

            boolean available,

            @NotBlank @Pattern(regexp = "^[A-Z_]{1,48}$") @Size(max = 64)
            String reasonCode) {}

    public record BulkAvailabilityOutcome(
            UUID variantId,
            String status,
            boolean changed,
            @Nullable String problemCode) {
        static BulkAvailabilityOutcome of(ItemOutcome outcome) {
            return new BulkAvailabilityOutcome(
                    outcome.variantId(), outcome.status().name(), outcome.changed(), outcome.problemCode());
        }
    }

    public record BulkAvailabilityResponse(
            int requestedCount, int appliedCount, int failedCount, List<BulkAvailabilityOutcome> items) {}
}
