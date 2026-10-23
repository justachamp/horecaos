package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.catalog.api.ItemDisplayLookup;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.integration.marketplace.MarketplacePropagationQuery;
import uz.horecaos.platform.integration.marketplace.MarketplacePropagationQuery.Binding;
import uz.horecaos.platform.integration.marketplace.MarketplacePropagationQuery.Item;
import uz.horecaos.platform.integration.marketplace.MarketplacePropagationQuery.WouldPush;
import uz.horecaos.platform.integration.marketplace.MarketplacePropagationQuery.WouldPushPage;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
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
    private final ItemDisplayLookup itemNames;

    public MarketplacePropagationController(MarketplacePropagationQuery propagation, ItemDisplayLookup itemNames) {
        this.propagation = propagation;
        this.itemNames = itemNames;
    }

    @GetMapping("/marketplace-propagation")
    @RequiresCapability(value = Capability.INVENTORY_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Per marketplace binding: pending and unconfirmed items, and since when",
            description = "ADR 0141. mode is AUTOMATIC (the platform pushes and these counts say how "
                    + "many items it has not been able to confirm), MANUAL (the platform is not "
                    + "pushing to this marketplace: update the partner portal by hand; reason is "
                    + "NO_ADAPTER, INSTALLATION_INACTIVE, CHANNEL_UNRESOLVED or NO_ITEMS_TRACKED) "
                    + "or SUSPENDED (the reconcile switch is off) or DRY_RUN (ADR 0141 Phase 3: the binding "
                    + "rehearses, wouldPush counts the dishes the platform has recorded as would-be pushes and "
                    + "nothing has been sent; unconfirmedItems is a sample, and GET "
                    + ".../marketplace-propagation/{bindingId}/would-push is the whole list). incidentOpenSince is set while a stale-channel incident is open "
                    + "on an automatic binding and clears by itself on recovery (ADR 0180). No item names and no "
                    + "provider response bodies: identifiers, counts, timestamps and stable codes.")
    public ResponseEntity<PropagationResponse> propagation(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return ResponseEntity.ok(new PropagationResponse(propagation.at(tenantId, locationId).stream()
                .map(BindingResponse::of)
                .toList()));
    }

    @GetMapping("/marketplace-propagation/{bindingId}/would-push")
    @RequiresCapability(value = Capability.INVENTORY_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Every dish a dry-run marketplace binding would push, with the dish's name",
            description = "ADR 0141 Phase 3, ADR 0180 Decision 3. The whole of a rehearsal's output, one decision "
                    + "per dish, for the operator to compare with the partner's own portal: the list behind "
                    + "the propagation read's wouldPush count, which is this list's size. Ordered by "
                    + "externalItemReference (the partner's own id for the item). Cursor-paged; cursor is the "
                    + "externalItemReference of the last dish seen. The dish's name is resolved through the "
                    + "catalog when the list is read and is absent where the dish no longer resolves; "
                    + "nothing but identifiers, the decision and its instant is stored. Empty while the binding "
                    + "is not rehearsing. 404 for a binding that is not an active marketplace binding of this "
                    + "branch.")
    public Page<WouldPushItemResponse> wouldPush(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID bindingId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int pageSize = Page.limitOrDefault(limit);
        WouldPushPage page = propagation
                .wouldPush(
                        tenantId, locationId, bindingId, cursor == null || cursor.isBlank() ? null : cursor, pageSize)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such marketplace binding"));
        Map<UUID, String> names = itemNames.displayNames(
                tenantId, page.items().stream().map(WouldPush::variantId).collect(Collectors.toSet()));
        return new Page<>(
                page.items().stream()
                        .map(item -> WouldPushItemResponse.of(item, names.get(item.variantId())))
                        .toList(),
                page.nextCursor());
    }

    public record PropagationResponse(List<BindingResponse> bindings) {}

    /**
     * One decision of a dry run.
     *
     * @param dishName the dish's name, resolved when the list is read; null when the dish no longer resolves
     * @param externalItemReference the partner's own id for the item, to find it in their portal
     * @param wouldPushAvailable what the platform would have set the dish to
     * @param confirmedAvailable what the platform last knew the partner to hold; null when it has never been
     *     told or its belief was withdrawn
     */
    public record WouldPushItemResponse(
            UUID variantId,
            @Nullable String dishName,
            String externalItemReference,
            boolean wouldPushAvailable,
            Instant wouldPushAt,
            @Nullable Boolean confirmedAvailable) {

        static WouldPushItemResponse of(WouldPush item, @Nullable String dishName) {
            return new WouldPushItemResponse(
                    item.variantId(),
                    dishName,
                    item.externalItemReference(),
                    item.wouldPushAvailable(),
                    item.wouldPushAt(),
                    item.confirmedAvailable());
        }
    }

    public record BindingResponse(
            UUID bindingId,
            String providerType,
            String displayName,
            String mode,
            @Nullable String reason,
            int inSync,
            int pending,
            int uncertain,
            int rejectedUnmapped,
            int unconfirmed,
            @Nullable Instant oldestUnconfirmedSince,
            @Nullable Instant lastSuccessAt,
            @Nullable Instant lastFailureAt,
            @Nullable String lastFailureCode,
            List<ItemResponse> unconfirmedItems,
            int wouldPush,
            @Nullable Instant incidentOpenSince) {

        static BindingResponse of(Binding binding) {
            return new BindingResponse(
                    binding.bindingId(),
                    binding.providerType(),
                    binding.displayName(),
                    binding.mode().name(),
                    binding.reason() == null ? null : binding.reason().name(),
                    binding.inSync(),
                    binding.pending(),
                    binding.uncertain(),
                    binding.rejectedUnmapped(),
                    binding.pending() + binding.uncertain() + binding.rejectedUnmapped(),
                    binding.oldestUnconfirmedSince(),
                    binding.lastSuccessAt(),
                    binding.lastFailureAt(),
                    binding.lastFailureCode(),
                    binding.unconfirmedItems().stream().map(ItemResponse::of).toList(),
                    binding.wouldPush(),
                    binding.incidentOpenSince());
        }
    }

    public record ItemResponse(
            UUID variantId,
            boolean desiredAvailable,
            @Nullable Boolean confirmedAvailable,
            String state,
            @Nullable Instant since,
            @Nullable String lastFailureCode,
            @Nullable Boolean wouldPushAvailable,
            @Nullable Instant wouldPushAt) {

        static ItemResponse of(Item item) {
            return new ItemResponse(
                    item.variantId(),
                    item.desiredAvailable(),
                    item.confirmedAvailable(),
                    item.state(),
                    item.since(),
                    item.lastFailureCode(),
                    item.wouldPushAvailable(),
                    item.wouldPushAt());
        }
    }
}
