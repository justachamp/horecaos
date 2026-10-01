package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.AttachmentPolicy;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.ComboComponentChanges;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.ComboGroupChanges;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.ComboGroupDetail;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.NewComboGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.CompositeProducts.AttachmentOwnerType;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboComponent;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboGroup;
import uz.horecaos.platform.catalog.domain.CompositeProducts.FulfillmentMode;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ModifierAttachment;
import uz.horecaos.platform.catalog.domain.CompositeProducts.Visibility;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Authoring for composite products (ADR 0136): combo groups and components, and the
 * visibility and overrides of a modifier attachment.
 *
 * <p>Mounted on {@code CatalogAuthoringController}'s own base path rather than the
 * sketch in the record's Specification (which omits the tenant segment every
 * neighbouring route carries): the operations console reaches catalog authoring
 * through exactly that prefix, and a second one for the same aggregate family would
 * make a combo the only catalog entity with a different address.
 *
 * <p>Capability {@code catalog.author} at brand scope throughout, no new
 * capability: a combo group is exactly as brand-scoped and exactly as
 * consequential as a modifier group, which already sits behind it. A combo
 * component's <em>price</em> is a pricing write and is authored under {@code
 * pricing.author} on the price book, beside every other price.
 *
 * <p>Every mutation requires {@code If-Match} (a version the caller read) except
 * creation, and every mutating route requires {@code Idempotency-Key} through its
 * capability declaration (ADR 0031). Each answer carries the new version as an
 * {@code ETag}, so the editor always holds the value the next write will ask for.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/catalog")
@Tag(name = "Composite products", description = "Combo groups, components, and modifier attachment policy")
public class CompositeProductAuthoringController {

    private final CompositeProductAuthoringService authoring;
    private final CurrentActor currentActor;

    public CompositeProductAuthoringController(CompositeProductAuthoringService authoring, CurrentActor currentActor) {
        this.authoring = authoring;
        this.currentActor = currentActor;
    }

    // ------------------------------------------------------------- combo groups

