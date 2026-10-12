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
 * Ingredients (ADR 0174, gap-map row 4.7): a tenant-wide registry of composition labels shown on a product
 * page, and the ordered list of them a product carries (at most forty).
 *
 * <p>An ingredient is a label and nothing else: never priced, stocked, ordered or counted, not a modifier, not a
 * bill of materials, and it carries no allergen meaning (the schema has no column for one). Capabilities, idempotency
 * and versioning are as for tags: {@code catalog.read} and {@code catalog.author}, {@code Idempotency-Key} on every
 * mutation, {@code If-Match} on an edit and an archive.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}")
@Tag(name = "Ingredients", description = "Composition labels shown on a product page: a tenant-wide vocabulary")
public class IngredientController {

    private static final Kind KIND = Kind.INGREDIENT;

    private final VocabularyService service;
    private final CurrentActor currentActor;

    public IngredientController(VocabularyService service, CurrentActor currentActor) {
        this.service = service;
        this.currentActor = currentActor;
    }

    @GetMapping("/ingredients")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The tenant's ingredients, active and archived",
            description =
                    "Each ingredient carries its code and its name per locale. It is a label for the customer to read, not a stock item.")
    public List<VocabularyEntryResponse> list(@PathVariable UUID tenantId) {
        return service.list(KIND, tenantId).stream()
                .map(VocabularyEntryResponse::of)
                .toList();
    }

    @PostMapping("/ingredients")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Register an ingredient",
            description =
                    "An ingredient has a code (lower case, unique per tenant, never shown), a sort order and a name per locale. At most 1,000 per tenant. 409 when the code is taken (findingCode VOCABULARY_CODE_TAKEN); "
                            + "400 for an invalid code, a missing name in the tenant's default language; 422 VOCABULARY_TENANT_LIMIT_REACHED over 1,000.")
    public ResponseEntity<VocabularyEntryResponse> create(
            @PathVariable UUID tenantId, @Valid @RequestBody IngredientCreateRequest request) {
        return VocabularyResponses.versioned(service.create(
                KIND,
                tenantId,
                new NewEntry(request.code(), null, null, null, request.sortOrder(), request.names()),
                subject()));
    }

    @GetMapping("/ingredients/{entryId}")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.TENANT)
    @Operation(summary = "One an ingredient with its wording")
    public ResponseEntity<VocabularyEntryResponse> read(@PathVariable UUID tenantId, @PathVariable UUID entryId) {
        return VocabularyResponses.versioned(service.get(KIND, tenantId, entryId));
    }

    @PutMapping("/ingredients/{entryId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Edit an ingredient",
            description = "Requires If-Match carrying the version. The code is the entry's identity and does not "
                    + "change. A locale not named keeps its wording; an edit never deletes one.")
    public ResponseEntity<VocabularyEntryResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable UUID entryId,
            @Valid @RequestBody IngredientEditRequest request,
            HttpServletRequest httpRequest) {
        int expected = Math.toIntExact(AggregateVersion.requireIfMatch(httpRequest));
        return VocabularyResponses.versioned(service.update(
                KIND,
                tenantId,
                entryId,
                new EntryEdit(expected, null, null, null, request.sortOrder(), request.names()),
                subject()));
    }

    @PostMapping("/ingredients/{entryId}/archive")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Archive an ingredient",
            description = "Requires If-Match. Archived, never deleted: it keeps resolving on the products that "
                    + "carry it, leaves the pickers and the next publication, and its code stays taken.")
    public ResponseEntity<VocabularyEntryResponse> archive(
            @PathVariable UUID tenantId, @PathVariable UUID entryId, HttpServletRequest httpRequest) {
        int expected = Math.toIntExact(AggregateVersion.requireIfMatch(httpRequest));
        return VocabularyResponses.versioned(service.archive(KIND, tenantId, entryId, expected, subject()));
    }

    @GetMapping("/brands/{brandId}/products/{productId}/ingredients")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The ingredients a product carries, in order",
            description = "Includes an archived entry the product still carries: it keeps resolving here.")
    public ProductVocabularySetResponse forProduct(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID productId) {
        return ProductVocabularySetResponse.of(service.forProduct(KIND, tenantId, brandId, productId));
    }

    @PutMapping("/brands/{brandId}/products/{productId}/ingredients")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Replace the ingredients a product carries, in order",
            description = "At most forty. 422 with findingCode VOCABULARY_PRODUCT_LIMIT_REACHED over that, "
                    + "VOCABULARY_ENTRY_UNKNOWN for an entry of no such tenant, VOCABULARY_ENTRY_ARCHIVED for an "
                    + "archived entry the product does not already carry, VOCABULARY_ENTRY_DUPLICATED for a repeat.")
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
    public record IngredientCreateRequest(
            @NotBlank @Size(max = 32) String code, @Nullable Integer sortOrder, Map<String, String> names) {}

    public record IngredientEditRequest(
            @Nullable Integer sortOrder, @Nullable Map<String, String> names) {}
}
