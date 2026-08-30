package uz.horecaos.platform.catalog.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.catalog.api.VariantPricingLookup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Category;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.LocationOffering;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierOption;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Product;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PublicationItem;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore.MediaRelationRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore.TranslationRow;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAvailability;

/**
 * Assembles a whole catalog in one read, then turns it into publication items
 * (ADR 0016).
 *
 * <p>Loading is separated from validating so the validator can stay a pure
 * function: no database, no clock, no service calls, and therefore every rule
 * testable on a literal.
 *
 * <p>It also means the snapshot is read once inside the publishing transaction,
 * so validation and the snapshot that gets published cannot disagree — validating
 * one state and publishing another is the classic way a "validated" menu goes
 * live broken.
 */
@Component
public class CatalogSnapshotLoader {

    /**
     * ADR 0036's fulfilment mode for delivery, as an offering records it.
     *
     * <p>A string rather than an enum because the mode vocabulary is tenancy's
     * and catalog stores it as free text on the offering row; naming the constant
     * here at least keeps the literal in one place.
     */
    private static final String DELIVERY_MODE = "DELIVERY";

    private final JdbcCatalogStore store;
    private final MediaAvailability media;
    private final VariantPricingLookup pricing;
    private final String defaultLocale;
    private final boolean pricingWired;

    public CatalogSnapshotLoader(JdbcCatalogStore store, MediaAvailability media,
            VariantPricingLookup pricing,
            @Value("${horecaos.catalog.default-locale:uz}") String defaultLocale) {
        this.store = store;
        this.media = media;
        this.pricing = pricing;
        this.defaultLocale = defaultLocale;
        this.pricingWired = pricing.isWired();
    }

    public CatalogValidator.Snapshot load(UUID tenantId, UUID brandId, UUID catalogId) {
        List<Product> products = store.productsInCatalog(tenantId, brandId, catalogId);
        List<Variant> variants = store.variantsInCatalog(tenantId, brandId, catalogId);
        List<Category> categories = store.categoriesInCatalog(tenantId, brandId, catalogId);
        List<ModifierGroup> groups = store.modifierGroupsInCatalog(tenantId, brandId, catalogId);
        List<ModifierOption> options = store.optionsForGroups(tenantId, brandId,
                groups.stream().map(ModifierGroup::id).toList());
        // Membership, read in the same transaction as the entities it joins.
        // Without these the published menu is a flat list: no category a
        // customer can browse, and no group a customer can choose from.
        Map<UUID, List<UUID>> productIdsByCategory =
                store.productIdsByCategory(tenantId, brandId, catalogId);
        Map<UUID, List<UUID>> modifierGroupIdsByProduct =
                store.modifierGroupIdsByProduct(tenantId, brandId, catalogId);

        Map<UUID, List<Variant>> variantsByProduct = variants.stream()
                .collect(Collectors.groupingBy(Variant::productId));
        Map<UUID, Category> categoriesById = categories.stream()
                .collect(Collectors.toMap(Category::id, category -> category));
        Map<UUID, List<ModifierOption>> optionsByGroup = options.stream()
                .collect(Collectors.groupingBy(ModifierOption::modifierGroupId));

        Map<String, CatalogValidator.LocalizedText> translations = new LinkedHashMap<>();
        for (TranslationRow row : store.translations(tenantId, brandId)) {
            translations.put(
                    CatalogValidator.Snapshot.translationKey(row.entityType(), row.entityId(), row.locale()),
                    new CatalogValidator.LocalizedText(row.locale(), row.name(), row.description()));
        }

        // Only media hanging off entities in this catalog. A brand's other
        // images are not this menu's problem, and validating them would block a
        // publication over a picture the customer will never see.
        Set<UUID> entityIds = new HashSet<>();
        products.forEach(product -> entityIds.add(product.id()));
        variants.forEach(variant -> entityIds.add(variant.id()));
        categories.forEach(category -> entityIds.add(category.id()));
        options.forEach(option -> entityIds.add(option.id()));

        Map<MediaAssetId, Set<UUID>> mediaReferences = new LinkedHashMap<>();
        for (MediaRelationRow relation : store.mediaRelations(tenantId, brandId)) {
            if (entityIds.contains(relation.entityId())) {
                mediaReferences
                        .computeIfAbsent(new MediaAssetId(relation.mediaAssetId()), key -> new HashSet<>())
                        .add(relation.entityId());
            }
        }

        Set<MediaAssetId> displayable = mediaReferences.keySet().stream()
                .filter(assetId -> media.allDisplayable(tenantId, Set.of(assetId)))
                .collect(Collectors.toUnmodifiableSet());

        Set<UUID> variantIds = variants.stream().map(Variant::id).collect(Collectors.toSet());
        Set<UUID> priced = pricing.pricedVariants(tenantId, brandId, variantIds);

        List<LocationOffering> offerings = store.offeringsForBrand(tenantId, brandId);
        Set<UUID> offered = offerings.stream()
                .map(LocationOffering::variantId)
                .collect(Collectors.toUnmodifiableSet());

        return new CatalogValidator.Snapshot(
                defaultLocale, products, variants, variantsByProduct,
                categories, categoriesById, productIdsByCategory, modifierGroupIdsByProduct,
                groups, optionsByGroup,
                Map.copyOf(translations), mediaReferences, displayable, priced, offered,
                loadFiscalContext(tenantId, brandId, offerings),
                pricingWired);
    }

