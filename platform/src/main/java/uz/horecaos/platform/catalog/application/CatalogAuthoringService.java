package uz.horecaos.platform.catalog.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierOption;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PriceableNode;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Product;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.UsageMeter;
import uz.horecaos.platform.commercial.api.UsageMovement;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.media.api.MediaAssetId;

/**
 * Draft authoring (ADR 0016).
 *
 * <p>Nothing here reaches a customer. Every write lands in the authoring tables,
 * and a menu only changes when {@link CatalogPublicationService} takes a snapshot
 * — which is what lets an operator edit a live brand's catalog in the middle of
 * service without anything changing under the customers currently ordering.
 *
 * <p>{@link #createProduct} is also this ADR 0021 wave's one enforced quantity
 * limit: {@code catalog.products.max_count} is checked with {@link
 * EntitlementService#require} before the insert, the same "before mutation"
 * shape ADR 0021's own enforcement semantics describe and {@code
 * CampaignService#start} already demonstrates for a feature gate. {@code
 * commercial} does not import {@code catalog}, so — unlike {@code tenancy},
 * which {@code commercial} already depends on for its ADR 0030 configuration
 * lookup — calling the port here creates no module cycle, which is why this
 * key is the one enforced synchronously rather than metered through a listener.
 */
@Service
public class CatalogAuthoringService {

    private final JdbcCatalogStore store;
    private final AuditRecorder audit;
    private final EntitlementService entitlements;
    private final UsageMeter usage;
    private final Clock clock;

    public CatalogAuthoringService(
            JdbcCatalogStore store,
            AuditRecorder audit,
            EntitlementService entitlements,
            UsageMeter usage,
            Clock clock) {
        this.store = store;
        this.audit = audit;
        this.entitlements = entitlements;
        this.usage = usage;
        this.clock = clock;
    }

    @Transactional
    public UUID createCatalog(UUID tenantId, UUID brandId, String code, String name, String locale) {
        UUID catalogId = UUID.randomUUID();
        store.insertCatalog(catalogId, tenantId, brandId, code, name);
        store.upsertTranslation(tenantId, brandId, EntityType.CATALOG, catalogId, locale, name, null);
        return catalogId;
    }

    /**
     * Creates a product with its first variant.
     *
     * <p>Together rather than separately because a product with no variant cannot
     * be published, so creating one alone would only ever be a half-finished
     * state an operator has to remember to come back to.
     */
    @Transactional
    public ProductCreated createProduct(
            UUID tenantId,
            UUID brandId,
            UUID catalogId,
            String code,
            String name,
            @Nullable String description,
            String locale,
            @Nullable String sku,
            @Nullable String unitCode,
            FiscalClassification fiscal,
            @Nullable UUID actorId) {

        // Before the insert, per ADR 0021's own enforcement semantics ("reject a
        // capacity-increasing action before mutation"). Under this wave's
        // meter-only default this never throws; once a plan sets a real limit
        // under HARD, a tenant that is over is refused here rather than after a
        // product row already exists that the response then has to pretend was
        // never created.
        entitlements.require(tenantId, EntitlementKeys.CATALOG_PRODUCTS_MAX_COUNT, 1);

        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        store.insertProduct(productId, tenantId, brandId, code, Status.ACTIVE);
        usage.record(new UsageMovement(
                tenantId,
                EntitlementKeys.CATALOG_PRODUCTS_MAX_COUNT,
                1,
                "catalog.ProductCreated",
                productId.toString(),
                clock.instant(),
                Map.of("brand_id", brandId.toString())));
        store.insertVariant(
                variantId,
                tenantId,
                brandId,
                productId,
                sku,
                unitCode == null ? "PIECE" : unitCode,
                true,
                0,
                Status.ACTIVE);
        // The classification lands on the default variant, not on the product.
        // The variant is what carries a price and therefore what appears on a
        // receipt line; a code on the product would have to be resolved through
        // a row the published snapshot does not contain (ADR 0038).
        classify(tenantId, brandId, PriceableNode.variant(variantId), fiscal, actorId);
        store.addProductToCatalog(tenantId, brandId, catalogId, productId, 0);
        store.upsertTranslation(tenantId, brandId, EntityType.PRODUCT, productId, locale, name, description);

        return new ProductCreated(productId, variantId);
    }

    /**
     * Adds a variant to an existing product.
     *
     * @param fiscal this variant's own classification. Every size of a dish is
     *               its own receipt line with its own unit and its own
     *               63-character fiscal name, so there is nothing sensible to
     *               inherit from a sibling
     */
    @Transactional
    public UUID addVariant(
            UUID tenantId,
            UUID brandId,
            UUID productId,
            @Nullable String sku,
            @Nullable String unitCode,
            @Nullable String name,
            String locale,
            int sortOrder,
            FiscalClassification fiscal,
            @Nullable UUID actorId) {
        UUID variantId = UUID.randomUUID();
        store.insertVariant(
                variantId,
                tenantId,
                brandId,
                productId,
                sku,
                unitCode == null ? "PIECE" : unitCode,
                false,
                sortOrder,
                Status.ACTIVE);
        classify(tenantId, brandId, PriceableNode.variant(variantId), fiscal, actorId);
        if (name != null) {
            store.upsertTranslation(tenantId, brandId, EntityType.VARIANT, variantId, locale, name, null);
        }
        return variantId;
    }

