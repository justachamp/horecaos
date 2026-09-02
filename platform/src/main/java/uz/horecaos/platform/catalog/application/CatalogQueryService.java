package uz.horecaos.platform.catalog.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Category;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierOption;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Product;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore.AttachedGroup;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore.MediaRelationRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore.ProductRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore.TranslationRow;

/**
 * Reading back what {@link CatalogAuthoringService} wrote (ADR 0016).
 *
 * <p>{@code CatalogAuthoringService} has never had a read side beyond the 86
 * screen's {@code variantsAtLocation}: there was no HTTP way to render a
 * products list, a categories tree, or reopen a product for editing. The join
 * plumbing this composes was already written once, for {@link
 * CatalogSnapshotLoader} to build a publication snapshot from — this class
 * reuses the same {@link JdbcCatalogStore} reads and the same "load broadly,
 * group in Java" style, aimed at the operations app's authoring screens
 * instead of a publication.
 *
 * <p>Unlike a publication snapshot, nothing here is a customer-facing
 * guarantee: every method reads the live draft tables directly, the same
 * tables {@code CatalogAuthoringService} writes, so a change made a moment ago
 * is visible here immediately and without a republish.
 */
@Service
@Transactional(readOnly = true)
public class CatalogQueryService {

    private final JdbcCatalogStore store;
    private final String defaultLocale;

    public CatalogQueryService(
            JdbcCatalogStore store, @Value("${horecaos.catalog.default-locale:uz}") String defaultLocale) {
        this.store = store;
        this.defaultLocale = defaultLocale;
    }

    /** A brand's catalogs, named in the configured default locale. */
    public List<CatalogSummary> catalogs(UUID tenantId, UUID brandId) {
        Map<UUID, String> names = defaultLocaleNames(tenantId, brandId, EntityType.CATALOG);
        return store.catalogsForBrand(tenantId, brandId).stream()
                .map(row -> new CatalogSummary(
                        row.id(), row.code(), names.getOrDefault(row.id(), row.code()), row.status()))
                .toList();
    }

    /**
     * One catalog's categories, flat — the client builds the tree from each
     * category's {@code parentCategoryId}.
     */
    public List<CategorySummary> categories(UUID tenantId, UUID brandId, UUID catalogId) {
        List<Category> categories = store.categoriesInCatalog(tenantId, brandId, catalogId);
        Map<UUID, List<UUID>> productsByCategory = store.productIdsByCategory(tenantId, brandId, catalogId);
        Map<UUID, String> names = defaultLocaleNames(tenantId, brandId, EntityType.CATEGORY);
        return categories.stream()
                .map(category -> new CategorySummary(
                        category.id(),
                        category.parentCategoryId(),
                        category.code(),
                        names.getOrDefault(category.id(), category.code()),
                        category.sortOrder(),
                        category.status().name(),
                        productsByCategory
                                .getOrDefault(category.id(), List.of())
                                .size()))
                .toList();
    }

