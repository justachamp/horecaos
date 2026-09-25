package uz.horecaos.platform.inventory.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import uz.horecaos.platform.inventory.application.InventoryService.ChannelStopThresholdView;
import uz.horecaos.platform.inventory.application.InventoryService.StockPositionView;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Availability and stock at a location (ADR 0017).
 *
 * <p>Every tracking mode lives here: a BINARY item's kitchen on/off toggle,
 * an UNTRACKED item's unlimited default, and — gap map row 4.4c — a
 * QUANTITY item's on-hand count, its daily default and reset, and its
 * per-channel-type stop thresholds. Marking something sold out or setting
 * on-hand both take effect immediately, because a kitchen mid-service cannot
 * wait for a republish.
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
                    + "something is unavailable has to guess which. An optional channel "
                    + "(tenant.sales_channels.system_type, e.g. AGGREGATOR) also applies that "
                    + "channel type's own per-item stop threshold for a QUANTITY item (gap map "
                    + "row 4.4c) — omitted, this is the plain stock check with no channel cutoff.")
    public ResponseEntity<AvailabilityDecision> checkAvailability(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam @NotEmpty @Size(max = 100) List<UUID> variantIds,
            @RequestParam(required = false) @Nullable String channel) {
        return ResponseEntity.ok(
                channel == null
                        ? inventory.checkAvailability(tenantId, locationId, Set.copyOf(variantIds))
                        : inventory.checkAvailabilityForChannel(tenantId, locationId, Set.copyOf(variantIds), channel));
    }

    @GetMapping("/positions")
    @RequiresCapability(value = Capability.INVENTORY_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Every stock item at this location, with its position",
            description = "The console's per-location stock page (gap map row 4.4c): on-hand, "
                    + "reserved and remaining for a QUANTITY item, its daily default and last "
                    + "reset date, and any per-channel-type stop thresholds. On-hand/reserved are "
                    + "reported as zero, and remaining as null, for a BINARY or UNTRACKED item — "
                    + "those columns carry no meaning outside QUANTITY tracking.")
    public ResponseEntity<List<StockPositionResponse>> listPositions(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return ResponseEntity.ok(inventory.listStockPositions(tenantId, locationId).stream()
                .map(StockPositionResponse::of)
                .toList());
    }

    @PutMapping("/variants/{variantId}/on-hand")
    @RequiresCapability(value = Capability.INVENTORY_ADJUST, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Set a QUANTITY item's on-hand count",
            description = "A physical recount, a delivery received, breakage found — every change "
                    + "is a CORRECTION movement with its reason (ADR 0017's own API list: POST "
                    + ".../adjustments) and an ADR 0027 audit fact. Refuses on a BINARY or "
                    + "UNTRACKED item, and on a variant nobody listed here at all.")
    public ResponseEntity<Void> setOnHand(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID variantId,
            @Valid @RequestBody OnHandRequest body) {
        try {
            inventory.setOnHandQuantity(
                    tenantId,
                    locationId,
                    variantId,
                    body.quantity(),
                    body.reasonCode(),
                    currentActor.get().subject());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, unknown.getMessage());
        } catch (IllegalStateException wrongMode) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, wrongMode.getMessage());
        }
    }

    @PutMapping("/variants/{variantId}/quantity-defaults")
    @RequiresCapability(value = Capability.INVENTORY_ADJUST, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Set (or clear) a QUANTITY item's daily reset target",
            description = "Every business day (ADR 0043's own boundary for this tenant), "
                    + "InventoryQuantityResetScheduler resets on-hand to this value. A null "
                    + "quantity turns the scheduled reset off for this item.")
    public ResponseEntity<Void> setQuantityDefault(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID variantId,
            @Valid @RequestBody QuantityDefaultRequest body) {
        try {
            inventory.setDefaultQuantity(
                    tenantId,
                    locationId,
                    variantId,
                    body.defaultQuantity(),
                    body.reasonCode(),
                    currentActor.get().subject());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, unknown.getMessage());
        } catch (IllegalStateException wrongMode) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, wrongMode.getMessage());
        }
    }

    @PutMapping("/variants/{variantId}/channel-stop-thresholds/{channelType}")
    @RequiresCapability(value = Capability.INVENTORY_ADJUST, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Name the remaining quantity a channel type stops selling this item at",
            description = "gap map row 4.4c: e.g. AGGREGATOR stopping at 3 while the storefront "
                    + "keeps selling to zero. Never enforced by the reservation/hold path — only "
                    + "by the channel-aware availability read (GET .../availability?channel=...).")
    public ResponseEntity<Void> setChannelStopThreshold(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID variantId,
            @PathVariable String channelType,
            @Valid @RequestBody ChannelStopThresholdRequest body) {
        try {
            inventory.setChannelStopThreshold(
                    tenantId,
                    locationId,
                    variantId,
                    channelType,
                    body.stopAtOrBelow(),
                    body.reasonCode(),
                    currentActor.get().subject());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, unknown.getMessage());
        } catch (IllegalStateException wrongMode) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, wrongMode.getMessage());
        }
    }

    @DeleteMapping("/variants/{variantId}/channel-stop-thresholds/{channelType}")
    @RequiresCapability(value = Capability.INVENTORY_ADJUST, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Remove a channel type's stop threshold",
            description = "That channel goes back to selling to zero, like every channel with no "
                    + "threshold configured at all.")
    public ResponseEntity<Void> clearChannelStopThreshold(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID variantId,
            @PathVariable String channelType,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z_]{1,48}$") @Size(max = 64) String reasonCode) {
        try {
            boolean removed = inventory.clearChannelStopThreshold(
                    tenantId,
                    locationId,
                    variantId,
                    channelType,
                    reasonCode,
                    currentActor.get().subject());
            return removed
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, unknown.getMessage());
        } catch (IllegalStateException wrongMode) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, wrongMode.getMessage());
        }
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

    /** {@code reasonCode} is a short enumerated code — see {@link AvailabilityRequest}'s own doc. */
    public record OnHandRequest(
            @NotNull @DecimalMin(value = "0", message = "must not be negative")
            BigDecimal quantity,

            @NotBlank @Pattern(regexp = "^[A-Z_]{1,48}$") @Size(max = 64)
            String reasonCode) {}

    /** A null {@code defaultQuantity} turns the scheduled reset off for this item. */
    public record QuantityDefaultRequest(
            @Nullable @DecimalMin(value = "0", message = "must not be negative")
            BigDecimal defaultQuantity,

            @NotBlank @Pattern(regexp = "^[A-Z_]{1,48}$") @Size(max = 64)
            String reasonCode) {}

    public record ChannelStopThresholdRequest(
            @NotNull @DecimalMin(value = "0", message = "must not be negative")
            BigDecimal stopAtOrBelow,

            @NotBlank @Pattern(regexp = "^[A-Z_]{1,48}$") @Size(max = 64)
            String reasonCode) {}

    public record ChannelStopThresholdResponse(String channelSystemType, BigDecimal stopAtOrBelow) {
        static ChannelStopThresholdResponse of(ChannelStopThresholdView view) {
            return new ChannelStopThresholdResponse(view.channelSystemType(), view.stopAtOrBelow());
        }
    }

    public record StockPositionResponse(
            UUID stockItemId,
            UUID variantId,
            String trackingMode,
            @Nullable Boolean binaryAvailable,
            BigDecimal onHandQuantity,
            BigDecimal reservedQuantity,
            @Nullable BigDecimal remainingQuantity,
            @Nullable BigDecimal defaultQuantity,
            @Nullable LocalDate lastResetBusinessDate,
            List<ChannelStopThresholdResponse> channelStopThresholds) {
        static StockPositionResponse of(StockPositionView view) {
            return new StockPositionResponse(
                    view.stockItemId(),
                    view.variantId(),
                    view.trackingMode().name(),
                    view.binaryAvailable(),
                    view.onHandQuantity(),
                    view.reservedQuantity(),
                    view.remainingQuantity(),
                    view.defaultQuantity(),
                    view.lastResetBusinessDate(),
                    view.channelStopThresholds().stream()
                            .map(ChannelStopThresholdResponse::of)
                            .toList());
        }
    }
}