    /**
     * @throws UnknownCatalogEntityException {@code parentCategoryId} does not
     *         name a category of this same {@code catalogId} — a category
     *         belonging to a sibling catalog in the same brand would
     *         otherwise pass silently (the fk only checks tenant+brand) and
     *         produce a parent link that spans two catalogs
     */
    @Transactional
    public UUID createCategory(
            UUID tenantId,
            UUID brandId,
            UUID catalogId,
            @Nullable UUID parentCategoryId,
            String code,
            String name,
            String locale,
            int sortOrder) {
        if (parentCategoryId != null) {
            requireParentInCatalog(tenantId, brandId, catalogId, parentCategoryId);
        }
        UUID categoryId = UUID.randomUUID();
        store.insertCategory(
                categoryId, tenantId, brandId, catalogId, parentCategoryId, code, sortOrder, Status.ACTIVE);
        store.upsertTranslation(tenantId, brandId, EntityType.CATEGORY, categoryId, locale, name, null);
        return categoryId;
    }

    @Transactional
    public void placeProductInCategory(UUID tenantId, UUID brandId, UUID categoryId, UUID productId, int sortOrder) {
        store.addProductToCategory(tenantId, brandId, categoryId, productId, sortOrder);
    }

    /** The undo {@link #placeProductInCategory} never had. */
    @Transactional
    public boolean removeProductFromCategory(UUID tenantId, UUID brandId, UUID categoryId, UUID productId) {
        return store.removeProductFromCategory(tenantId, brandId, categoryId, productId);
    }

    /** The undo a catalog's own {@code addProductToCatalog} never had. */
    @Transactional
    public boolean removeProductFromCatalog(UUID tenantId, UUID brandId, UUID catalogId, UUID productId) {
        return store.removeProductFromCatalog(tenantId, brandId, catalogId, productId);
    }

    /**
     * Changes a product's own status. catalog.md §4.2 tab 1 rendered
     * Черновик/Активен as read-only text because nothing here could change it.
     *
     * @return true when the product exists in this brand
     */
    @Transactional
    public boolean setProductStatus(UUID tenantId, UUID brandId, UUID productId, Status status) {
        return store.updateProductStatus(tenantId, brandId, productId, status);
    }

    /**
     * Corrects a variant's SKU, unit and status — the variants tab was
     * otherwise read-only apart from the price input. The name is set through
     * {@link #translate} with {@link EntityType#VARIANT}, the same path every
     * other entity's name already uses.
     *
     * @return true when the variant exists in this brand and belongs to
     *         {@code productId} — a variantId that names a real variant of a
     *         *different* product in the same brand is refused exactly like
     *         one that does not exist at all, rather than silently updating
     *         the wrong product's variant under the caller's own {@code
     *         productId}
     */
    @Transactional
    public boolean updateVariant(
            UUID tenantId,
            UUID brandId,
            UUID productId,
            UUID variantId,
            @Nullable String sku,
            String unitCode,
            Status status) {
        return store.updateVariant(tenantId, brandId, productId, variantId, sku, unitCode, status);
    }

    /** Makes one variant of a product the default, and no other. */
    @Transactional
    public boolean setDefaultVariant(UUID tenantId, UUID brandId, UUID productId, UUID variantId) {
        return store.setDefaultVariant(tenantId, brandId, productId, variantId);
    }

    /**
     * Reparents, renames the code of, and re-sorts an existing category —
     * categories were write-once before this wave (row {@code 4.3}).
     *
     * <p>The database refuses only the one-step cycle, a category naming
     * itself as its own parent ({@code ck_category_not_self_parent}). A longer
     * cycle — reparenting a category under its own descendant — passes that
     * constraint and would otherwise only be caught later, as a publication
     * blocker ({@code CatalogValidator}'s {@code CATEGORY_TREE_HAS_CYCLE}), by
     * which point the tree has already been unusable for however long nobody
     * published. So it is refused here, synchronously, before the write:
     * {@link #wouldCreateCycle} walks the brand's existing category graph from
     * the proposed new parent upward, and if that walk ever reaches {@code
     * categoryId} itself, the reparent would make the category its own
     * ancestor.
     *
     * @throws CategoryTreeCycleException the reparent would create a cycle
     * @throws UnknownCatalogEntityException no such category in this brand and catalog, or
     *         {@code parentCategoryId} names a category belonging to a *different* catalog
     *         in the same brand
     */
    @Transactional
    public void updateCategory(
            UUID tenantId,
            UUID brandId,
            UUID catalogId,
            UUID categoryId,
            @Nullable UUID parentCategoryId,
            String code,
            int sortOrder) {
        if (parentCategoryId != null) {
            assertNoCycle(tenantId, brandId, catalogId, categoryId, parentCategoryId);
        }
        boolean updated =
                store.updateCategory(tenantId, brandId, catalogId, categoryId, parentCategoryId, code, sortOrder);
        if (!updated) {
            throw new UnknownCatalogEntityException(EntityType.CATEGORY, categoryId);
        }
    }

