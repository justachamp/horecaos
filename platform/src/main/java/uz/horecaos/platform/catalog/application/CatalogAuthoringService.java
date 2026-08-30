package uz.horecaos.platform.catalog.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierOption;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PriceableNode;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.media.api.MediaAssetId;

/**
 * Draft authoring (ADR 0016).
 *
 * <p>Nothing here reaches a customer. Every write lands in the authoring tables,
 * and a menu only changes when {@link CatalogPublicationService} takes a snapshot
 * — which is what lets an operator edit a live brand's catalog in the middle of
 * service without anything changing under the customers currently ordering.
 */
@Service
public class CatalogAuthoringService {

    private final JdbcCatalogStore store;

    public CatalogAuthoringService(JdbcCatalogStore store) {
        this.store = store;
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
            String description,
            String locale,
            String sku,
            String unitCode,
            FiscalClassification fiscal,
            UUID actorId) {

        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        store.insertProduct(productId, tenantId, brandId, code, Status.ACTIVE);
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
            String sku,
            String unitCode,
            String name,
            String locale,
            int sortOrder,
            FiscalClassification fiscal,
            UUID actorId) {
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

    @Transactional
    public UUID createCategory(
            UUID tenantId,
            UUID brandId,
            UUID catalogId,
            UUID parentCategoryId,
            String code,
            String name,
            String locale,
            int sortOrder) {
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
            UUID linkedVariantId,
            int maximumQuantity,
            int sortOrder,
            FiscalClassification fiscal,
            UUID actorId) {
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
    public void classify(UUID tenantId, UUID brandId, PriceableNode node, FiscalClassification fiscal, UUID actorId) {
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
    public UUID classifyFee(UUID tenantId, UUID brandId, String feeCode, FiscalClassification fiscal, UUID actorId) {
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
     * The 86 screen's read (catalog.md §4.6): one location's sellable variants,
     * joined with whether each can be sold right now.
     *
     * <p>Read-only and, unlike every other method here, not scoped to a draft —
     * {@code location_offerings} and {@code inventory.positions} both take
     * effect immediately without a publication, which is the design rule
     * catalog.md §0 states and this query reads rather than restates.
     */
    @Transactional(readOnly = true)
    public List<JdbcCatalogStore.VariantAvailabilityRow> variantsAtLocation(
            UUID tenantId, UUID brandId, UUID locationId, String locale, UUID cursor, int limit) {
        return store.variantsAtLocation(tenantId, brandId, locationId, locale, cursor, limit);
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
            String description) {
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

    public record ProductCreated(UUID productId, UUID defaultVariantId) {}
}