    @PostMapping("/combo-groups")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Create a combo group on a container variant",
            description = "One choice a combo asks the customer to make. The container variant is "
                    + "never priced or sold directly; an order for it becomes one ordinary line per "
                    + "selected component. A variant that is already a component of another combo "
                    + "cannot become a container (no nested combos): 422 with findingCode "
                    + "COMBO_NESTING_FORBIDDEN. A range that cannot be completed is refused with "
                    + "COMBO_GROUP_RANGE_INVALID; a range that merely exceeds the components added "
                    + "so far is accepted here and rejected at publication, like a modifier group.")
    public ResponseEntity<ComboGroupResponse> createComboGroup(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody CreateComboGroupRequest request) {
        ComboGroup group = authoring.createComboGroup(
                new NewComboGroup(
                        tenantId,
                        brandId,
                        request.containerVariantId(),
                        request.code(),
                        request.name(),
                        request.locale(),
                        request.minimumSelections(),
                        request.maximumSelections(),
                        Boolean.TRUE.equals(request.allowSameComponentMultipleTimes()),
                        request.sortOrder() == null ? 0 : request.sortOrder()),
                subject());
        return respond(new ComboGroupDetail(group, List.of()));
    }

    @GetMapping("/combo-groups/{comboGroupId}")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(summary = "One combo group with its components")
    public ResponseEntity<ComboGroupResponse> readComboGroup(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID comboGroupId) {
        return respond(authoring.comboGroup(tenantId, brandId, comboGroupId));
    }

    @GetMapping("/variants/{variantId}/combo-groups")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every combo group a container variant offers",
            description = "Empty for a variant that is not a container, which is not an error.")
    public List<ComboGroupResponse> comboGroupsOfVariant(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID variantId) {
        return authoring.comboGroupsOf(tenantId, brandId, variantId).stream()
                .map(ComboGroupResponse::of)
                .toList();
    }

    @PutMapping("/combo-groups/{comboGroupId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Replace a combo group's selection range, repeat rule, order and status",
            description = "Requires If-Match carrying the group's version; a stale version is "
                    + "refused with STALE_VERSION. The code and the container are the group's "
                    + "identity and are not editable.")
    public ResponseEntity<ComboGroupResponse> updateComboGroup(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID comboGroupId,
            @Valid @RequestBody UpdateComboGroupRequest request,
            HttpServletRequest httpRequest) {
        int expected = Math.toIntExact(AggregateVersion.requireIfMatch(httpRequest));
        authoring.updateComboGroup(
                tenantId,
                brandId,
                comboGroupId,
                expected,
                new ComboGroupChanges(
                        request.minimumSelections(),
                        request.maximumSelections(),
                        Boolean.TRUE.equals(request.allowSameComponentMultipleTimes()),
                        request.sortOrder() == null ? 0 : request.sortOrder(),
                        request.status()),
                subject());
        return respond(authoring.comboGroup(tenantId, brandId, comboGroupId));
    }

    // --------------------------------------------------------------- components

    @PostMapping("/combo-groups/{comboGroupId}/components")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Offer a variant inside a combo group",
            description = "A component is a real dish or drink, classified once and usable in any "
                    + "number of combos. It cannot itself be a combo container (422, findingCode "
                    + "COMBO_NESTING_FORBIDDEN) and cannot be archived. Its price in a combo is a "
                    + "COMBO_COMPONENT price on a price book, set under pricing.author: until one "
                    + "exists the component blocks publication with COMBO_COMPONENT_HAS_NO_ACTIVE_PRICE.")
    public ResponseEntity<ComboComponentResponse> addComponent(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID comboGroupId,
            @Valid @RequestBody AddComboComponentRequest request) {
        ComboComponent component = authoring.addComponent(
                tenantId,
                brandId,
                comboGroupId,
                request.componentVariantId(),
                request.defaultQuantity() == null ? 1 : request.defaultQuantity(),
                request.sortOrder() == null ? 0 : request.sortOrder(),
                subject());
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(component.version()))
                .body(ComboComponentResponse.of(component));
    }

    @PutMapping("/combo-components/{componentId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Replace a combo component's quantity, order and status",
            description = "Requires If-Match carrying the component's version. Archiving a "
                    + "component is how it leaves a combo; nothing is deleted.")
    public ResponseEntity<ComboComponentResponse> updateComponent(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID componentId,
            @Valid @RequestBody UpdateComboComponentRequest request,
            HttpServletRequest httpRequest) {
        int expected = Math.toIntExact(AggregateVersion.requireIfMatch(httpRequest));
        ComboComponent component = authoring.updateComponent(
                tenantId,
                brandId,
                componentId,
                expected,
                new ComboComponentChanges(
                        request.defaultQuantity(),
                        request.sortOrder() == null ? 0 : request.sortOrder(),
                        request.status()),
                subject());
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(component.version()))
                .body(ComboComponentResponse.of(component));
    }

    // ---------------------------------------------------- modifier attachments

    @PutMapping("/variants/{variantId}/modifier-groups/{groupId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Attach a modifier group to a variant",
            description = "variant_modifier_groups has existed since ADR 0016 and nothing wrote it. "
                    + "This is what lets an option that links a variant offer that variant's own "
                    + "groups -- one level of nesting, ADR 0136. A variant-level attachment of a "
                    + "group wins over the product-level attachment of the same group. Idempotent "
                    + "on (variant, group): a second call re-sorts it.")
    public ResponseEntity<ModifierAttachmentResponse> attachToVariant(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID variantId,
            @PathVariable UUID groupId,
            @Valid @RequestBody AttachModifierGroupRequest request) {
        ModifierAttachment attached = authoring.attachModifierGroupToVariant(
                tenantId,
                brandId,
                variantId,
                groupId,
                request.sortOrder() == null ? 0 : request.sortOrder(),
                subject());
        return respond(attached);
    }

    @GetMapping("/variants/{variantId}/modifier-groups")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The modifier groups attached to a variant itself",
            description = "Variant-level attachments only, with their visibility and overrides. The "
                    + "product-level ones are on the product's detail.")
    public List<ModifierAttachmentResponse> variantAttachments(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID variantId) {
        return authoring.attachmentsOf(tenantId, brandId, AttachmentOwnerType.VARIANT, variantId).stream()
                .map(ModifierAttachmentResponse::of)
                .toList();
    }

    @PutMapping("/products/{productId}/modifier-groups/{groupId}/overrides")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Set how a product offers an attached modifier group",
            description = "Visibility (VISIBLE, or HIDDEN_AUTO_SELECT for a charge the server "
                    + "applies without showing the customer), the fulfilment modes a hidden group "
                    + "applies to (null = every mode), and this product's own required/minimum/"
                    + "maximum -- each null meaning 'use the shared group's value'. The shared "
                    + "group is never edited from here: another product attaching it is "
                    + "unaffected. Requires If-Match carrying the attachment's version.")
    public ResponseEntity<ModifierAttachmentResponse> setProductAttachmentPolicy(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID productId,
            @PathVariable UUID groupId,
            @Valid @RequestBody AttachmentPolicyRequest request,
            HttpServletRequest httpRequest) {
        return respond(authoring.setAttachmentPolicy(
                tenantId,
                brandId,
                AttachmentOwnerType.PRODUCT,
                productId,
                groupId,
                Math.toIntExact(AggregateVersion.requireIfMatch(httpRequest)),
                request.toPolicy(),
                subject()));
    }

    @PutMapping("/variants/{variantId}/modifier-groups/{groupId}/overrides")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Set how a variant offers an attached modifier group",
            description = "The variant-level twin of the product route, with the same body and "
                    + "rules; a variant-level policy wins over the product's for the same group.")
    public ResponseEntity<ModifierAttachmentResponse> setVariantAttachmentPolicy(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID variantId,
            @PathVariable UUID groupId,
            @Valid @RequestBody AttachmentPolicyRequest request,
            HttpServletRequest httpRequest) {
        return respond(authoring.setAttachmentPolicy(
                tenantId,
                brandId,
                AttachmentOwnerType.VARIANT,
                variantId,
                groupId,
                Math.toIntExact(AggregateVersion.requireIfMatch(httpRequest)),
                request.toPolicy(),
                subject()));
    }

    private String subject() {
        return currentActor.get().subject();
    }

    private static ResponseEntity<ComboGroupResponse> respond(ComboGroupDetail detail) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(detail.group().version()))
                .body(ComboGroupResponse.of(detail));
    }

    private static ResponseEntity<ModifierAttachmentResponse> respond(ModifierAttachment attachment) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(attachment.version()))
                .body(ModifierAttachmentResponse.of(attachment));
    }

    // --------------------------------------------------------------- requests

    /**
     * @param minimumSelections picks the customer must make; boxed because Jackson 3
     *                          refuses a missing primitive, and a missing range must be
     *                          a validation error rather than a silent zero
     * @param allowSameComponentMultipleTimes optional, false when absent
     */
    public record CreateComboGroupRequest(
            @NotNull UUID containerVariantId,
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 16) String locale,
            @NotNull @Min(0) @Max(100) Integer minimumSelections,
            @NotNull @Min(1) @Max(100) Integer maximumSelections,
            @Nullable Boolean allowSameComponentMultipleTimes,
            @Nullable @Min(0) Integer sortOrder) {}

    public record UpdateComboGroupRequest(
            @NotNull @Min(0) @Max(100) Integer minimumSelections,
            @NotNull @Min(1) @Max(100) Integer maximumSelections,
            @Nullable Boolean allowSameComponentMultipleTimes,
            @Nullable @Min(0) Integer sortOrder,
            @NotNull Status status) {}

    public record AddComboComponentRequest(
            @NotNull UUID componentVariantId,
            @Nullable @Min(1) @Max(100) Integer defaultQuantity,
            @Nullable @Min(0) Integer sortOrder) {}

    public record UpdateComboComponentRequest(
            @NotNull @Min(1) @Max(100) Integer defaultQuantity,
            @Nullable @Min(0) Integer sortOrder,
            @NotNull Status status) {}

    public record AttachModifierGroupRequest(
            @Nullable @Min(0) Integer sortOrder) {}

    /**
     * @param applicableFulfillmentModes null = every mode; only a {@code
     *                                   HIDDEN_AUTO_SELECT} attachment may set it
     * @param requiredOverride null falls back to the shared group's own
     */
    public record AttachmentPolicyRequest(
            @NotNull Visibility visibility,
            @Nullable Set<FulfillmentMode> applicableFulfillmentModes,
            @Nullable Boolean requiredOverride,
            @Nullable @Min(0) Integer minimumSelectionsOverride,
            @Nullable @Min(1) Integer maximumSelectionsOverride) {

        AttachmentPolicy toPolicy() {
            return new AttachmentPolicy(
                    visibility,
                    applicableFulfillmentModes,
                    requiredOverride,
                    minimumSelectionsOverride,
                    maximumSelectionsOverride);
        }
    }

    // -------------------------------------------------------------- responses

    public record ComboGroupResponse(
            UUID comboGroupId,
            UUID containerVariantId,
            String code,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameComponentMultipleTimes,
            int sortOrder,
            Status status,
            int version,
            List<ComboComponentResponse> components) {

        static ComboGroupResponse of(ComboGroupDetail detail) {
            ComboGroup group = detail.group();
            return new ComboGroupResponse(
                    group.id(),
                    group.containerVariantId(),
                    group.code(),
                    group.minimumSelections(),
                    group.maximumSelections(),
                    group.allowSameComponentMultipleTimes(),
                    group.sortOrder(),
                    group.status(),
                    group.version(),
                    detail.components().stream().map(ComboComponentResponse::of).toList());
        }
    }

    public record ComboComponentResponse(
            UUID componentId,
            UUID comboGroupId,
            UUID componentVariantId,
            int defaultQuantity,
            int sortOrder,
            Status status,
            int version) {

        static ComboComponentResponse of(ComboComponent component) {
            return new ComboComponentResponse(
                    component.id(),
                    component.comboGroupId(),
                    component.componentVariantId(),
                    component.defaultQuantity(),
                    component.sortOrder(),
                    component.status(),
                    component.version());
        }
    }

    public record ModifierAttachmentResponse(
            UUID ownerId,
            AttachmentOwnerType ownerType,
            UUID modifierGroupId,
            int sortOrder,
            Visibility visibility,
            @Nullable List<FulfillmentMode> applicableFulfillmentModes,
            @Nullable Boolean requiredOverride,
            @Nullable Integer minimumSelectionsOverride,
            @Nullable Integer maximumSelectionsOverride,
            int version) {

        static ModifierAttachmentResponse of(ModifierAttachment attachment) {
            return new ModifierAttachmentResponse(
                    attachment.ownerId(),
                    attachment.ownerType(),
                    attachment.modifierGroupId(),
                    attachment.sortOrder(),
                    attachment.visibility(),
                    attachment.modes() == null
                            ? null
                            : attachment.modes().stream().sorted().toList(),
                    attachment.requiredOverride(),
                    attachment.minimumOverride(),
                    attachment.maximumOverride(),
                    attachment.version());
        }
    }
}