    /**
     * Archives a category. Never a hard delete — a product still placed in it
     * keeps its row, matching catalog.md's "archive, never delete" rule for
     * every catalog entity.
     *
     * @throws UnknownCatalogEntityException no such category in this brand and catalog
     */
    @Transactional
    public void archiveCategory(UUID tenantId, UUID brandId, UUID catalogId, UUID categoryId) {
        boolean archived = store.archiveCategory(tenantId, brandId, catalogId, categoryId);
        if (!archived) {
            throw new UnknownCatalogEntityException(EntityType.CATEGORY, categoryId);
        }
    }

    /**
     * Refuses a reparent that would make {@code categoryId} its own ancestor.
     *
     * <p>Walks from {@code proposedParentId} upward through the brand's
     * existing {@code parentCategoryId} links. Reaching {@code categoryId}
     * means the reparent closes a loop back to itself. A pre-existing cycle
     * elsewhere in the tree — unrelated to this category — is left for {@code
     * CatalogValidator} to report at publication; this walk only needs to
     * terminate, not to certify the rest of the tree, so it stops the moment it
     * revisits a node rather than looping forever.
     */
    private void assertNoCycle(UUID tenantId, UUID brandId, UUID catalogId, UUID categoryId, UUID proposedParentId) {
        Map<UUID, UUID> parentById = categoryParentsInCatalog(tenantId, brandId, catalogId);
        // A proposedParentId absent from this catalog's own categories is
        // either unknown outright or belongs to a sibling catalog in the same
        // brand — the walk below would otherwise terminate immediately
        // (current = null on the first lookup) and read as "no cycle", when
        // what actually happened is a parent link that spans two catalogs,
        // which CatalogValidator only catches later, at publication.
        if (!parentById.containsKey(proposedParentId)) {
            throw new UnknownCatalogEntityException(EntityType.CATEGORY, proposedParentId);
        }

        java.util.Set<UUID> walked = new java.util.LinkedHashSet<>();
        UUID current = proposedParentId;
        while (current != null) {
            if (current.equals(categoryId)) {
                throw new CategoryTreeCycleException(categoryId, proposedParentId, walked);
            }
            if (!walked.add(current)) {
                // A cycle exists already, but not through categoryId — not this
                // reparent's problem to fix.
                return;
            }
            current = parentById.get(current);
        }
    }

    private Map<UUID, UUID> categoryParentsInCatalog(UUID tenantId, UUID brandId, UUID catalogId) {
        Map<UUID, UUID> parentById = new java.util.HashMap<>();
        for (var category : store.categoriesInCatalog(tenantId, brandId, catalogId)) {
            parentById.put(category.id(), category.parentCategoryId());
        }
        return parentById;
    }

    /**
     * @throws UnknownCatalogEntityException {@code parentCategoryId} is not a
     *         category of this {@code catalogId} — unknown outright, or a
     *         real category belonging to a sibling catalog in the same brand
     */
    private void requireParentInCatalog(UUID tenantId, UUID brandId, UUID catalogId, UUID parentCategoryId) {
        if (!categoryParentsInCatalog(tenantId, brandId, catalogId).containsKey(parentCategoryId)) {
            throw new UnknownCatalogEntityException(EntityType.CATEGORY, parentCategoryId);
        }
    }

    /**
     * Sets the offering status of many variants at one location in a single
     * gesture — catalog.md §4.5's bulk stop/unstop, absent until this wave.
     *
     * <p>Loops rather than one bulk statement: the set a bulk selection spans
     * is bounded by what one screen can show and select (the matrix's own
     * cursor page size), never large enough that N round trips inside one
     * transaction cost more than the plumbing a single multi-row statement
     * would need.
     *
     * @return how many variants were updated
     */
    @Transactional
    public int bulkSetOfferingStatus(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            List<UUID> variantIds,
            OfferingStatus status,
            String actorSubject) {
        for (UUID variantId : variantIds) {
            store.upsertOfferingStatus(tenantId, brandId, locationId, variantId, status);
        }

        if (!variantIds.isEmpty()) {
            audit.record(AuditFact.of("catalog.offering.bulkSet", AuditClass.BUSINESS)
                    .by(ActorRef.user(actorSubject, null))
                    .at(ResourceScope.location(tenantId, brandId, locationId))
                    .target("LocationOffering", locationId)
                    .because("Bulk-set %d variants to %s".formatted(variantIds.size(), status))
                    .usingCapability(Capability.CATALOG_AUTHOR.code())
                    .changed(Map.of("status", status.name(), "variantCount", variantIds.size()))
                    .correlatedBy(locationId.toString())
                    .occurredAt(clock.instant())
                    .build());
        }
        return variantIds.size();
    }

