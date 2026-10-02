package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.integration.marketplace.MarketplacePropagationQuery;
import uz.horecaos.platform.integration.marketplace.MarketplacePropagationQuery.Binding;
import uz.horecaos.platform.integration.marketplace.MarketplacePropagationQuery.Item;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Per-binding propagation status for one branch (ADR 0141): what each connected marketplace has
 * and has not been told about the stop list, and since when. A provider with no availability
 * write API shows {@code MANUAL} — "not propagated automatically" — never a quiet "in sync".
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/inventory")
@Tag(name = "Marketplace propagation", description = "What each marketplace has been told about the stop list")
public class MarketplacePropagationController {

    private final MarketplacePropagationQuery propagation;

    public MarketplacePropagationController(MarketplacePropagationQuery propagation) {
        this.propagation = propagation;
    }

    @GetMapping("/marketplace-propagation")
    @RequiresCapability(value = Capability.INVENTORY_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Per marketplace binding: pending and unconfirmed items, and since when",
            description = "ADR 0141. mode is AUTOMATIC (the platform pushes and these counts say how "
                    + "many items it has not been able to confirm), MANUAL (this provider has no "
                    + "availability write API: nothing is pushed, update the partner portal by hand) "
                    + "or SUSPENDED (the reconcile switch is off). No item names and no provider "
                    + "response bodies: identifiers, counts, timestamps and stable codes.")
    public ResponseEntity<PropagationResponse> propagation(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return ResponseEntity.ok(new PropagationResponse(propagation.at(tenantId, locationId).stream()
                .map(BindingResponse::of)
                .toList()));
    }

    public record PropagationResponse(List<BindingResponse> bindings) {}

    public record BindingResponse(
            UUID bindingId,
            String providerType,
            String displayName,
            String mode,
            int inSync,
            int pending,
            int uncertain,
            int rejectedUnmapped,
            int unconfirmed,
            @Nullable Instant oldestUnconfirmedSince,
            @Nullable Instant lastSuccessAt,
            @Nullable Instant lastFailureAt,
            @Nullable String lastFailureCode,
            List<ItemResponse> unconfirmedItems) {

        static BindingResponse of(Binding binding) {
            return new BindingResponse(
                    binding.bindingId(),
                    binding.providerType(),
                    binding.displayName(),
                    binding.mode().name(),
                    binding.inSync(),
                    binding.pending(),
                    binding.uncertain(),
                    binding.rejectedUnmapped(),
                    binding.pending() + binding.uncertain() + binding.rejectedUnmapped(),
                    binding.oldestUnconfirmedSince(),
                    binding.lastSuccessAt(),
                    binding.lastFailureAt(),
                    binding.lastFailureCode(),
                    binding.unconfirmedItems().stream().map(ItemResponse::of).toList());
        }
    }

    public record ItemResponse(
            UUID variantId,
            boolean desiredAvailable,
            @Nullable Boolean confirmedAvailable,
            String state,
            @Nullable Instant since,
            @Nullable String lastFailureCode) {

        static ItemResponse of(Item item) {
            return new ItemResponse(
                    item.variantId(),
                    item.desiredAvailable(),
                    item.confirmedAvailable(),
                    item.state(),
                    item.since(),
                    item.lastFailureCode());
        }
    }
}