    /**
     * The fiscal inputs, read in the same transaction as the rest (ADR 0038).
     *
     * <p>Classifications are loaded for the whole brand rather than for this
     * catalog's nodes, because a modifier option resolves through a link to a
     * variant that may belong to another of the brand's catalogs, and a
     * catalog-scoped read would report that option as unclassified when it is
     * not.
     */
    private CatalogValidator.FiscalContext loadFiscalContext(UUID tenantId, UUID brandId,
            List<LocationOffering> offerings) {

        Map<UUID, FiscalClassification> byNode = store.classificationsForBrand(tenantId, brandId);

        // Whether the delivery fee is a line this brand can produce at all. Read
        // from the offerings rather than from the channel registry because the
        // question is what this brand sells for delivery, and an offering is
        // where that is stated per location.
        boolean offersDelivery = offerings.stream()
                .anyMatch(offering -> offering.status() != OfferingStatus.HIDDEN
                        && offering.fulfillmentModes().stream()
                                .anyMatch(DELIVERY_MODE::equalsIgnoreCase));

        boolean referenceLoaded = store.mxikReferenceIsLoaded();
        Set<String> knownCodes = referenceLoaded
                ? store.knownMxikCodes(byNode.values().stream()
                        .map(FiscalClassification::mxikCode)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toUnmodifiableSet()))
                : Set.of();