    @Transactional
    public UUID createModifierGroup(
            UUID tenantId,
            UUID brandId,
            String code,
            String name,
            String locale,
            boolean required,
            int minimum,
            int maximum,
            boolean allowRepeat) {
        UUID groupId = UUID.randomUUID();
        store.insertModifierGroup(new ModifierGroup(
                groupId, tenantId, brandId, code, required, minimum, maximum, allowRepeat, 0, Status.ACTIVE, 1));
        store.upsertTranslation(tenantId, brandId, EntityType.MODIFIER_GROUP, groupId, locale, name, null);
        return groupId;
    }

    /**
     * Adds an option to an existing modifier group.
     *
     * @param fiscal a modifier reaches a receipt as its own line, so it carries
     *               its own ИКПУ/MXIK. Left unclassified it falls back to the
     *               linked variant's, when the modifier is itself something
     *               sellable
     */
    @Transactional
    public UUID addModifierOption(
            UUID tenantId,
            UUID brandId,
            UUID groupId,
            String code,
            String name,
            String locale,
            @Nullable UUID linkedVariantId,
            int maximumQuantity,
            int sortOrder,
            FiscalClassification fiscal,
            @Nullable UUID actorId) {
        UUID optionId = UUID.randomUUID();
        store.insertModifierOption(new ModifierOption(
                optionId,
                tenantId,
                brandId,
                groupId,
                code,
                linkedVariantId,
                maximumQuantity,
                sortOrder,
                Status.ACTIVE,
                1));
        classify(tenantId, brandId, PriceableNode.modifierOption(optionId), fiscal, actorId);
        store.upsertTranslation(tenantId, brandId, EntityType.MODIFIER_OPTION, optionId, locale, name, null);
        return optionId;
    }

    /**
     * Duplicates a product: every variant (its own fiscal classification and
     * every locale's translation), its catalog and category placements, its
     * attached modifier groups, and its media — everything a 600-item
     * onboarding operator would otherwise retype variant by variant and
     * locale by locale for "the same dish, slightly different." catalog.md
     * §4.1's row action; no endpoint answered it before this.
     *
     * <p>The duplicate starts at the source product's own status: an
     * ARCHIVED product does not spring back to sellable just because it was
     * copied, and an ACTIVE one is exactly as ready as the row an operator
     * picked. Its code, and a variant's SKU when it had one, are suffixed
     * with a slice of the new id — {@code uq_product_code}/{@code
     * uq_variant_sku} would otherwise refuse the copy outright.
     */
    @Transactional
    public ProductCreated duplicateProduct(UUID tenantId, UUID brandId, UUID productId, @Nullable UUID actorId) {
        Product original = store.productById(tenantId, brandId, productId)
                .orElseThrow(() -> new UnknownProductException(productId));

        // Same "before mutation" shape as createProduct: a duplicate is one
        // more product row, and the entitlement is checked before any of it
        // is written.
        entitlements.require(tenantId, EntitlementKeys.CATALOG_PRODUCTS_MAX_COUNT, 1);

        UUID newProductId = UUID.randomUUID();
        store.insertProduct(
                newProductId, tenantId, brandId, suffixed(original.code(), newProductId), original.status());
        usage.record(new UsageMovement(
                tenantId,
                EntitlementKeys.CATALOG_PRODUCTS_MAX_COUNT,
                1,
                "catalog.ProductDuplicated",
                newProductId.toString(),
                clock.instant(),
                // Only "brand_id" is allowlisted for this key (EntitlementKeys);
                // the source product id belongs in sourceEventId's neighbourhood,
                // not in a dimension, which UsageMovement refuses at construction.
                Map.of("brand_id", brandId.toString())));

        List<JdbcCatalogStore.TranslationRow> allTranslations = store.translations(tenantId, brandId);
        copyTranslations(tenantId, brandId, allTranslations, EntityType.PRODUCT, productId, newProductId);

        Map<UUID, FiscalClassification> classifications = store.classificationsForBrand(tenantId, brandId);
        Map<UUID, UUID> variantIdMap = new HashMap<>();
        UUID defaultVariantId = null;
        for (Variant variant : store.variantsForProduct(tenantId, brandId, productId)) {
            UUID newVariantId = UUID.randomUUID();
            variantIdMap.put(variant.id(), newVariantId);
            if (variant.isDefault()) {
                defaultVariantId = newVariantId;
            }
            store.insertVariant(
                    newVariantId,
                    tenantId,
                    brandId,
                    newProductId,
                    variant.sku() == null ? null : suffixed(variant.sku(), newVariantId),
                    variant.unitCode(),
                    variant.isDefault(),
                    variant.sortOrder(),
                    variant.status());
            FiscalClassification fiscal = classifications.get(variant.id());
            if (fiscal != null) {
                classify(tenantId, brandId, PriceableNode.variant(newVariantId), fiscal, actorId);
            }
            copyTranslations(tenantId, brandId, allTranslations, EntityType.VARIANT, variant.id(), newVariantId);
        }

        int catalogSortOrder = 0;
        for (UUID catalogId : store.catalogsForProduct(tenantId, brandId, productId)) {
            store.addProductToCatalog(tenantId, brandId, catalogId, newProductId, catalogSortOrder++);
        }
        int categorySortOrder = 0;
        for (UUID categoryId : store.categoriesForProduct(tenantId, brandId, productId)) {
            store.addProductToCategory(tenantId, brandId, categoryId, newProductId, categorySortOrder++);
        }
        for (JdbcCatalogStore.AttachedGroup group : store.modifierGroupsForProduct(tenantId, brandId, productId)) {
            store.attachModifierGroupToProduct(tenantId, brandId, newProductId, group.groupId(), group.sortOrder());
        }

        Set<UUID> sourceEntityIds = new HashSet<>(variantIdMap.keySet());
        sourceEntityIds.add(productId);
        for (JdbcCatalogStore.MediaRelationRow media :
                store.mediaRelationsForEntities(tenantId, brandId, sourceEntityIds)) {
            UUID newEntityId = media.entityId().equals(productId) ? newProductId : variantIdMap.get(media.entityId());
            if (newEntityId != null) {
                store.attachMedia(
                        tenantId,
                        brandId,
                        media.entityType(),
                        newEntityId,
                        media.mediaAssetId(),
                        media.role(),
                        media.sortOrder());
            }
        }

        // Every product this service creates has exactly one default variant
        // (createProduct enforces it), so this is reached only if that
        // invariant was somehow broken upstream of here — falling back to any
        // copied variant, or the product itself, is strictly better than a
        // null the caller was not typed to expect.
        if (defaultVariantId == null) {
            defaultVariantId = variantIdMap.values().stream().findFirst().orElse(newProductId);
        }
        return new ProductCreated(newProductId, defaultVariantId);
    }

