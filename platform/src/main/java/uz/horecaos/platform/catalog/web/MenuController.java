package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
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
import uz.horecaos.platform.catalog.application.MenuAuthoringService;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore.BranchMenuBindingRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore.MenuItemRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore.MenuRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The named {@code Menu} entity (row 4.4a) — control-plane authoring: create,
 * rename/archive, copy, membership (single add/remove and the filtered
 * select-all), and binding a menu to a branch.
 *
 * <p>Nothing here reaches a customer by itself. A bound branch's storefront
 * read changes ({@link uz.horecaos.platform.catalog.application.StorefrontCatalogQuery})
 * the moment a binding is written — the same "takes effect immediately
 * without republishing" rule {@code location_offerings} already follows, and
 * for the same reason: which branches sell what from which named list is an
 * availability question, not a content-publication one.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/catalog/menus")
@Tag(name = "Named menus", description = "Row 4.4a — a copyable, bindable assortment")
public class MenuController {

    private final MenuAuthoringService menus;
    private final CurrentActor currentActor;

    public MenuController(MenuAuthoringService menus, CurrentActor currentActor) {
        this.menus = menus;
        this.currentActor = currentActor;
    }

    // ------------------------------------------------------------------ menus

    @PostMapping
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Create a named menu", description = "Starts in DRAFT with no membership.")
    public ResponseEntity<MenuResponse> create(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody CreateMenuRequest body) {
        MenuRow created = menus.createMenu(
                tenantId, brandId, body.name(), currentActor.get().subject());
        return ResponseEntity.ok(MenuResponse.of(created));
    }

    @GetMapping
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Every menu this brand has authored, every status")
    public ResponseEntity<List<MenuResponse>> list(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(menus.listMenus(tenantId, brandId).stream()
                .map(MenuResponse::of)
                .toList());
    }