        return new CatalogValidator.FiscalContext(byNode,
                store.feesForBrand(tenantId, brandId), offersDelivery,
                referenceLoaded, knownCodes);
    }

    /**
     * Flattens a snapshot into the rows the storefront will read.
     *
     * <p>Each item carries the entity's content as a copy, not a reference. That
     * copy is what makes a published menu immune to a later draft edit, and it is
     * why publication items are insert-only at the grant level.
     */
    public List<PublicationItem> toPublicationItems(CatalogValidator.Snapshot snapshot) {
        List<PublicationItem> items = new ArrayList<>();

        for (Category category : snapshot.categories()) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("code", category.code());
            // Omitted when absent rather than written as the string "null".
            putIfPresent(content, "parentCategoryId", category.parentCategoryId());
            content.put("sortOrder", category.sortOrder());
            content.put("status", category.status().name());
            // Names travel with the snapshot. The storefront reads only
            // publication items, so a name left behind here would render as a
            // database code.
            content.put("names", names(snapshot, EntityType.CATEGORY, category.id()));
            // Membership travels with the category rather than the product so
            // that sort order within the category survives -- a product sits in
            // more than one category and is ordered differently in each.
            content.put("productIds", idStrings(snapshot.productIdsByCategory()
                    .getOrDefault(category.id(), List.of())));

            items.add(new PublicationItem(EntityType.CATEGORY, category.id(),
                    category.version(), content));
        }

        for (Product product : snapshot.products()) {
            List<Map<String, Object>> productVariants = snapshot.variantsByProduct()
                    .getOrDefault(product.id(), List.of()).stream()
                    .map(variant -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("variantId", variant.id().toString());
                        // A variant genuinely may have no SKU. Writing "null" here
                        // put a real-looking string in front of customers and in
                        // every downstream consumer keying on sku — and because
                        // publication items are insert-only, it could not be
                        // corrected without republishing the whole menu.
                        putIfPresent(entry, "sku", variant.sku());
                        entry.put("unitCode", variant.unitCode());
                        // Resolved, not raw. A partner adapter reading this
                        // snapshot has no access to the product row to inherit
                        // from, and a fiscal receipt built from a published menu
                        // must not have to reach back into authoring — which is
                        // mutable and may have moved on.
                        putClassification(entry, snapshot.effectiveClassification(variant));
                        entry.put("isDefault", variant.isDefault());
                        entry.put("sortOrder", variant.sortOrder());
                        entry.put("status", variant.status().name());
                        return entry;
                    })
                    .toList();

            Map<String, Object> content = new LinkedHashMap<>();
            content.put("code", product.code());
            // No classification on a product. It is not priceable and never
            // reaches a receipt as its own line, so a code here would be a second
            // place to look with no rule for which one wins.
            content.put("status", product.status().name());
            content.put("variants", productVariants);
            content.put("names", names(snapshot, EntityType.PRODUCT, product.id()));
            content.put("mediaAssetIds", mediaFor(snapshot, product.id()));
            // Product-level only. V0016 also has variant_modifier_groups and
            // nothing writes it, so publishing a variant-level link would put an
            // always-empty list in front of every client.
            content.put("modifierGroupIds", idStrings(snapshot.modifierGroupIdsByProduct()
                    .getOrDefault(product.id(), List.of())));

            items.add(new PublicationItem(EntityType.PRODUCT, product.id(),
                    product.version(), content));
        }

        for (ModifierGroup group : snapshot.modifierGroups()) {
            List<Map<String, Object>> groupOptions = snapshot.optionsByGroup()
                    .getOrDefault(group.id(), List.of()).stream()
                    .map(option -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("optionId", option.id().toString());
                        entry.put("code", option.code());
                        putIfPresent(entry, "linkedVariantId", option.linkedVariantId());
                        putClassification(entry, snapshot.effectiveClassification(option));
                        entry.put("maximumQuantity", option.maximumQuantity());
                        entry.put("sortOrder", option.sortOrder());
                        entry.put("status", option.status().name());
                        return entry;
                    })
                    .toList();

            Map<String, Object> content = new LinkedHashMap<>();
            content.put("code", group.code());
            content.put("required", group.required());
            content.put("minimumSelections", group.minimumSelections());
            content.put("maximumSelections", group.maximumSelections());
            content.put("allowSameOptionMultipleTimes", group.allowSameOptionMultipleTimes());
            content.put("sortOrder", group.sortOrder());
            content.put("names", names(snapshot, EntityType.MODIFIER_GROUP, group.id()));
            content.put("options", groupOptions);

            items.add(new PublicationItem(EntityType.MODIFIER_GROUP, group.id(),
                    group.version(), content));
        }

        return List.copyOf(items);
    }

    /**
     * Identifiers as strings, matching how every other identifier is written
     * into publication content.
     */
    private static List<String> idStrings(List<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }

    /**
     * Writes a value only when it exists.
     *
     * <p>The alternative, {@code String.valueOf(x)}, turns a null into the string
     * {@code "null"} — which reaches the storefront looking like a real value.
     */
    /**
     * Writes the classification a receipt line will be built from, omitting each
     * field that is absent (ADR 0038).
     *
     * <p>Omitted rather than written as null, for the same reason the SKU is: a
     * publication item is insert-only, so a placeholder that reaches an
     * aggregator as a real-looking code cannot be corrected without republishing
     * the entire menu — and a wrong code on a receipt is a tax classification
     * error, not a cosmetic one.
     *
     * <p>The marking flag is written even when false, because it is the one field
     * here that a consumer must act on rather than copy: a payment surface
     * reading a published menu decides which methods to offer from it, and
     * "absent" and "not marked" have to be the same answer for that to be safe.
     */
    private static void putClassification(Map<String, Object> target,
            FiscalClassification fiscal) {
        putIfPresent(target, "mxikCode", fiscal.mxikCode());
        putIfPresent(target, "packageCode", fiscal.packageCode());
        putIfPresent(target, "fiscalUnitCode", fiscal.fiscalUnitCode());
        putIfPresent(target, "fiscalName", fiscal.fiscalName());
        putIfPresent(target, "barcode", fiscal.barcode());
        target.put("markingRequired", fiscal.markingRequired());
        if (fiscal.markingRequired()) {
            target.put("markingScheme", fiscal.markingScheme().name());
        }
        if (fiscal.excisable()) {
            target.put("excisable", true);
        }
        putIfPresent(target, "alcoholByVolumeBp", fiscal.alcoholByVolumeBasisPoints());
        putIfPresent(target, "ageRestrictionYears", fiscal.ageRestrictionYears());
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value instanceof java.util.UUID id ? id.toString() : value);
        }
    }

    /** Every locale this entity has a name in, keyed by locale. */
    private static Map<String, Map<String, String>> names(
            CatalogValidator.Snapshot snapshot, EntityType type, UUID entityId) {

        Map<String, Map<String, String>> byLocale = new LinkedHashMap<>();
        snapshot.translations().forEach((key, text) -> {
            if (key.startsWith(type.name() + ":" + entityId + ":")) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("name", text.name());
                if (text.description() != null) {
                    entry.put("description", text.description());
                }
                byLocale.put(text.locale(), entry);
            }
        });
        return byLocale;
    }

    private static List<String> mediaFor(CatalogValidator.Snapshot snapshot, UUID entityId) {
        return snapshot.mediaReferences().entrySet().stream()
                .filter(entry -> entry.getValue().contains(entityId))
                .map(entry -> entry.getKey().toString())
                .toList();
    }
}