    private void copyTranslations(
            UUID tenantId,
            UUID brandId,
            List<JdbcCatalogStore.TranslationRow> allTranslations,
            EntityType type,
            UUID sourceId,
            UUID targetId) {
        for (JdbcCatalogStore.TranslationRow row : allTranslations) {
            if (row.entityType() == type && row.entityId().equals(sourceId)) {
                store.upsertTranslation(tenantId, brandId, type, targetId, row.locale(), row.name(), row.description());
            }
        }
    }

    /** A short, uppercase slice of {@code id} appended to {@code value} — just enough to dodge a unique constraint. */
    private static String suffixed(String value, UUID id) {
        return value + "-" + id.toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
    }

    /**
     * Changes a product's status — Черновик/Активен/Архивирован, catalog.md
     * §4.1's archive/restore row action. {@code Product.status} has carried
     * this since V0016; nothing mutated it until now.
     */
    @Transactional
    public void setProductStatus(UUID tenantId, UUID brandId, UUID productId, Status status, String actorSubject) {
        Product product = store.productById(tenantId, brandId, productId)
                .orElseThrow(() -> new UnknownProductException(productId));
        if (product.status() == status) {
            return;
        }
        store.updateProductStatus(tenantId, brandId, productId, status);
        audit.record(AuditFact.of("catalog.product.status_changed", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Product", productId)
                .because("Changed product status from " + product.status() + " to " + status)
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(Map.of("previousStatus", product.status().name(), "status", status.name()))
                .correlatedBy(productId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * Stops a product in every branch it is currently offered in — a
     * product-level fan-out over {@link #setOffering}'s own table, done in
     * one statement rather than once per branch. catalog.md §4.1's row
     * action; nothing computed "every branch for this product" before this.
     *
     * @return how many location offerings changed
     */
    @Transactional
    public int stopInAllBranches(UUID tenantId, UUID brandId, UUID productId, String actorSubject) {
        if (store.productById(tenantId, brandId, productId).isEmpty()) {
            throw new UnknownProductException(productId);
        }
        int changed = store.stopProductEverywhere(tenantId, brandId, productId);
        if (changed > 0) {
            audit.record(AuditFact.of("catalog.product.stopped_everywhere", AuditClass.BUSINESS)
                    .by(ActorRef.user(actorSubject, null))
                    .at(ResourceScope.brand(tenantId, brandId))
                    .target("Product", productId)
                    .because("Stopped in all branches (" + changed + " location offerings)")
                    .usingCapability(Capability.CATALOG_AUTHOR.code())
                    .changed(Map.of("locationOfferingsChanged", changed))
                    .correlatedBy(productId.toString())
                    .occurredAt(clock.instant())
                    .build());
        }
        return changed;
    }

    /**
     * Classifies many priceable nodes in one call — the fiscal workbench's
     * bulk fill, so ИКПУ and package code can be filled down a
     * {@code q-data-grid} column across hundreds of rows instead of one
     * variant at a time in the editor (ADR 0038's own coverage tooling,
     * catalog.md §4.1a).
     *
     * <p>Idempotent: {@link #classify} upserts, so calling this twice with
     * the same items leaves the same rows in the same state. One bad node id
     * in the batch does not fail the rest — it is reported {@link
     * BulkClassifyStatus#NOT_FOUND} and every other item is still applied,
     * the same "N independent outcomes, never one all-or-nothing" contract
     * {@code POST .../orders/bulk-actions} already uses.
     */
    @Transactional
    public List<BulkClassifyOutcome> bulkClassify(
            UUID tenantId, UUID brandId, List<BulkClassifyItem> items, @Nullable UUID actorId) {
        List<BulkClassifyOutcome> outcomes = new ArrayList<>(items.size());
        for (BulkClassifyItem item : items) {
            if (!store.priceableNodeExistsInBrand(tenantId, brandId, item.node())) {
                outcomes.add(new BulkClassifyOutcome(item.node(), BulkClassifyStatus.NOT_FOUND));
                continue;
            }
            FiscalClassification fiscal = item.fiscal();
            if (fiscal == null || fiscal.isEmpty()) {
                outcomes.add(new BulkClassifyOutcome(item.node(), BulkClassifyStatus.SKIPPED_EMPTY));
                continue;
            }
            classify(tenantId, brandId, item.node(), fiscal, actorId);
            outcomes.add(new BulkClassifyOutcome(item.node(), BulkClassifyStatus.CLASSIFIED));
        }
        return outcomes;
    }

    /** One item of a {@link #bulkClassify} batch: a target node and what to set it to. */
    public record BulkClassifyItem(
            PriceableNode node, @Nullable FiscalClassification fiscal) {}

    /** One node's outcome within a {@link #bulkClassify} batch. */
    public enum BulkClassifyStatus {
        CLASSIFIED,
        /** The classification carried no fields at all — nothing was written. */
        SKIPPED_EMPTY,
        /** The node id does not belong to this brand, or does not exist. */
        NOT_FOUND
    }

    public record BulkClassifyOutcome(PriceableNode node, BulkClassifyStatus status) {}

    /**
     * Records what a priceable node is, fiscally (ADR 0038).
     *
     * <p>An empty classification writes no row at all. The absence of a row is
     * how the coverage report reads "unclassified", and a row full of nulls would
     * be indistinguishable from one an operator started and abandoned — while
     * also making every newly created dish look like work in progress.
     *
     * <p>{@code MANUAL} is the only source this path writes. {@code IMPORT} and
     * {@code POS_SYNC} belong to ADR 0024 and ADR 0012 respectively and are
     * recorded by those importers, so that a coverage audit can tell a code a
     * human chose from one a machine carried in.
     */
    @Transactional
    public void classify(
            UUID tenantId,
            UUID brandId,
            PriceableNode node,
            @Nullable FiscalClassification fiscal,
            @Nullable UUID actorId) {
        if (fiscal == null || fiscal.isEmpty()) {
            return;
        }
        store.upsertFiscalClassification(tenantId, brandId, node, fiscal, "MANUAL", actorId);
    }

    /**
     * Classifies a brand's delivery charge (ADR 0038).
     *
     * <p>The fee node is created if the brand has none, because V0028 seeds one
     * per brand that existed when it ran and brand creation belongs to tenancy.
     * An operator classifying a fee should not have to know which side of a
     * migration their brand was created on.
     *
     * <p>The delivery fee must reach a receipt as an ordinary item line. Payme's
     * {@code shipping} block accepts a title and a price and carries no ИКПУ, no
     * package code and no VAT percent, so a fee sent through it arrives
     * unclassified and the payment still succeeds — which is exactly the failure
     * this classification exists to prevent.
     */
    @Transactional
    public UUID classifyFee(
            UUID tenantId, UUID brandId, String feeCode, FiscalClassification fiscal, @Nullable UUID actorId) {
        UUID feeId = store.ensureFee(tenantId, brandId, feeCode);
        classify(tenantId, brandId, PriceableNode.fee(feeId), fiscal, actorId);
        return feeId;
    }

    @Transactional
    public void attachModifierGroup(UUID tenantId, UUID brandId, UUID productId, UUID modifierGroupId, int sortOrder) {
        store.attachModifierGroupToProduct(tenantId, brandId, productId, modifierGroupId, sortOrder);
    }

    @Transactional
    public void attachMedia(
            UUID tenantId,
            UUID brandId,
            EntityType entityType,
            UUID entityId,
            MediaAssetId assetId,
            String role,
            int sortOrder) {
        store.attachMedia(tenantId, brandId, entityType, entityId, assetId.value(), role, sortOrder);
    }

    /**
     * {@link #attachMedia(UUID, UUID, EntityType, UUID, MediaAssetId, String, int)},
     * naming a channel to override for (IA 4.2f) rather than falling back to
     * {@link JdbcCatalogStore#ALL_CHANNELS}.
     */
    @Transactional
    public void attachMedia(
            UUID tenantId,
            UUID brandId,
            EntityType entityType,
            UUID entityId,
            MediaAssetId assetId,
            String role,
            int sortOrder,
            String channelCode) {
        store.attachMedia(tenantId, brandId, entityType, entityId, assetId.value(), role, sortOrder, channelCode);
    }

    /**
     * Detaches a media asset from a catalog entity — the undo {@link
     * #attachMedia} never had, so a wrong upload could not be corrected short
     * of leaving it attached.
     *
     * @return true when a relation actually existed and was removed
     */
    @Transactional
    public boolean detachMedia(
            UUID tenantId,
            UUID brandId,
            EntityType entityType,
            UUID entityId,
            MediaAssetId assetId,
            String role,
            String channelCode) {
        return store.detachMedia(tenantId, brandId, entityType, entityId, assetId.value(), role, channelCode);
    }

    /**
     * Sets whether one location sells one variant.
     *
     * <p>Deliberately outside the publication cycle. A kitchen marking a dish
     * sold out must take effect immediately, and forcing it through a republish
     * would mean the whole menu had to be re-validated to hide one item.
     */
    @Transactional
    public void setOffering(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID variantId,
            OfferingStatus status,
            List<String> fulfillmentModes) {
        store.upsertOffering(tenantId, brandId, locationId, variantId, status, String.join(",", fulfillmentModes));
    }

    /**
     * Offers a variant at a location only if nothing already says otherwise.
     *
     * <p>{@link #setOffering}'s create-only sibling, for a machine that runs
     * again: the ADR 0099 sample-menu installer re-asserts its offerings on
     * every retry and on every later run for the same tenant, and last-write-wins
     * there would silently undo an operator who had taken a sample dish off. An
     * existing row — {@code AVAILABLE}, {@code UNAVAILABLE} or {@code HIDDEN},
     * and whatever fulfilment modes it was narrowed to — is somebody's decision
     * and is left alone. The console's own toggle keeps {@link #setOffering},
     * which genuinely wants last-write-wins.
     *
     * @return whether this call created the offering
     */
    @Transactional
    public boolean offerIfAbsent(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID variantId,
            OfferingStatus status,
            List<String> fulfillmentModes) {
        return store.insertOfferingIfAbsent(
                tenantId, brandId, locationId, variantId, status, String.join(",", fulfillmentModes));
    }

    /**
     * The same offering toggle as {@link #setOffering}, plus the ADR 0027
     * audit fact it never wrote: whether a location sells a variant at all
     * is a menu-structure decision, distinct from the ADR 0060 stop-list
     * toggle proper ({@code InventoryService#setAvailabilityAudited}, "a
     * kitchen marking a dish sold out, or back on") but the same kind of
     * unaudited availability mutation, closed here for the same reason.
     * {@code CatalogAuthoringController} is this overload's only caller
     * today.
     *
     * <p>The other overload keeps its own signature and its own thirty-odd
     * fixture call sites unaudited on purpose: most are catalog authoring
     * scaffolding that predates any actor at all, and widening every one of
     * them to carry an actor for a mutation none of them means to audit would
     * be noise, not coverage.
     */
    @Transactional
    public void setOffering(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID variantId,
            OfferingStatus status,
            List<String> fulfillmentModes,
            String actorSubject) {
        store.upsertOffering(tenantId, brandId, locationId, variantId, status, String.join(",", fulfillmentModes));

        audit.record(AuditFact.of("catalog.offering.set", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("LocationOffering", variantId)
                .because("Set variant availability to " + status)
                .usingCapability(Capability.OFFERING_MANAGE.code())
                .changed(Map.of("status", status.name(), "fulfillmentModes", fulfillmentModes))
                .correlatedBy(variantId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * The 86 screen's read (catalog.md §4.6): one location's sellable variants,
     * joined with whether each can be sold right now. Also the New order
     * screen's item search (orders.md §5.5, wave P13): {@code query} narrows by
     * product name so the picker can search a catalog of thousands rather than
     * paging through it; {@code null} keeps every existing caller's
     * unfiltered behaviour.
     *
     * <p>Read-only and, unlike every other method here, not scoped to a draft —
     * {@code location_offerings} and {@code inventory.positions} both take
     * effect immediately without a publication, which is the design rule
     * catalog.md §0 states and this query reads rather than restates.
     */
    @Transactional(readOnly = true)
    public List<JdbcCatalogStore.VariantAvailabilityRow> variantsAtLocation(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String locale,
            @Nullable String query,
            @Nullable UUID cursor,
            int limit) {
        return store.variantsAtLocation(tenantId, brandId, locationId, locale, query, cursor, limit);
    }

    /**
     * The Layer A menu matrix's own read (catalog.md §4.5): the same query as
     * the four-argument overload above, widened with an optional search and an
     * optional offering-status filter — {@code NOT_ADDED} answers "what is
     * missing from this branch's menu", the question the narrower overload
     * cannot answer at all.
     */
    @Transactional(readOnly = true)
    public List<JdbcCatalogStore.VariantAvailabilityRow> variantsAtLocation(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String locale,
            @Nullable UUID cursor,
            int limit,
            @Nullable String search,
            @Nullable String offeringStatusFilter) {
        return store.variantsAtLocation(
                tenantId, brandId, locationId, locale, cursor, limit, search, offeringStatusFilter);
    }

    /** The stop list's own tab badges (gap map row 2.5) — see {@link JdbcCatalogStore#variantAvailabilityCounts}. */
    @Transactional(readOnly = true)
    public JdbcCatalogStore.VariantAvailabilityCountsRow variantAvailabilityCounts(
            UUID tenantId, UUID brandId, UUID locationId, String locale, @Nullable String search) {
        return store.variantAvailabilityCounts(tenantId, brandId, locationId, locale, search);
    }

    /**
     * Sets one entity's name and description in one locale.
     *
     * <p>{@code entityId} arrives from the caller and {@code catalog.translations}
     * carries no foreign key on it — it is polymorphic across six tables, so there
     * is nothing for one to reference. Every other cross-tenant reference on this
     * platform is caught by the database eventually; this one never would be, and
     * before V0077 the consequence was not a dangling pointer but a rewrite:
     * passing another tenant's product id made the upsert collide on a key that
     * did not name a tenant, and the DO UPDATE branch replaced that tenant's live
     * menu text while leaving their {@code tenant_id} on the row.
     *
     * <p>V0077 put {@code tenant_id} in the key, which ends the overwrite. It
     * cannot end the rest: without this resolution a tenant could still write a
     * translation of its own against somebody else's entity id, and the fact that
     * the write succeeded would tell it the id was real. So the entity is resolved
     * in the caller's own tenant and brand first, and the refusal says only that
     * the entity is unknown here — one answer for "not yours" and "does not
     * exist", because a caller able to tell them apart has an existence oracle for
     * catalog ids. Same shape as the courier evidence path after V0069.
     */
    @Transactional
    public void translate(
            UUID tenantId,
            UUID brandId,
            EntityType entityType,
            UUID entityId,
            String locale,
            String name,
            @Nullable String description) {
        if (!store.entityExistsInBrand(tenantId, brandId, entityType, entityId)) {
            throw new UnknownCatalogEntityException(entityType, entityId);
        }
        store.upsertTranslation(tenantId, brandId, entityType, entityId, locale, name, description);
    }

    /**
     * A translation was asked for against an entity this brand does not have.
     *
     * <p>The message names the entity type and the id the caller already sent, and
     * nothing else. It must stay that way: an exception that distinguished "exists
     * elsewhere" from "does not exist" would answer, for any uuid a caller cares
     * to submit, whether it is a real catalog id somewhere on the platform.
     */
    public static class UnknownCatalogEntityException extends RuntimeException {

        private final transient EntityType entityType;
        private final transient UUID entityId;

        public UnknownCatalogEntityException(EntityType entityType, UUID entityId) {
            super("No %s %s in this brand".formatted(entityType, entityId));
            this.entityType = entityType;
            this.entityId = entityId;
        }

        public EntityType entityType() {
            return entityType;
        }

        public UUID entityId() {
            return entityId;
        }
    }

    /**
     * A category update was refused because it would make the category its
     * own ancestor. Carries {@code CatalogValidator}'s own finding code,
     * {@code CATEGORY_TREE_HAS_CYCLE}, so the console can render the exact
     * copy an operator already sees on a blocked publication, rather than a
     * second message for the same fact.
     */
    public static class CategoryTreeCycleException extends RuntimeException {

        /** {@link uz.horecaos.platform.catalog.application.CatalogValidator}'s own finding code. */
        public static final String FINDING_CODE = "CATEGORY_TREE_HAS_CYCLE";

        private final transient UUID categoryId;
        private final transient UUID proposedParentId;

        public CategoryTreeCycleException(UUID categoryId, UUID proposedParentId, java.util.Set<UUID> walked) {
            super("Reparenting %s under %s would form a cycle: %s".formatted(categoryId, proposedParentId, walked));
            this.categoryId = categoryId;
            this.proposedParentId = proposedParentId;
        }

        public UUID categoryId() {
            return categoryId;
        }

        public UUID proposedParentId() {
            return proposedParentId;
        }
    }

    public record ProductCreated(UUID productId, UUID defaultVariantId) {}

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
}