    @PutMapping("/{menuId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Rename, archive, or reactivate a menu",
            description = "Whole-record PUT with an expected version, the same discipline "
                    + "CommentPresetController's own update already uses.")
    public ResponseEntity<MenuResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID menuId,
            @Valid @RequestBody UpdateMenuRequest body) {
        try {
            MenuRow updated = menus.updateMenu(
                    tenantId,
                    brandId,
                    menuId,
                    body.name(),
                    body.status(),
                    body.expectedVersion(),
                    currentActor.get().subject());
            return ResponseEntity.ok(MenuResponse.of(updated));
        } catch (MenuAuthoringService.UnknownMenuException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        }
    }

    @PostMapping("/{menuId}/copy")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Copy a menu",
            description = "A new menu, in DRAFT, with the same membership (variants, sort, "
                    + "default availability) as the source — the gesture that lets a chain roll "
                    + "one assortment out to a further branch without re-authoring it. The copy's "
                    + "own name must be given and must differ from every other menu on this brand.")
    public ResponseEntity<MenuResponse> copy(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID menuId,
            @Valid @RequestBody CopyMenuRequest body) {
        try {
            MenuRow copy = menus.copyMenu(
                    tenantId, brandId, menuId, body.name(), currentActor.get().subject());
            return ResponseEntity.ok(MenuResponse.of(copy));
        } catch (MenuAuthoringService.UnknownMenuException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        }
    }

    // ------------------------------------------------------------- membership

    @GetMapping("/{menuId}/items")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(summary = "A menu's membership, in sort order")
    public ResponseEntity<List<MenuItemResponse>> items(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID menuId) {
        try {
            return ResponseEntity.ok(menus.listItems(tenantId, brandId, menuId).stream()
                    .map(MenuItemResponse::of)
                    .toList());
        } catch (MenuAuthoringService.UnknownMenuException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        }
    }

    @PutMapping("/{menuId}/items/{variantId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Add a variant to a menu, or re-sort/re-default it if already there",
            description = "Never a second row for one (menu, variant) pair.")
    public ResponseEntity<Void> addItem(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID menuId,
            @PathVariable UUID variantId,
            @Valid @RequestBody MenuItemRequest body) {
        try {
            menus.addItem(
                    tenantId,
                    brandId,
                    menuId,
                    variantId,
                    body.sortOrder(),
                    body.availabilityDefault(),
                    currentActor.get().subject());
            return ResponseEntity.noContent().build();
        } catch (MenuAuthoringService.UnknownMenuException | MenuAuthoringService.UnknownVariantException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        }
    }

    @DeleteMapping("/{menuId}/items/{variantId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Remove a variant from a menu", description = "Idempotent.")
    public ResponseEntity<Void> removeItem(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID menuId,
            @PathVariable UUID variantId) {
        menus.removeItem(
                tenantId, brandId, menuId, variantId, currentActor.get().subject());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{menuId}/items/bulk-add-by-filter")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Add every active variant matching a category and/or search filter, in one command",
            description = "The row's own \"filtered select-all\": category and/or a "
                    + "case-insensitive product-name-or-SKU search resolve to a membership "
                    + "write in one call rather than one gesture per product. Tag filtering is "
                    + "not offered — catalog carries no tag vocabulary yet (gap map row 4.7 "
                    + "stays BLOCKED on the same unanswered classification question).")
    public ResponseEntity<BulkAddByFilterResponse> addByFilter(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID menuId,
            @Valid @RequestBody BulkAddByFilterRequest body) {
        try {
            int added = menus.addByFilter(
                    tenantId,
                    brandId,
                    menuId,
                    body.categoryId(),
                    body.search(),
                    body.availabilityDefault() == null ? "AVAILABLE" : body.availabilityDefault(),
                    body.locale() == null ? "uz" : body.locale(),
                    currentActor.get().subject());
            return ResponseEntity.ok(new BulkAddByFilterResponse(added));
        } catch (MenuAuthoringService.UnknownMenuException | MenuAuthoringService.UnknownCategoryException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        }
    }

    // --------------------------------------------------------------- binding

    @GetMapping("/bindings")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Every branch's current menu binding, one row per (branch, channel scope)")
    public ResponseEntity<List<BranchMenuBindingResponse>> listBindings(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(menus.listBindings(tenantId, brandId).stream()
                .map(BranchMenuBindingResponse::of)
                .toList());
    }

    @PutMapping("/bindings/locations/{locationId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Binds a menu to a branch",
            description = "A null channelId binds the branch's default, across every sales "
                    + "channel; naming one overrides the default for that channel alone. "
                    + "Replaces whichever menu previously held that exact scope. Takes effect "
                    + "immediately, exactly like a location_offerings toggle — no republish "
                    + "required. Refused unless the menu is ACTIVE — a DRAFT menu is not yet "
                    + "reviewed and complete, and an ARCHIVED one is retired; activate the menu "
                    + "first.")
    public ResponseEntity<Void> bind(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody BindMenuRequest body) {
        try {
            menus.bindToBranch(
                    tenantId,
                    brandId,
                    locationId,
                    body.channelId(),
                    body.menuId(),
                    currentActor.get().subject());
            return ResponseEntity.noContent().build();
        } catch (MenuAuthoringService.UnknownMenuException | MenuAuthoringService.UnknownChannelException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        }
    }

    @DeleteMapping("/bindings/locations/{locationId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Unbinds a branch's menu",
            description = "Query param channelId names which scope to clear; omitted clears the "
                    + "branch's default binding. The branch falls back to today's unmodified "
                    + "location_offerings behaviour the moment no binding covers it. Idempotent.")
    public ResponseEntity<Void> unbind(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) @Nullable UUID channelId) {
        menus.unbindBranch(
                tenantId, brandId, locationId, channelId, currentActor.get().subject());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ DTOs

    record CreateMenuRequest(@NotBlank @Size(max = 255) String name) {}

    record UpdateMenuRequest(
            @NotBlank @Size(max = 255) String name,

            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "DRAFT|ACTIVE|ARCHIVED")
            String status,

            @NotNull Integer expectedVersion) {}

    record CopyMenuRequest(@NotBlank @Size(max = 255) String name) {}

    record MenuItemRequest(
            int sortOrder,

            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "AVAILABLE|UNAVAILABLE|HIDDEN")
            String availabilityDefault) {}

    record BulkAddByFilterRequest(
            @Nullable UUID categoryId,
            @Nullable @Size(max = 200) String search,

            @Nullable @jakarta.validation.constraints.Pattern(regexp = "AVAILABLE|UNAVAILABLE|HIDDEN")
            String availabilityDefault,

            @Nullable @Size(max = 8) String locale) {}

    record BulkAddByFilterResponse(int added) {}

    record BindMenuRequest(@NotNull UUID menuId, @Nullable UUID channelId) {}

    record MenuResponse(UUID menuId, String name, String status, int version) {
        static MenuResponse of(MenuRow row) {
            return new MenuResponse(row.id(), row.name(), row.status(), row.version());
        }
    }

    record MenuItemResponse(
            UUID variantId,
            UUID productId,
            String productName,
            @Nullable String sku,
            int sortOrder,
            String availabilityDefault,
            int version) {
        static MenuItemResponse of(MenuItemRow row) {
            return new MenuItemResponse(
                    row.variantId(),
                    row.productId(),
                    row.productName(),
                    row.sku(),
                    row.sortOrder(),
                    row.availabilityDefault(),
                    row.version());
        }
    }

    record BranchMenuBindingResponse(
            UUID locationId,
            String locationName,
            @Nullable UUID channelId,
            @Nullable String channelCode,
            UUID menuId,
            String menuName,
            int version) {
        static BranchMenuBindingResponse of(BranchMenuBindingRow row) {
            return new BranchMenuBindingResponse(
                    row.locationId(),
                    row.locationName(),
                    row.channelId(),
                    row.channelCode(),
                    row.menuId(),
                    row.menuName(),
                    row.version());
        }
    }
}
