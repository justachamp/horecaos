package uz.horecaos.platform.inventory.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.inventory.application.OfferingListingBackfillService;
import uz.horecaos.platform.inventory.application.OfferingListingBackfillService.VariantBackfillResult;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The product editor's own "not listed at N branches" read and one-click
 * action (gap map row 4.1's pilot-critical backfill) — {@link
 * OfferingListingBackfillService#backfillLocation} looked at one location's
 * whole menu; this looks at one variant across a brand's every location
 * instead, the direction the Availability tab actually needs.
 *
 * <p>Brand-scoped rather than location-scoped, matching {@code
 * CatalogAuthoringController}'s own stance on an authoring-facing screen: the
 * product editor already operates at brand scope, and a variant's listing gap
 * spans every branch that offers it, not the operator's one currently
 * selected location.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/variants/{variantId}/inventory-listing")
@Tag(
        name = "Inventory listing backfill",
        description = "One variant's unlisted branches, and listing it at all of them")
public class InventoryVariantListingController {

    private final OfferingListingBackfillService backfill;

    public InventoryVariantListingController(OfferingListingBackfillService backfill) {
        this.backfill = backfill;
    }

    @GetMapping
    @RequiresCapability(value = Capability.INVENTORY_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every branch offering this variant AVAILABLE that has never listed it",
            description = "The product editor's Availability tab own \"not listed at N branches\" "
                    + "banner — empty once every offering AVAILABLE offering of this variant has a "
                    + "stock item somewhere.")
    public ResponseEntity<UnlistedLocationsResponse> unlistedLocations(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID variantId) {
        List<UUID> locationIds = backfill.unlistedLocationsForVariant(tenantId, brandId, variantId);
        return ResponseEntity.ok(new UnlistedLocationsResponse(locationIds));
    }

    @PostMapping
    @RequiresCapability(value = Capability.INVENTORY_ADJUST, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "List this variant at every branch that offers it AVAILABLE but has not",
            description = "The one-click action behind the Availability tab's own banner. "
                    + "Idempotent — a branch already listed, or one that deliberately marked this "
                    + "variant sold out, is left exactly as it is.")
    public ResponseEntity<BackfillResponse> listEverywhere(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID variantId) {
        VariantBackfillResult result = backfill.backfillVariant(tenantId, brandId, variantId);
        return ResponseEntity.ok(new BackfillResponse(result.candidateCount(), result.listedCount()));
    }

    public record UnlistedLocationsResponse(List<UUID> locationIds) {}

    public record BackfillResponse(int candidateCount, int listedCount) {}
}
