package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
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
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PriceableNode;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Catalog authoring (ADR 0016).
 *
 * <p>Authorization is declared at brand scope, matching the path. Declaring it
 * at tenant scope — the annotation's default — would mean a brand manager whose
 * grant covers only their own brand is refused, because ADR 0025 scopes cover
 * downwards and never up.
 *
 * <p>Nothing written here is visible to a customer. Every endpoint edits a draft;
 * a menu changes only when {@code CatalogPublicationController} takes a snapshot.
 * That is what lets an operator restructure a menu mid-service without anything
 * shifting under the customers currently ordering.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/catalog")
@Tag(name = "Catalog authoring", description = "Draft menu authoring; never visible to customers")
public class CatalogAuthoringController {

    private final CatalogAuthoringService authoring;
    private final CurrentActor currentActor;

    public CatalogAuthoringController(CatalogAuthoringService authoring, CurrentActor currentActor) {
        this.authoring = authoring;
        this.currentActor = currentActor;
    }

    @PostMapping("/catalogs")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Create a draft catalog")
    public ResponseEntity<IdResponse> createCatalog(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody CreateCatalogRequest request) {
        UUID catalogId = authoring.createCatalog(tenantId, brandId, request.code(), request.name(), request.locale());
        return ResponseEntity.ok(new IdResponse(catalogId));
    }

