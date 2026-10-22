package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.application.StorefrontBranchOrderService;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStorefrontBranchOrderStore.Branch;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The order a brand's storefront lists its branches in (gap-map row {@code 6.8}; ADR 0157 Release 4, ADR 0183 D17).
 *
 * <p>Not the console's own branch list order ({@code sort_order}, V0401): this is the one a customer sees when they have shared
 * no location, and the tie-break after distance when they have.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/storefront-branch-order")
@Tag(name = "Storefront branch order", description = "The order a brand's storefront lists its branches in")
public class StorefrontBranchOrderController {

    private final StorefrontBranchOrderService order;

    public StorefrontBranchOrderController(StorefrontBranchOrderService order) {
        this.order = order;
    }

    @GetMapping
    @RequiresCapability(value = Capability.LOCATION_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The brand's branches in the order the storefront lists them with no customer point",
            description = "storefrontSortOrder is null for a branch nobody has placed; those sort last, by name.")
    public List<StorefrontBranchView> readStorefrontBranchOrder(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return order.list(tenantId, brandId).stream()
                .map(StorefrontBranchView::of)
                .toList();
    }

    @PutMapping
    @RequiresCapability(value = Capability.LOCATION_WRITE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Set the order the storefront lists the brand's branches in",
            description =
                    "Names the branches first to last. A branch of the brand that is left out goes back to unset and "
                            + "sorts last. 400 for a branch that is not this brand's or is named twice.")
    public List<StorefrontBranchView> replaceStorefrontBranchOrder(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody BranchOrderRequest body) {
        return order.replace(tenantId, brandId, body.locationIds()).stream()
                .map(StorefrontBranchView::of)
                .toList();
    }

    public record BranchOrderRequest(@NotNull List<@NotNull UUID> locationIds) {}

    public record StorefrontBranchView(
            UUID locationId,
            String displayName,
            String status,
            @Nullable Integer storefrontSortOrder) {
        static StorefrontBranchView of(Branch branch) {
            return new StorefrontBranchView(
                    branch.locationId(), branch.displayName(), branch.status(), branch.storefrontSortOrder());
        }
    }
}