    /**
     * One page of a catalog's products, keyset-paginated by product id.
     *
     * <p>Returns a plain list rather than a {@code Page}: the "is this the last
     * page" decision needs the caller's own page size to apply the short-page
     * shortcut, and {@code CatalogQueryController} already has it — the same
     * split {@code variantsAtLocation} uses between {@code
     * CatalogAuthoringService} and its controller.
     */
    public List<ProductSummary> products(
            UUID tenantId, UUID brandId, UUID catalogId, @Nullable UUID cursor, int limit) {
        List<ProductRow> page = store.productsInCatalogPage(tenantId, brandId, catalogId, cursor, limit);
        if (page.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<Variant>> variantsByProduct = store.variantsInCatalog(tenantId, brandId, catalogId).stream()
                .collect(Collectors.groupingBy(Variant::productId));
        Map<UUID, String> productNames = defaultLocaleNames(tenantId, brandId, EntityType.PRODUCT);
        Map<UUID, String> categoryNames = defaultLocaleNames(tenantId, brandId, EntityType.CATEGORY);
        Map<UUID, String> categoryCodes = store.categoriesInCatalog(tenantId, brandId, catalogId).stream()
                .collect(Collectors.toMap(Category::id, Category::code));
        Map<UUID, List<UUID>> categoriesByProduct = invert(store.productIdsByCategory(tenantId, brandId, catalogId));
        Map<UUID, FiscalClassification> classifications = store.classificationsForBrand(tenantId, brandId);

        return page.stream()
                .map(row -> {
                    List<Variant> variants = variantsByProduct.getOrDefault(row.id(), List.of());
                    boolean hasMxik = variants.stream()
                            .map(variant -> classifications.get(variant.id()))
                            .anyMatch(fiscal -> fiscal != null && fiscal.mxikCode() != null);
                    List<String> productCategoryNames = categoriesByProduct.getOrDefault(row.id(), List.of()).stream()
                            .map(categoryId -> categoryNames.getOrDefault(
                                    categoryId, categoryCodes.getOrDefault(categoryId, categoryId.toString())))
                            .toList();
                    return new ProductSummary(
                            row.id(),
                            row.code(),
                            row.status(),
                            productNames.getOrDefault(row.id(), row.code()),
                            variants.size(),
                            productCategoryNames,
                            hasMxik,
                            row.version());
                })
                .toList();
    }

    /**
     * One product's full detail, for the product editor: every locale it has a
     * translation in (not just the default), its variants, its fiscal data, its
     * category and catalog membership, its attached modifier groups, and its
     * media.
     */
    public ProductDetail productDetail(UUID tenantId, UUID brandId, UUID productId) {
        Product product = store.productById(tenantId, brandId, productId)
                .orElseThrow(() -> new UnknownProductException(productId));
        List<Variant> variants = store.variantsForProduct(tenantId, brandId, productId);
        List<UUID> catalogIds = store.catalogsForProduct(tenantId, brandId, productId);
        List<UUID> categoryIds = store.categoriesForProduct(tenantId, brandId, productId);
        List<AttachedGroup> groups = store.modifierGroupsForProduct(tenantId, brandId, productId);

        Set<UUID> variantIds = variants.stream().map(Variant::id).collect(Collectors.toCollection(HashSet::new));
        Set<UUID> entityIds = new HashSet<>(variantIds);
        entityIds.add(productId);

        Map<UUID, Map<String, LocalizedFields>> translations = translationsFor(
                tenantId, brandId, Map.of(EntityType.PRODUCT, Set.of(productId), EntityType.VARIANT, variantIds));
        Map<UUID, FiscalClassification> classifications = store.classificationsForBrand(tenantId, brandId);
        List<MediaRelationRow> media = store.mediaRelationsForEntities(tenantId, brandId, entityIds);

        List<VariantDetail> variantDetails = variants.stream()
                .map(variant -> new VariantDetail(
                        variant.id(),
                        variant.sku(),
                        variant.unitCode(),
                        variant.isDefault(),
                        variant.sortOrder(),
                        variant.status().name(),
                        variant.version(),
                        translations.getOrDefault(variant.id(), Map.of()),
                        FiscalClassificationView.of(classifications.get(variant.id()))))
                .toList();

        List<AttachedModifierGroup> groupViews = groups.stream()
                .map(g -> new AttachedModifierGroup(g.groupId(), g.sortOrder()))
                .toList();

        List<MediaRelation> mediaViews = media.stream()
                .map(row -> new MediaRelation(row.mediaAssetId(), row.role(), row.sortOrder()))
                .toList();

        return new ProductDetail(
                product.id(),
                product.code(),
                product.status().name(),
                product.version(),
                translations.getOrDefault(productId, Map.of()),
                catalogIds,
                categoryIds,
                variantDetails,
                groupViews,
                mediaViews);
    }

    /** A brand's whole modifier group library, shared across its catalogs. */
    public List<ModifierGroupSummary> modifierGroups(UUID tenantId, UUID brandId) {
        List<ModifierGroup> groups = store.modifierGroupsForBrand(tenantId, brandId);
        Map<UUID, String> names = defaultLocaleNames(tenantId, brandId, EntityType.MODIFIER_GROUP);
        Map<UUID, Integer> optionCounts =
                store
                        .optionsForGroups(
                                tenantId,
                                brandId,
                                groups.stream().map(ModifierGroup::id).toList())
                        .stream()
                        .collect(Collectors.groupingBy(
                                ModifierOption::modifierGroupId, Collectors.summingInt(option -> 1)));
        return groups.stream()
                .map(group -> new ModifierGroupSummary(
                        group.id(),
                        group.code(),
                        names.getOrDefault(group.id(), group.code()),
                        group.required(),
                        group.minimumSelections(),
                        group.maximumSelections(),
                        group.allowSameOptionMultipleTimes(),
                        optionCounts.getOrDefault(group.id(), 0),
                        group.status().name()))
                .toList();
    }

    /** One modifier group's detail, with every option and each option's own translations. */
    public ModifierGroupDetail modifierGroupDetail(UUID tenantId, UUID brandId, UUID groupId) {
        ModifierGroup group = store.modifierGroupById(tenantId, brandId, groupId)
                .orElseThrow(() -> new UnknownModifierGroupException(groupId));
        List<ModifierOption> options = store.optionsForGroups(tenantId, brandId, List.of(groupId));

        Set<UUID> optionIds = options.stream().map(ModifierOption::id).collect(Collectors.toCollection(HashSet::new));
        Map<UUID, Map<String, LocalizedFields>> translations = translationsFor(
                tenantId,
                brandId,
                Map.of(EntityType.MODIFIER_GROUP, Set.of(groupId), EntityType.MODIFIER_OPTION, optionIds));
        Map<UUID, FiscalClassification> classifications = store.classificationsForBrand(tenantId, brandId);

        List<ModifierOptionView> optionViews = options.stream()
                .map(option -> new ModifierOptionView(
                        option.id(),
                        option.code(),
                        translations.getOrDefault(option.id(), Map.of()),
                        option.linkedVariantId(),
                        option.maximumQuantity(),
                        option.sortOrder(),
                        option.status().name(),
                        FiscalClassificationView.of(classifications.get(option.id()))))
                .toList();

        return new ModifierGroupDetail(
                group.id(),
                group.code(),
                group.required(),
                group.minimumSelections(),
                group.maximumSelections(),
                group.allowSameOptionMultipleTimes(),
                translations.getOrDefault(groupId, Map.of()),
                optionViews);
    }

    /**
     * Every locale each of these entities has a translation in, keyed first by
     * entity id and then by locale — the product editor's locale switcher needs
     * every locale with a row, not only the default one {@link
     * #defaultLocaleNames} resolves for a list screen.
     */
    private Map<UUID, Map<String, LocalizedFields>> translationsFor(
            UUID tenantId, UUID brandId, Map<EntityType, Set<UUID>> wantedByType) {
        Map<UUID, Map<String, LocalizedFields>> byEntity = new LinkedHashMap<>();
        for (TranslationRow row : store.translations(tenantId, brandId)) {
            Set<UUID> wanted = wantedByType.get(row.entityType());
            if (wanted == null || !wanted.contains(row.entityId())) {
                continue;
            }
            byEntity.computeIfAbsent(row.entityId(), id -> new LinkedHashMap<>())
                    .put(row.locale(), new LocalizedFields(row.name(), row.description()));
        }
        return byEntity;
    }

    /** Each entity's name in the brand's configured default locale, keyed by entity id. */
    private Map<UUID, String> defaultLocaleNames(UUID tenantId, UUID brandId, EntityType type) {
        Map<UUID, String> names = new HashMap<>();
        for (TranslationRow row : store.translations(tenantId, brandId)) {
            if (row.entityType() == type && defaultLocale.equals(row.locale())) {
                names.put(row.entityId(), row.name());
            }
        }
        return names;
    }

    /** Inverts a parent-to-children map into a child-to-parents map. */
    private static Map<UUID, List<UUID>> invert(Map<UUID, List<UUID>> productIdsByCategory) {
        Map<UUID, List<UUID>> categoriesByProduct = new LinkedHashMap<>();
        productIdsByCategory.forEach((categoryId, productIds) -> productIds.forEach(productId -> categoriesByProduct
                .computeIfAbsent(productId, id -> new ArrayList<>())
                .add(categoryId)));
        return categoriesByProduct;
    }

    /** A product this brand does not have — either never existed, or another brand's. */
    public static final class UnknownProductException extends RuntimeException {

        private final transient UUID productId;

        public UnknownProductException(UUID productId) {
            super("No product " + productId + " in this brand");
            this.productId = productId;
        }

        public UUID productId() {
            return productId;
        }
    }

    /** A modifier group this brand does not have — either never existed, or another brand's. */
    public static final class UnknownModifierGroupException extends RuntimeException {

        private final transient UUID groupId;

        public UnknownModifierGroupException(UUID groupId) {
            super("No modifier group " + groupId + " in this brand");
            this.groupId = groupId;
        }

        public UUID groupId() {
            return groupId;
        }
    }

    public record CatalogSummary(UUID catalogId, String code, String name, String status) {}

    public record CategorySummary(
            UUID categoryId,
            @Nullable UUID parentCategoryId,
            String code,
            String name,
            int sortOrder,
            String status,
            int productCount) {}

    public record ProductSummary(
            UUID productId,
            String code,
            String status,
            String name,
            int variantCount,
            List<String> categoryNames,
            boolean hasMxik,
            int version) {}

    /** Every locale this entity has a translation in, keyed by locale. */
    public record LocalizedFields(String name, @Nullable String description) {}

    public record ProductDetail(
            UUID productId,
            String code,
            String status,
            int version,
            Map<String, LocalizedFields> translations,
            List<UUID> catalogIds,
            List<UUID> categoryIds,
            List<VariantDetail> variants,
            List<AttachedModifierGroup> modifierGroups,
            List<MediaRelation> media) {}

    public record VariantDetail(
            UUID variantId,
            @Nullable String sku,
            String unitCode,
            boolean isDefault,
            int sortOrder,
            String status,
            int version,
            Map<String, LocalizedFields> translations,
            @Nullable FiscalClassificationView fiscal) {}

    /**
     * ADR 0038's classification, shaped for a read rather than a write.
     *
     * @param markingScheme name only; {@code NONE} never reaches a caller
     *                      because {@link #of} returns null for an empty
     *                      classification, and a non-empty one with marking off
     *                      also has nothing worth naming here
     */
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

        /** Null rather than a classification full of nulls, matching {@link FiscalClassification#isEmpty()}. */
        public static @Nullable FiscalClassificationView of(@Nullable FiscalClassification fiscal) {
            if (fiscal == null || fiscal.isEmpty()) {
                return null;
            }
            return new FiscalClassificationView(
                    fiscal.mxikCode(),
                    fiscal.packageCode(),
                    fiscal.fiscalUnitCode(),
                    fiscal.fiscalName(),
                    fiscal.barcode(),
                    fiscal.markingRequired(),
                    fiscal.markingScheme().name(),
                    fiscal.excisable(),
                    fiscal.alcoholByVolumeBasisPoints(),
                    fiscal.ageRestrictionYears());
        }
    }

    public record AttachedModifierGroup(UUID groupId, int sortOrder) {}

    public record MediaRelation(UUID mediaAssetId, String role, int sortOrder) {}

    public record ModifierGroupSummary(
            UUID groupId,
            String code,
            String name,
            boolean required,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameOptionMultipleTimes,
            int optionCount,
            String status) {}

    public record ModifierGroupDetail(
            UUID groupId,
            String code,
            boolean required,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameOptionMultipleTimes,
            Map<String, LocalizedFields> translations,
            List<ModifierOptionView> options) {}

    public record ModifierOptionView(
            UUID optionId,
            String code,
            Map<String, LocalizedFields> translations,
            @Nullable UUID linkedVariantId,
            int maximumQuantity,
            int sortOrder,
            String status,
            @Nullable FiscalClassificationView fiscal) {}
}
