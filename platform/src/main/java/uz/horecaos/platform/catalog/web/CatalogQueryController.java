package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.catalog.application.CatalogQueryService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Catalog reads (ADR 0016) — the counterpart {@code CatalogAuthoringController}
 * never had: it can create and edit a catalog, a product, a category, or a
 * modifier group, but until this controller there was no HTTP way to read one
 * back. A Products list, a Categories tree, or reopening a product for editing
 * all had no endpoint to call.
 *
 * <p>A separate {@code @RestController} sharing {@code CatalogAuthoringController}'s
 * base path — Spring allows this as long as the full paths do not collide —
 * so authoring stays focused on writes and this stays focused on reads.
 *
 * <p>Every read here is a draft read, the same tables {@code
 * CatalogAuthoringController} writes. Nothing here is what a customer sees;
 * that is {@code CatalogPublicationController}'s published snapshot, taken
 * separately and on demand.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/catalog")
@Tag(name = "Catalog reads", description = "Reading back draft catalogs, products, categories, and modifier groups")
public class CatalogQueryController {

    private final CatalogQueryService query;

    public CatalogQueryController(CatalogQueryService query) {
        this.query = query;
    }

    @GetMapping("/catalogs")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(summary = "List the brand's catalogs")
    public List<CatalogSummaryResponse> catalogs(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return query.catalogs(tenantId, brandId).stream()
                .map(CatalogSummaryResponse::of)
                .toList();
    }

