package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.catalog.application.CommentPresetService;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore.ProductPresetRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Which of the tenant's preset product comments one product offers on a
 * line (row 2.1b) — the product editor's own, alongside {@code
 * KitchenStationController}'s routing rule (row 4.2g) and {@code
 * CatalogAuthoringController}'s recommendations (row 4.2h). A separate
 * controller from {@link CommentPresetController} because this half is
 * brand-scoped (the preset itself is tenant-wide, see that class's doc) and
 * addresses an existing product, matching {@code recommendations}'
 * attach/detach shape exactly.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/products/{productId}/comment-presets")
@Tag(name = "Preset product comments", description = "Which presets a product offers on a line")
public class ProductCommentPresetController {

    private final CommentPresetService presets;
    private final CurrentActor currentActor;

    public ProductCommentPresetController(CommentPresetService presets, CurrentActor currentActor) {
        this.presets = presets;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(summary = "Every preset attached to this product, unfiltered")
    public ResponseEntity<List<ProductPresetResponse>> list(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID productId) {
        return ResponseEntity.ok(presets.listForProduct(tenantId, brandId, productId).stream()
                .map(ProductPresetResponse::of)
                .toList());
    }

    @PostMapping
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Attach a preset to this product, or re-sort it if already attached",
            description = "Refused (404) when the preset does not exist for this tenant, or the "
                    + "product does not exist in this brand.")
    public ResponseEntity<ProductPresetResponse> attach(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID productId,
            @Valid @RequestBody AttachPresetRequest body) {
        try {
            presets.attachToProduct(
                    tenantId,
                    brandId,
                    productId,
                    body.presetId(),
                    body.sortOrder(),
                    currentActor.get().subject());
        } catch (CommentPresetService.UnknownProductException | CommentPresetService.UnknownPresetException notFound) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, notFound.getMessage());
        }
        ProductPresetRow attached = presets.listForProduct(tenantId, brandId, productId).stream()
                .filter(row -> row.presetId().equals(body.presetId()))
                .findFirst()
                .orElseThrow();
        return ResponseEntity.ok(ProductPresetResponse.of(attached));
    }

    @DeleteMapping("/{presetId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Detach a preset",
            description = "Idempotent: detaching a pair that is already gone still resolves.")
    public ResponseEntity<Void> detach(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID productId,
            @PathVariable UUID presetId) {
        presets.detachFromProduct(
                tenantId, brandId, productId, presetId, currentActor.get().subject());
        return ResponseEntity.noContent().build();
    }

    record AttachPresetRequest(@NotNull UUID presetId, int sortOrder) {}

    record ProductPresetResponse(
            UUID presetId,
            String code,
            String labelRu,
            String labelUz,
            String labelEn,
            @Nullable String posModifierCode,
            int sortOrder,
            String status) {

        static ProductPresetResponse of(ProductPresetRow row) {
            return new ProductPresetResponse(
                    row.presetId(),
                    row.code(),
                    row.labelRu(),
                    row.labelUz(),
                    row.labelEn(),
                    row.posModifierCode(),
                    row.sortOrder(),
                    row.status());
        }
    }
}
