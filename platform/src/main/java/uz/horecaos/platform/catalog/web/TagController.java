package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.catalog.application.VocabularyService;
import uz.horecaos.platform.catalog.application.VocabularyService.EntryEdit;
import uz.horecaos.platform.catalog.application.VocabularyService.NewEntry;
import uz.horecaos.platform.catalog.domain.Vocabularies.Kind;
import uz.horecaos.platform.catalog.web.VocabularyResponses.ProductVocabularySetRequest;
import uz.horecaos.platform.catalog.web.VocabularyResponses.ProductVocabularySetResponse;
import uz.horecaos.platform.catalog.web.VocabularyResponses.VocabularyEntryResponse;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Tags (ADR 0174, gap-map row 4.7): a tenant-wide registry of badges and storefront filter options, and the
 * ordered set of them a product carries (at most twelve).
 *
 * <p>No new capability: the codes that already guard the sibling vocabulary (comment presets) guard these, {@code
 * catalog.read} and {@code catalog.author}, at tenant scope for the registry and brand scope for a product's set.
 * Every mutating route requires an {@code Idempotency-Key}; an edit and an archive require {@code If-Match} and
 * answer with the new version as an {@code ETag} (ADR 0031). Mounted on the control-plane document group (ADR 0057).
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}")
@Tag(name = "Tags", description = "Badges and filter options: a tenant-wide vocabulary, assigned to products")
public class TagController {

    private static final Kind KIND = Kind.TAG;

    private final VocabularyService service;
    private final CurrentActor currentActor;

    public TagController(VocabularyService service, CurrentActor currentActor) {
        this.service = service;
        this.currentActor = currentActor;
    }

    @GetMapping("/tags")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The tenant's tags, active and archived",
            description =
                    "Each tag carries its code, an optional icon from a closed list, whether it is shown as a badge and/or offered as a filter, and its name per locale.")
    public List<VocabularyEntryResponse> list(@PathVariable UUID tenantId) {
        return service.list(KIND, tenantId).stream()
                .map(VocabularyEntryResponse::of)
                .toList();
    }

    @PostMapping("/tags")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Register a tag",
            description =
                    "A tag has a code (lower case, unique per tenant, never shown), an optional icon key from the closed list FLAME, LEAF, STAR, HEART, FISH, NEW, at least one of showAsBadge and showAsFilter, a sort order and a name per locale. At most 200 per tenant. 409 when the code is taken (findingCode VOCABULARY_CODE_TAKEN); "
                            + "400 for an invalid code, a missing name in the tenant's default language, an unknown icon, or a tag shown neither as badge nor as filter; 422 VOCABULARY_TENANT_LIMIT_REACHED over 200.")
    public ResponseEntity<VocabularyEntryResponse> create(
            @PathVariable UUID tenantId, @Valid @RequestBody TagCreateRequest request) {
        return VocabularyResponses.versioned(service.create(
                KIND,
                tenantId,
                new NewEntry(
                        request.code(),
                        request.iconKey(),
                        request.showAsBadge(),
                        request.showAsFilter(),
                        request.sortOrder(),
                        request.names()),
                subject()));
    }

    @GetMapping("/tags/{entryId}")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.TENANT)
    @Operation(summary = "One a tag with its wording")
    public ResponseEntity<VocabularyEntryResponse> read(@PathVariable UUID tenantId, @PathVariable UUID entryId) {
        return VocabularyResponses.versioned(service.get(KIND, tenantId, entryId));
    }

    @PutMapping("/tags/{entryId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Edit a tag",
            description = "Requires If-Match carrying the version. The code is the entry's identity and does not "
                    + "change. A locale not named keeps its wording; an edit never deletes one.")
    public ResponseEntity<VocabularyEntryResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable UUID entryId,
            @Valid @RequestBody TagEditRequest request,
            HttpServletRequest httpRequest) {
        int expected = Math.toIntExact(AggregateVersion.requireIfMatch(httpRequest));
        return VocabularyResponses.versioned(service.update(
                KIND,
                tenantId,
                entryId,
                new EntryEdit(
                        expected,
                        request.iconKey(),
                        request.showAsBadge(),
                        request.showAsFilter(),
                        request.sortOrder(),
                        request.names()),
                subject()));
    }

    @PostMapping("/tags/{entryId}/archive")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Archive a tag",
            description = "Requires If-Match. Archived, never deleted: it keeps resolving on the products that "
                    + "carry it, leaves the pickers and the next publication, and its code stays taken.")
    public ResponseEntity<VocabularyEntryResponse> archive(
            @PathVariable UUID tenantId, @PathVariable UUID entryId, HttpServletRequest httpRequest) {
        int expected = Math.toIntExact(AggregateVersion.requireIfMatch(httpRequest));
        return VocabularyResponses.versioned(service.archive(KIND, tenantId, entryId, expected, subject()));
    }

    @GetMapping("/brands/{brandId}/products/{productId}/tags")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The tags a product carries, in order",
            description = "Includes an archived entry the product still carries: it keeps resolving here.")
    public ProductVocabularySetResponse forProduct(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID productId) {
        return ProductVocabularySetResponse.of(service.forProduct(KIND, tenantId, brandId, productId));
    }

    @PutMapping("/brands/{brandId}/products/{productId}/tags")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Replace the tags a product carries, in order",
            description =
                    "At most twelve. 422 with findingCode VOCABULARY_PRODUCT_LIMIT_REACHED over that, "
                            + "VOCABULARY_ENTRY_UNKNOWN for an entry of no such tenant, VOCABULARY_ENTRY_ARCHIVED for an "
                            + "archived entry the product does not already carry; 400 VOCABULARY_ENTRY_DUPLICATED for a repeat.")
    public ProductVocabularySetResponse setForProduct(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID productId,
            @Valid @RequestBody ProductVocabularySetRequest request) {
        return ProductVocabularySetResponse.of(
                service.setForProduct(KIND, tenantId, brandId, productId, request.ids(), subject()));
    }

    private String subject() {
        return currentActor.get().subject();
    }

    /** Every optional field is boxed: Jackson 3 refuses a body that omits a primitive. */
    public record TagCreateRequest(
            @NotBlank @Size(max = 32) String code,
            @Nullable @Size(max = 24) String iconKey,
            @Nullable Boolean showAsBadge,
            @Nullable Boolean showAsFilter,
            @Nullable Integer sortOrder,
            Map<String, String> names) {}

    public record TagEditRequest(
            @Nullable @Size(max = 24) String iconKey,
            @Nullable Boolean showAsBadge,
            @Nullable Boolean showAsFilter,
            @Nullable Integer sortOrder,
            @Nullable Map<String, String> names) {}
}