    @GetMapping("/catalogs/{catalogId}/categories")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "One catalog's categories, flat",
            description = "The client builds the tree from each category's parentCategoryId.")
    public List<CategorySummaryResponse> categories(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID catalogId) {
        return query.categories(tenantId, brandId, catalogId).stream()
                .map(CategorySummaryResponse::of)
                .toList();
    }

    @GetMapping("/catalogs/{catalogId}/products")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "One catalog's products, cursor-paginated",
            description = "Same Page and keyset-cursor shape variantsAtLocation uses: a short "
                    + "page is the end of the collection, a full one may or may not be.")
    public Page<ProductSummaryResponse> products(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID catalogId,
            @RequestParam(required = false) @Nullable UUID cursor,
            @RequestParam(required = false) @Nullable Integer limit) {

        int pageSize = Page.limitOrDefault(limit);
        List<CatalogQueryService.ProductSummary> rows = query.products(tenantId, brandId, catalogId, cursor, pageSize);
        List<ProductSummaryResponse> items =
                rows.stream().map(ProductSummaryResponse::of).toList();

        String nextCursor = items.size() < pageSize
                ? null
                : rows.get(rows.size() - 1).productId().toString();
        return new Page<>(items, nextCursor);
    }

    @GetMapping("/products/{productId}")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "One product's full detail, for the product editor",
            description = "Every locale the product and its variants have a translation in, not "
                    + "only the default — the editor's locale switcher needs to know which locales "
                    + "already have text.")
    public ProductDetailResponse product(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID productId) {
        try {
            return ProductDetailResponse.of(query.productDetail(tenantId, brandId, productId));
        } catch (CatalogQueryService.UnknownProductException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        }
    }

    @GetMapping("/modifier-groups")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The brand's modifier group library",
            description = "Brand-wide, not catalog-scoped: a modifier group carries no catalog id "
                    + "and is shared across every catalog the brand has.")
    public List<ModifierGroupSummaryResponse> modifierGroups(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return query.modifierGroups(tenantId, brandId).stream()
                .map(ModifierGroupSummaryResponse::of)
                .toList();
    }

    @GetMapping("/modifier-groups/{groupId}")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(summary = "One modifier group's detail, with its options")
    public ModifierGroupDetailResponse modifierGroup(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID groupId) {
        try {
            return ModifierGroupDetailResponse.of(query.modifierGroupDetail(tenantId, brandId, groupId));
        } catch (CatalogQueryService.UnknownModifierGroupException unknown) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, unknown.getMessage());
        }
    }

    public record CatalogSummaryResponse(UUID catalogId, String code, String name, String status) {

        static CatalogSummaryResponse of(CatalogQueryService.CatalogSummary summary) {
            return new CatalogSummaryResponse(summary.catalogId(), summary.code(), summary.name(), summary.status());
        }
    }

    /** @param parentCategoryId null for a top-level category */
    public record CategorySummaryResponse(
            UUID categoryId,
            @Nullable UUID parentCategoryId,
            String code,
            String name,
            int sortOrder,
            String status,
            int productCount) {

        static CategorySummaryResponse of(CatalogQueryService.CategorySummary summary) {
            return new CategorySummaryResponse(
                    summary.categoryId(),
                    summary.parentCategoryId(),
                    summary.code(),
                    summary.name(),
                    summary.sortOrder(),
                    summary.status(),
                    summary.productCount());
        }
    }

    public record ProductSummaryResponse(
            UUID productId,
            String code,
            String status,
            String name,
            int variantCount,
            List<String> categoryNames,
            boolean hasMxik,
            int version) {

        static ProductSummaryResponse of(CatalogQueryService.ProductSummary summary) {
            return new ProductSummaryResponse(
                    summary.productId(),
                    summary.code(),
                    summary.status(),
                    summary.name(),
                    summary.variantCount(),
                    summary.categoryNames(),
                    summary.hasMxik(),
                    summary.version());
        }
    }

    public record LocalizedFields(String name, @Nullable String description) {

        static LocalizedFields of(CatalogQueryService.LocalizedFields fields) {
            return new LocalizedFields(fields.name(), fields.description());
        }

        static Map<String, LocalizedFields> of(Map<String, CatalogQueryService.LocalizedFields> byLocale) {
            return byLocale.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> of(entry.getValue())));
        }
    }

    public record ProductDetailResponse(
            UUID productId,
            String code,
            String status,
            int version,
            Map<String, LocalizedFields> translations,
            List<UUID> catalogIds,
            List<UUID> categoryIds,
            List<VariantDetail> variants,
            List<AttachedModifierGroupView> modifierGroups,
            List<MediaRelationView> media) {

        static ProductDetailResponse of(CatalogQueryService.ProductDetail detail) {
            return new ProductDetailResponse(
                    detail.productId(),
                    detail.code(),
                    detail.status(),
                    detail.version(),
                    LocalizedFields.of(detail.translations()),
                    detail.catalogIds(),
                    detail.categoryIds(),
                    detail.variants().stream().map(VariantDetail::of).toList(),
                    detail.modifierGroups().stream()
                            .map(AttachedModifierGroupView::of)
                            .toList(),
                    detail.media().stream().map(MediaRelationView::of).toList());
        }
    }

    public record VariantDetail(
            UUID variantId,
            @Nullable String sku,
            String unitCode,
            boolean isDefault,
            int sortOrder,
            String status,
            int version,
            Map<String, LocalizedFields> translations,
            @Nullable FiscalClassificationView fiscal) {

        static VariantDetail of(CatalogQueryService.VariantDetail detail) {
            return new VariantDetail(
                    detail.variantId(),
                    detail.sku(),
                    detail.unitCode(),
                    detail.isDefault(),
                    detail.sortOrder(),
                    detail.status(),
                    detail.version(),
                    LocalizedFields.of(detail.translations()),
                    FiscalClassificationView.of(detail.fiscal()));
        }
    }

    public record FiscalClassificationView(
            @Nullable String mxikCode,
            @Nullable String packageCode,
            @Nullable Integer fiscalUnitCode,
            @Nullable String fiscalName,
            @Nullable String barcode,
            boolean markingRequired,
            @Nullable String markingScheme,
            boolean excisable,
            @Nullable Integer alcoholByVolumeBp,
            @Nullable Integer ageRestrictionYears) {

        static @Nullable FiscalClassificationView of(CatalogQueryService.@Nullable FiscalClassificationView view) {
            if (view == null) {
                return null;
            }
            return new FiscalClassificationView(
                    view.mxikCode(),
                    view.packageCode(),
                    view.fiscalUnitCode(),
                    view.fiscalName(),
                    view.barcode(),
                    view.markingRequired(),
                    view.markingScheme(),
                    view.excisable(),
                    view.alcoholByVolumeBp(),
                    view.ageRestrictionYears());
        }
    }

    public record AttachedModifierGroupView(UUID groupId, int sortOrder) {

        static AttachedModifierGroupView of(CatalogQueryService.AttachedModifierGroup group) {
            return new AttachedModifierGroupView(group.groupId(), group.sortOrder());
        }
    }

    public record MediaRelationView(UUID mediaAssetId, String role, int sortOrder) {

        static MediaRelationView of(CatalogQueryService.MediaRelation media) {
            return new MediaRelationView(media.mediaAssetId(), media.role(), media.sortOrder());
        }
    }

    public record ModifierGroupSummaryResponse(
            UUID groupId,
            String code,
            String name,
            boolean required,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameOptionMultipleTimes,
            int optionCount,
            String status) {

        static ModifierGroupSummaryResponse of(CatalogQueryService.ModifierGroupSummary summary) {
            return new ModifierGroupSummaryResponse(
                    summary.groupId(),
                    summary.code(),
                    summary.name(),
                    summary.required(),
                    summary.minimumSelections(),
                    summary.maximumSelections(),
                    summary.allowSameOptionMultipleTimes(),
                    summary.optionCount(),
                    summary.status());
        }
    }

    public record ModifierGroupDetailResponse(
            UUID groupId,
            String code,
            boolean required,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameOptionMultipleTimes,
            Map<String, LocalizedFields> translations,
            List<ModifierOptionView> options) {

        static ModifierGroupDetailResponse of(CatalogQueryService.ModifierGroupDetail detail) {
            return new ModifierGroupDetailResponse(
                    detail.groupId(),
                    detail.code(),
                    detail.required(),
                    detail.minimumSelections(),
                    detail.maximumSelections(),
                    detail.allowSameOptionMultipleTimes(),
                    LocalizedFields.of(detail.translations()),
                    detail.options().stream().map(ModifierOptionView::of).toList());
        }
    }

    public record ModifierOptionView(
            UUID optionId,
            String code,
            Map<String, LocalizedFields> translations,
            @Nullable UUID linkedVariantId,
            int maximumQuantity,
            int sortOrder,
            String status,
            @Nullable FiscalClassificationView fiscal) {

        static ModifierOptionView of(CatalogQueryService.ModifierOptionView view) {
            return new ModifierOptionView(
                    view.optionId(),
                    view.code(),
                    LocalizedFields.of(view.translations()),
                    view.linkedVariantId(),
                    view.maximumQuantity(),
                    view.sortOrder(),
                    view.status(),
                    FiscalClassificationView.of(view.fiscal()));
        }
    }
}