    @PostMapping("/catalogs/{catalogId}/products")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Create a product with its default variant",
            description = "Both together, because a product with no variant cannot be published "
                    + "and would only ever be a half-finished state to come back to.")
    public ResponseEntity<ProductResponse> createProduct(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID catalogId,
            @Valid @RequestBody CreateProductRequest request) {

        var created = authoring.createProduct(
                tenantId,
                brandId,
                catalogId,
                request.code(),
                request.name(),
                request.description(),
                request.locale(),
                request.sku(),
                request.unitCode(),
                request.classification(),
                actorId());
        return ResponseEntity.ok(new ProductResponse(created.productId(), created.defaultVariantId()));
    }

    @PostMapping("/products/{productId}/variants")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Add a further variant to a product")
    public ResponseEntity<IdResponse> addVariant(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID productId,
            @Valid @RequestBody AddVariantRequest request) {
        UUID variantId = authoring.addVariant(
                tenantId,
                brandId,
                productId,
                request.sku(),
                request.unitCode(),
                request.name(),
                request.locale(),
                request.sortOrder(),
                request.classification(),
                actorId());
        return ResponseEntity.ok(new IdResponse(variantId));
    }

    @PostMapping("/catalogs/{catalogId}/categories")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Create a category")
    public ResponseEntity<IdResponse> createCategory(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID catalogId,
            @Valid @RequestBody CreateCategoryRequest request) {
        UUID categoryId = authoring.createCategory(
                tenantId,
                brandId,
                catalogId,
                request.parentCategoryId(),
                request.code(),
                request.name(),
                request.locale(),
                request.sortOrder());
        return ResponseEntity.ok(new IdResponse(categoryId));
    }

    @PostMapping("/modifier-groups")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Create a modifier group",
            description = "A group whose minimum exceeds the number of options it offers is "
                    + "accepted here and rejected at publication, with the entity path.")
    public ResponseEntity<IdResponse> createModifierGroup(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody CreateModifierGroupRequest request) {
        UUID groupId = authoring.createModifierGroup(
                tenantId,
                brandId,
                request.code(),
                request.name(),
                request.locale(),
                request.required(),
                request.minimumSelections(),
                request.maximumSelections(),
                request.allowSameOptionMultipleTimes());
        return ResponseEntity.ok(new IdResponse(groupId));
    }

    @PostMapping("/modifier-groups/{groupId}/options")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Add an option to a modifier group")
    public ResponseEntity<IdResponse> addModifierOption(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID groupId,
            @Valid @RequestBody AddModifierOptionRequest request) {
        UUID optionId = authoring.addModifierOption(
                tenantId,
                brandId,
                groupId,
                request.code(),
                request.name(),
                request.locale(),
                request.linkedVariantId(),
                request.maximumQuantity(),
                request.sortOrder(),
                request.classification(),
                actorId());
        return ResponseEntity.ok(new IdResponse(optionId));
    }

    @PutMapping("/variants/{variantId}/fiscal-classification")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Classify a variant for fiscal purposes",
            description = "ИКПУ/MXIK, package code, fiscal unit and fiscal name. All four are "
                    + "required by Click and Payme alike; publication reports a gap in any of "
                    + "them rather than refusing the menu, while ADR 0038's coverage tooling "
                    + "is still being built.")
    public ResponseEntity<Void> classifyVariant(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID variantId,
            @Valid @RequestBody FiscalClassificationRequest request) {
        authoring.classify(tenantId, brandId, PriceableNode.variant(variantId), request.toClassification(), actorId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/modifier-options/{optionId}/fiscal-classification")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Classify a modifier option for fiscal purposes",
            description = "A modifier reaches a receipt as its own line. One linked to a "
                    + "sellable variant inherits that variant's classification instead of "
                    + "carrying a second copy that can drift.")
    public ResponseEntity<Void> classifyModifierOption(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID optionId,
            @Valid @RequestBody FiscalClassificationRequest request) {
        authoring.classify(
                tenantId, brandId, PriceableNode.modifierOption(optionId), request.toClassification(), actorId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/fees/{feeCode}/fiscal-classification")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Classify a brand's delivery charge",
            description = "The delivery fee goes out as an ordinary receipt line, never through "
                    + "Payme's shipping block, which carries no code, no package code and no "
                    + "VAT percent. So it needs the same four fields a dish does.")
    public ResponseEntity<IdResponse> classifyFee(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable String feeCode,
            @Valid @RequestBody FiscalClassificationRequest request) {
        UUID feeId = authoring.classifyFee(tenantId, brandId, feeCode, request.toClassification(), actorId());
        return ResponseEntity.ok(new IdResponse(feeId));
    }

    @PutMapping("/products/{productId}/modifier-groups/{groupId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Attach a modifier group to a product")
    public ResponseEntity<Void> attachModifierGroup(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID productId,
            @PathVariable UUID groupId,
            @Valid @RequestBody SortOrderRequest request) {
        authoring.attachModifierGroup(tenantId, brandId, productId, groupId, request.sortOrder());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/categories/{categoryId}/products/{productId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(summary = "Place a product in a category")
    public ResponseEntity<Void> placeInCategory(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID categoryId,
            @PathVariable UUID productId,
            @Valid @RequestBody SortOrderRequest request) {
        authoring.placeProductInCategory(tenantId, brandId, categoryId, productId, request.sortOrder());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/variants/{variantId}/location-offerings/{locationId}")
    @RequiresCapability(value = Capability.OFFERING_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Set whether a location sells a variant",
            description = "Takes effect immediately without republishing. Marking a dish sold out "
                    + "must not require re-validating an entire menu.")
    public ResponseEntity<Void> setOffering(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID variantId,
            @PathVariable UUID locationId,
            @Valid @RequestBody SetOfferingRequest request) {
        authoring.setOffering(
                tenantId,
                brandId,
                locationId,
                variantId,
                request.status(),
                request.fulfillmentModes(),
                currentActor.get().subject());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/locations/{locationId}/variants")
    @RequiresCapability(value = Capability.INVENTORY_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "One location's sellable variants, with current availability",
            description = "catalog.md §4.6, the 86 screen's read side: the read counterpart of "
                    + "PUT .../inventory/variants/{variantId}/availability. Gated on inventory.read "
                    + "rather than catalog.read, matching the screen's own denial rule — an actor "
                    + "who can adjust stock but never touches draft authoring still needs this "
                    + "list.")
    public Page<VariantAvailabilityResponse> variantsAtLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(defaultValue = "uz") String locale,
            @RequestParam(required = false) @Nullable UUID cursor,
            @RequestParam(required = false) @Nullable Integer limit) {

        int pageSize = Page.limitOrDefault(limit);
        List<JdbcCatalogStore.VariantAvailabilityRow> rows =
                authoring.variantsAtLocation(tenantId, brandId, locationId, locale, cursor, pageSize);
        List<VariantAvailabilityResponse> items =
                rows.stream().map(VariantAvailabilityResponse::of).toList();

        // A short page is the end of the collection; a full one may or may not
        // be, and the same shortcut MigrationProgramController#listScopes takes
        // costs the caller one empty request rather than losing every row after
        // this page (ADR 0031 — no signed CursorSigner bean exists yet).
        String nextCursor = items.size() < pageSize
                ? null
                : rows.get(rows.size() - 1).variantId().toString();
        return new Page<>(items, nextCursor);
    }

    /**
     * Actor attribution for a classification (ADR 0038).
     *
     * <p>Null when the subject is not a UUID — a service account, in practice.
     * The column records who chose a code on a legal document, and a fabricated
     * identifier there is worse than an honest absence.
     */
    private @Nullable UUID actorId() {
        try {
            return UUID.fromString(currentActor.get().subject());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    public record CreateCatalogRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String locale) {}

    /**
     * A product and its default variant, created together.
     *
     * @param fiscal the default variant's classification (ADR 0038). Optional
     *               today: publication reports a gap as a warning rather than
     *               refusing the menu, while ADR 0038's stage 2 coverage tooling
     *               is still being built
     */
    public record CreateProductRequest(
            @NotBlank String code,
            @NotBlank String name,
            @Nullable String description,
            @NotBlank String locale,
            @Nullable String sku,
            @Nullable String unitCode,
            @Valid @Nullable FiscalClassificationRequest fiscal) {

        FiscalClassification classification() {
            return fiscal == null ? FiscalClassification.unclassified() : fiscal.toClassification();
        }
    }

    /**
     * A further variant of an existing product.
     *
     * @param fiscal this variant's own classification. Nothing is inherited from
     *               a sibling variant: every size of a dish is its own receipt
     *               line with its own unit and its own 63-character fiscal name
     */
    public record AddVariantRequest(
            @Nullable String sku,
            @Nullable String unitCode,
            @Nullable String name,
            @NotBlank String locale,
            @PositiveOrZero int sortOrder,
            @Valid @Nullable FiscalClassificationRequest fiscal) {

        FiscalClassification classification() {
            return fiscal == null ? FiscalClassification.unclassified() : fiscal.toClassification();
        }
    }

    /**
     * Everything a Click or Payme receipt line needs about one priceable node
     * (ADR 0038).
     *
     * <p>Every field is optional at the edge even though four of them are
     * required by both providers, because ADR 0038 turns completeness into a
     * publication blocker per brand once that brand's coverage report is clean.
     * A request that refused a half-filled classification would refuse an
     * operator saving progress, and would make the eventual per-brand switch
     * impossible to honour.
     *
     * @param mxikCode       ИКПУ/MXIK. Length-checked only against the column;
     *                       the code's shape belongs to the official reference
     *                       list, not to this record
     * @param fiscalUnitCode the numeric fiscal unit, distinct from the variant's
     *                       varchar measurement unit
     * @param fiscalName     Click caps {@code Name} at 63 characters and wants
     *                       the unit of measure inside it
     * @param markingRequired a marked good cannot be fiscalized through Payme,
     *                        so setting this withdraws Payme from any cart
     *                        containing this node
     */
    public record FiscalClassificationRequest(
            @Size(max = 32) @Nullable String mxikCode,
            @Size(max = 32) @Nullable String packageCode,
            @Positive @Nullable Integer fiscalUnitCode,
            @Size(max = 63) @Nullable String fiscalName,
            @Size(max = 13) @Nullable String barcode,
            boolean markingRequired,
            FiscalClassification.@Nullable MarkingScheme markingScheme,
            boolean excisable,
            @PositiveOrZero @Max(10_000) @Nullable Integer alcoholByVolumeBp,
            @Positive @Max(120) @Nullable Integer ageRestrictionYears) {

        FiscalClassification toClassification() {
            // A marking scheme is implied by the requirement rather than demanded
            // alongside it: DATA_MATRIX is the only scheme in use, and a request
            // that must carry both is a request that can carry a contradiction.
            FiscalClassification.MarkingScheme scheme = markingScheme != null
                    ? markingScheme
                    : (markingRequired
                            ? FiscalClassification.MarkingScheme.DATA_MATRIX
                            : FiscalClassification.MarkingScheme.NONE);
            return new FiscalClassification(
                    mxikCode,
                    packageCode,
                    fiscalUnitCode,
                    fiscalName,
                    barcode,
                    markingRequired,
                    scheme,
                    excisable,
                    alcoholByVolumeBp,
                    ageRestrictionYears);
        }
    }

    public record CreateCategoryRequest(
            @Nullable UUID parentCategoryId,
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String locale,
            @PositiveOrZero int sortOrder) {}

    public record CreateModifierGroupRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String locale,
            boolean required,
            @PositiveOrZero int minimumSelections,
            @Positive int maximumSelections,
            boolean allowSameOptionMultipleTimes) {}

    /**
     * One choice added to an existing modifier group.
     *
     * @param fiscal a modifier reaches a receipt as its own line and so needs its
     *               own classification; absent, it falls back to the linked
     *               variant's when there is one
     */
    public record AddModifierOptionRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String locale,
            @Nullable UUID linkedVariantId,
            @Positive int maximumQuantity,
            @PositiveOrZero int sortOrder,
            @Valid @Nullable FiscalClassificationRequest fiscal) {

        FiscalClassification classification() {
            return fiscal == null ? FiscalClassification.unclassified() : fiscal.toClassification();
        }
    }

    public record SortOrderRequest(@PositiveOrZero int sortOrder) {}

    public record SetOfferingRequest(
            @NotNull OfferingStatus status, @NotNull List<String> fulfillmentModes) {}

    public record IdResponse(UUID id) {}

    public record ProductResponse(UUID productId, UUID defaultVariantId) {}

    /**
     * One row of the 86 screen.
     *
     * @param category  null when the product sits in no category
     * @param available whether this variant can be sold right now
     * @param trackingMode {@code BINARY}, {@code UNTRACKED}, {@code QUANTITY},
     *                     or null when nothing stocks it at this location. A
     *                     {@code QUANTITY} row is always {@code available:
     *                     false} — the client renders catalog.md's read-only
     *                     "Количественный учёт пока не поддерживается" state
     *                     rather than a control that would 409
     */
    public record VariantAvailabilityResponse(
            UUID variantId,
            String productName,
            @Nullable String category,
            boolean available,
            @Nullable String trackingMode) {

        static VariantAvailabilityResponse of(JdbcCatalogStore.VariantAvailabilityRow row) {
            return new VariantAvailabilityResponse(
                    row.variantId(), row.productName(), row.categoryName(), row.available(), row.trackingMode());
        }
    }
}
