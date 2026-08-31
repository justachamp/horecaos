package uz.horecaos.platform.catalog.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Category;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Fee;
import uz.horecaos.platform.catalog.domain.CatalogEntities.LocationOffering;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierOption;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PriceableNode;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PriceableType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Product;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PublicationItem;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.FiscalClassification.MarkingScheme;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.catalog.domain.ValidationFinding;

/**
 * Catalog persistence (ADR 0016).
 *
 * <p>Every read is filtered by tenant and brand in the query rather than checked
 * after loading, so there is no code path that materialises another brand's row
 * at all.
 */
@Repository
public class JdbcCatalogStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcCatalogStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- authoring

    public void insertCatalog(UUID id, UUID tenantId, UUID brandId, String code, String name) {
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, :code, :name, 'DRAFT')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .param("name", name)
                .update();
    }

    public void insertProduct(UUID id, UUID tenantId, UUID brandId, String code, Status status) {
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, :status)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .param("status", status.name())
                .update();
    }

    public void insertVariant(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID productId,
            @Nullable String sku,
            String unitCode,
            boolean isDefault,
            int sortOrder,
            Status status) {
        jdbc.sql("""
                INSERT INTO catalog.variants (
                    id, tenant_id, brand_id, product_id, sku, unit_code,
                    is_default, sort_order, status)
                VALUES (:id, :tenantId, :brandId, :productId, :sku, :unitCode,
                    :isDefault, :sortOrder, :status)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .param("sku", sku)
                .param("unitCode", unitCode)
                .param("isDefault", isDefault)
                .param("sortOrder", sortOrder)
                .param("status", status.name())
                .update();
    }

    public void insertCategory(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID catalogId,
            @Nullable UUID parentCategoryId,
            String code,
            int sortOrder,
            Status status) {
        jdbc.sql("""
                INSERT INTO catalog.categories (
                    id, tenant_id, brand_id, catalog_id, parent_category_id, code, sort_order, status)
                VALUES (:id, :tenantId, :brandId, :catalogId, :parentId, :code, :sortOrder, :status)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .param("parentId", parentCategoryId)
                .param("code", code)
                .param("sortOrder", sortOrder)
                .param("status", status.name())
                .update();
    }

    public void insertModifierGroup(ModifierGroup group) {
        jdbc.sql("""
                INSERT INTO catalog.modifier_groups (
                    id, tenant_id, brand_id, code, is_required, minimum_selections,
                    maximum_selections, allow_same_option_multiple_times, sort_order, status)
                VALUES (:id, :tenantId, :brandId, :code, :required, :minimum,
                    :maximum, :allowRepeat, :sortOrder, :status)
                """)
                .param("id", group.id())
                .param("tenantId", group.tenantId())
                .param("brandId", group.brandId())
                .param("code", group.code())
                .param("required", group.required())
                .param("minimum", group.minimumSelections())
                .param("maximum", group.maximumSelections())
                .param("allowRepeat", group.allowSameOptionMultipleTimes())
                .param("sortOrder", group.sortOrder())
                .param("status", group.status().name())
                .update();
    }

    public void insertModifierOption(ModifierOption option) {
        jdbc.sql("""
                INSERT INTO catalog.modifier_options (
                    id, tenant_id, brand_id, modifier_group_id, code, linked_variant_id,
                    maximum_quantity, sort_order, status)
                VALUES (:id, :tenantId, :brandId, :groupId, :code, :linkedVariantId,
                    :maximumQuantity, :sortOrder, :status)
                """)
                .param("id", option.id())
                .param("tenantId", option.tenantId())
                .param("brandId", option.brandId())
                .param("groupId", option.modifierGroupId())
                .param("code", option.code())
                .param("linkedVariantId", option.linkedVariantId())
                .param("maximumQuantity", option.maximumQuantity())
                .param("sortOrder", option.sortOrder())
                .param("status", option.status().name())
                .update();
    }

    public void addProductToCatalog(UUID tenantId, UUID brandId, UUID catalogId, UUID productId, int sortOrder) {
        jdbc.sql("""
                INSERT INTO catalog.catalog_products (tenant_id, brand_id, catalog_id, product_id, sort_order)
                VALUES (:tenantId, :brandId, :catalogId, :productId, :sortOrder)
                ON CONFLICT (catalog_id, product_id) DO UPDATE SET sort_order = EXCLUDED.sort_order
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .param("productId", productId)
                .param("sortOrder", sortOrder)
                .update();
    }

    public void addProductToCategory(UUID tenantId, UUID brandId, UUID categoryId, UUID productId, int sortOrder) {
        jdbc.sql("""
                INSERT INTO catalog.category_products (tenant_id, brand_id, category_id, product_id, sort_order)
                VALUES (:tenantId, :brandId, :categoryId, :productId, :sortOrder)
                ON CONFLICT (category_id, product_id) DO UPDATE SET sort_order = EXCLUDED.sort_order
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("categoryId", categoryId)
                .param("productId", productId)
                .param("sortOrder", sortOrder)
                .update();
    }

    public void attachModifierGroupToProduct(
            UUID tenantId, UUID brandId, UUID productId, UUID modifierGroupId, int sortOrder) {
        jdbc.sql("""
                INSERT INTO catalog.product_modifier_groups (
                    tenant_id, brand_id, product_id, modifier_group_id, sort_order)
                VALUES (:tenantId, :brandId, :productId, :groupId, :sortOrder)
                ON CONFLICT (product_id, modifier_group_id) DO UPDATE SET sort_order = EXCLUDED.sort_order
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .param("groupId", modifierGroupId)
                .param("sortOrder", sortOrder)
                .update();
    }

    /**
     * Whether this entity exists in this tenant and brand.
     *
     * <p>{@code catalog.translations.entity_id} is polymorphic across six tables
     * and so can carry no foreign key. Nothing in the database can be asked where
     * an entity id came from, which makes this query the only thing standing
     * between a caller-supplied uuid and a row written against it. It answers one
     * boolean, and the caller turns a {@code false} into a refusal that does not
     * say which of "not yours" and "does not exist" it was.
     *
     * <p>The table is chosen by a switch over the enum rather than interpolated
     * from a string, so there is no path from a caller's value to a table name.
     */
    public boolean entityExistsInBrand(UUID tenantId, UUID brandId, EntityType entityType, UUID entityId) {
        String sql =
                switch (entityType) {
                    case CATALOG -> "SELECT 1 FROM catalog.catalogs";
                    case CATEGORY -> "SELECT 1 FROM catalog.categories";
                    case PRODUCT -> "SELECT 1 FROM catalog.products";
                    case VARIANT -> "SELECT 1 FROM catalog.variants";
                    case MODIFIER_GROUP -> "SELECT 1 FROM catalog.modifier_groups";
                    case MODIFIER_OPTION -> "SELECT 1 FROM catalog.modifier_options";
                    // ADR 0038: a fee reaches a receipt as a line without being a catalog
                    // item. It has no row anywhere, and no translations, so there is
                    // nothing to resolve and nothing that may be written against it.
                    case FEE -> null;
                };
        if (sql == null) {
            return false;
        }
        return jdbc.sql(sql + " WHERE id = :entityId AND tenant_id = :tenantId AND brand_id = :brandId")
                .param("entityId", entityId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * The localised name and description of one catalog entity.
     *
     * <p>The conflict target names {@code tenant_id} because the primary key does,
     * since V0077. Before that it was {@code (entity_type, entity_id, locale)} and
     * so was the key: an upsert from one tenant carrying another tenant's entity
     * id found the other tenant's row, took the DO UPDATE branch, and replaced the
     * name and description a different tenant's customers were reading — leaving
     * {@code tenant_id} untouched, so the row still looked like the victim's
     * afterwards. There is no foreign key on {@code entity_id} to have caught it
     * and no dangling pointer to find later.
     *
     * <p>Both halves are needed. This key stops the overwrite; only
     * {@code CatalogAuthoringService.translate} stops a tenant writing a row of
     * its own against somebody else's entity id, because no key can.
     */
    public void upsertTranslation(
            UUID tenantId,
            UUID brandId,
            EntityType entityType,
            UUID entityId,
            String locale,
            String name,
            @Nullable String description) {
        jdbc.sql("""
                INSERT INTO catalog.translations (
                    tenant_id, brand_id, entity_type, entity_id, locale, name, description)
                VALUES (:tenantId, :brandId, :entityType, :entityId, :locale, :name, :description)
                ON CONFLICT (tenant_id, entity_type, entity_id, locale) DO UPDATE
                SET name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    version = catalog.translations.version + 1,
                    updated_at = now()
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("entityType", entityType.name())
                .param("entityId", entityId)
                .param("locale", locale)
                .param("name", name)
                .param("description", description)
                .update();
    }

    public void attachMedia(
            UUID tenantId,
            UUID brandId,
            EntityType entityType,
            UUID entityId,
            UUID mediaAssetId,
            String role,
            int sortOrder) {
        jdbc.sql("""
                INSERT INTO catalog.media_relations (
                    tenant_id, brand_id, entity_type, entity_id, media_asset_id, role, sort_order)
                VALUES (:tenantId, :brandId, :entityType, :entityId, :assetId, :role, :sortOrder)
                ON CONFLICT (entity_type, entity_id, media_asset_id, role)
                DO UPDATE SET sort_order = EXCLUDED.sort_order
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("entityType", entityType.name())
                .param("entityId", entityId)
                .param("assetId", mediaAssetId)
                .param("role", role)
                .param("sortOrder", sortOrder)
                .update();
    }

    /**
     * Sets whether one location sells one variant.
     *
     * <p>An upsert because this is toggled constantly during service — a kitchen
     * running out of a dish should not need to know whether a row already exists.
     */
    public void upsertOffering(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID variantId,
            OfferingStatus status,
            String fulfillmentModes) {
        jdbc.sql("""
                INSERT INTO catalog.location_offerings (
                    id, tenant_id, brand_id, location_id, variant_id, status, fulfillment_modes)
                VALUES (:id, :tenantId, :brandId, :locationId, :variantId, :status, :modes)
                ON CONFLICT (location_id, variant_id) DO UPDATE
                SET status = EXCLUDED.status,
                    fulfillment_modes = EXCLUDED.fulfillment_modes,
                    version = catalog.location_offerings.version + 1,
                    updated_at = now()
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("variantId", variantId)
                .param("status", status.name())
                .param("modes", fulfillmentModes)
                .update();
    }

    // --------------------------------------------------- fiscal classification

    /**
     * Records what a priceable node is, fiscally (ADR 0038).
     *
     * <p>An upsert on the node rather than an insert, because classifying is a
     * correction as often as it is a first entry: an operator fixing a wrong
     * ИКПУ, or filling in the package code they did not have on Monday, must not
     * create a second row. The unique index on the derived
     * {@code (priceable_type, priceable_id)} pair is what makes that a conflict
     * rather than a duplicate — two classifications for one node would make the
     * code on a receipt depend on row order.
     *
     * <p>Exactly one of the three node columns is populated and the other two are
     * explicitly null, which is why the parameters go in through a map: mixing
     * {@code .param(...)} with a null value is fine, but building the row by
     * three branches of an if is where the third branch gets forgotten.
     */
    public void upsertFiscalClassification(
            UUID tenantId,
            UUID brandId,
            PriceableNode node,
            FiscalClassification fiscal,
            String source,
            @Nullable UUID actorId) {

        Map<String, Object> params = new HashMap<>();
        params.put("id", UUID.randomUUID());
        params.put("tenantId", tenantId);
        params.put("brandId", brandId);
        params.put("variantId", node.type() == PriceableType.VARIANT ? node.id() : null);
        params.put("modifierOptionId", node.type() == PriceableType.MODIFIER_OPTION ? node.id() : null);
        params.put("feeId", node.type() == PriceableType.FEE ? node.id() : null);
        params.put("mxikCode", fiscal.mxikCode());
        params.put("packageCode", fiscal.packageCode());
        params.put("fiscalUnitCode", fiscal.fiscalUnitCode());
        params.put("fiscalName", fiscal.fiscalName());
        params.put("barcode", fiscal.barcode());
        params.put("markingRequired", fiscal.markingRequired());
        params.put("markingScheme", fiscal.markingScheme().name());
        params.put("excisable", fiscal.excisable());
        params.put("alcoholByVolumeBp", fiscal.alcoholByVolumeBasisPoints());
        params.put("ageRestrictionYears", fiscal.ageRestrictionYears());
        params.put("source", source);
        params.put("classifiedBy", actorId);

        jdbc.sql("""
                INSERT INTO catalog.fiscal_classifications (
                    id, tenant_id, brand_id, variant_id, modifier_option_id, fee_id,
                    mxik_code, package_code, fiscal_unit_code, fiscal_name, barcode,
                    marking_required, marking_scheme, excisable, alcohol_by_volume_bp,
                    age_restriction_years, source, classified_by)
                VALUES (
                    :id, :tenantId, :brandId, :variantId, :modifierOptionId, :feeId,
                    :mxikCode, :packageCode, :fiscalUnitCode, :fiscalName, :barcode,
                    :markingRequired, :markingScheme, :excisable, :alcoholByVolumeBp,
                    :ageRestrictionYears, :source, :classifiedBy)
                ON CONFLICT (priceable_type, priceable_id) DO UPDATE SET
                    mxik_code = EXCLUDED.mxik_code,
                    package_code = EXCLUDED.package_code,
                    fiscal_unit_code = EXCLUDED.fiscal_unit_code,
                    fiscal_name = EXCLUDED.fiscal_name,
                    barcode = EXCLUDED.barcode,
                    marking_required = EXCLUDED.marking_required,
                    marking_scheme = EXCLUDED.marking_scheme,
                    excisable = EXCLUDED.excisable,
                    alcohol_by_volume_bp = EXCLUDED.alcohol_by_volume_bp,
                    age_restriction_years = EXCLUDED.age_restriction_years,
                    source = EXCLUDED.source,
                    classified_by = EXCLUDED.classified_by,
                    classified_at = now(),
                    version = catalog.fiscal_classifications.version + 1,
                    updated_at = now()
                """).params(params).update();
    }

    /** Every classified node in one brand, keyed by the node it classifies. */
    public Map<UUID, FiscalClassification> classificationsForBrand(UUID tenantId, UUID brandId) {
        Map<UUID, FiscalClassification> byNode = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT priceable_id, mxik_code, package_code, fiscal_unit_code, fiscal_name,
                       barcode, marking_required, marking_scheme, excisable,
                       alcohol_by_volume_bp, age_restriction_years
                FROM catalog.fiscal_classifications
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> Map.entry(row.getObject("priceable_id", UUID.class), mapClassification(row)))
                .list()
                .forEach(entry -> byNode.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(byNode);
    }

    /**
     * The nodes in this set that carry a marking requirement (ADR 0038).
     *
     * <p>Read on the checkout path to decide which payment methods a cart may be
     * offered, so it asks the narrowest question it can: the partial index on
     * {@code marking_required} holds no rows at all until a tenant publishes a
     * marked SKU, and this query never touches the classified-but-unmarked bulk
     * of the table.
     */
    public Set<UUID> markedNodes(UUID tenantId, UUID brandId, Set<UUID> priceableIds) {
        if (priceableIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("""
                SELECT priceable_id FROM catalog.fiscal_classifications
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND marking_required
                  AND priceable_id = ANY(:ids)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", priceableIds.toArray(UUID[]::new))
                .query(UUID.class)
                .list());
    }

    /** A brand's non-catalogue charge lines — today only the delivery fee. */
    public List<Fee> feesForBrand(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, code, status, version
                FROM catalog.fees
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                ORDER BY code
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> new Fee(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getString("code"),
                        Status.valueOf(row.getString("status")),
                        row.getInt("version")))
                .list();
    }

    /**
     * The identifier of one of a brand's fee nodes, creating it if the brand has
     * none.
     *
     * <p>V0028 seeds a delivery fee for every brand that existed when it ran, and
     * brand creation belongs to tenancy rather than here, so a brand created
     * afterwards would otherwise have nothing to classify. Creating it on demand
     * keeps the gap from being a support call; the insert is a no-op when the row
     * is already there.
     */
    public UUID ensureFee(UUID tenantId, UUID brandId, String code) {
        jdbc.sql("""
                INSERT INTO catalog.fees (id, tenant_id, brand_id, code)
                VALUES (:id, :tenantId, :brandId, :code)
                ON CONFLICT (tenant_id, brand_id, code) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .update();

        return jdbc.sql("""
                SELECT id FROM catalog.fees
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND code = :code
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .query(UUID.class)
                .single();
    }

    /**
     * The subset of {@code codes} the imported ИКПУ/MXIK reference recognises.
     *
     * <p>Returns empty when the reference table is empty, which the caller must
     * treat as "the import has not run" rather than as "every code is wrong".
     * The two are indistinguishable from the result alone, which is why
     * {@link #mxikReferenceIsLoaded()} exists separately.
     */
    public Set<String> knownMxikCodes(Set<String> codes) {
        if (codes.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("""
                SELECT code FROM catalog.mxik_reference WHERE code = ANY(:codes)
                """)
                .param("codes", codes.toArray(String[]::new))
                .query(String.class)
                .list());
    }

    /** Whether the official list has been imported at all. */
    public boolean mxikReferenceIsLoaded() {
        return jdbc.sql("SELECT count(*) FROM catalog.mxik_reference")
                        .query(Long.class)
                        .single()
                > 0;
    }

    // ------------------------------------------------------------------- reads

    /** Whether this catalog exists under this exact tenant and brand. */
    public boolean catalogBelongsTo(UUID tenantId, UUID brandId, UUID catalogId) {
        return jdbc.sql("""
                SELECT count(*) FROM catalog.catalogs
                WHERE id = :catalogId AND tenant_id = :tenantId AND brand_id = :brandId
                """)
                        .param("catalogId", catalogId)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    public List<Product> productsInCatalog(UUID tenantId, UUID brandId, UUID catalogId) {
        return jdbc.sql("""
                SELECT p.* FROM catalog.products p
                JOIN catalog.catalog_products cp ON cp.product_id = p.id
                WHERE p.tenant_id = :tenantId AND p.brand_id = :brandId AND cp.catalog_id = :catalogId
                ORDER BY cp.sort_order, p.code
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .query(JdbcCatalogStore::mapProduct)
                .list();
    }

    public List<Variant> variantsInCatalog(UUID tenantId, UUID brandId, UUID catalogId) {
        return jdbc.sql("""
                SELECT v.* FROM catalog.variants v
                JOIN catalog.catalog_products cp ON cp.product_id = v.product_id
                WHERE v.tenant_id = :tenantId AND v.brand_id = :brandId AND cp.catalog_id = :catalogId
                ORDER BY v.sort_order
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .query(JdbcCatalogStore::mapVariant)
                .list();
    }

    public List<Category> categoriesInCatalog(UUID tenantId, UUID brandId, UUID catalogId) {
        return jdbc.sql("""
                SELECT * FROM catalog.categories
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND catalog_id = :catalogId
                ORDER BY sort_order, code
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .query(JdbcCatalogStore::mapCategory)
                .list();
    }

    /**
     * The sellable variants of one location, joined with their current
     * availability (catalog.md §4.6) — the read side of {@code
     * InventoryController.setAvailability} / {@code
     * CatalogAuthoringController.setOffering}.
     *
     * <p>"Sellable" means offered here: an active variant of an active product
     * with an {@code AVAILABLE} {@code location_offerings} row at this
     * location. Availability is read the same way {@code
     * InventoryService.checkAvailability} decides it, mirrored rather than
     * called through the port because this is one join, not a per-variant
     * lookup: a variant with no {@code inventory.stock_items} row is
     * unavailable (an unlisted variant is never orderable, ADR 0017), {@code
     * UNTRACKED} is always available, and {@code BINARY} follows {@code
     * positions.binary_available}. {@code QUANTITY} is accepted by the schema
     * and refused by the inventory service; a row in that mode comes back
     * {@code available = false} with its tracking mode named, so the caller can
     * render catalog.md's read-only "Количественный учёт пока не
     * поддерживается" state instead of a control that would 409.
     *
     * <p>Keyset on {@code v.id}, the same documented shortcut {@code
     * MigrationProgramController#listScopes} uses: ADR 0031 asks for a signed
     * cursor and the platform has no {@code CursorSigner} bean yet, so the
     * cursor is the last variant id of the previous page.
     *
     * <p>A product may sit in more than one category; the lateral join below
     * picks exactly one, by the same {@code sort_order} tiebreak {@link
     * #productIdsByCategory} uses elsewhere, so the join fans out to one row per
     * variant rather than one per (variant, category) pair.
     */
    public List<VariantAvailabilityRow> variantsAtLocation(
            UUID tenantId, UUID brandId, UUID locationId, String locale, @Nullable UUID cursorVariantId, int limit) {
        return jdbc.sql("""
                SELECT v.id AS variant_id,
                       t.name AS product_name,
                       ct.name AS category_name,
                       si.tracking_mode AS tracking_mode,
                       pos.binary_available AS binary_available
                FROM catalog.variants v
                JOIN catalog.products p
                    ON p.id = v.product_id AND p.tenant_id = v.tenant_id AND p.brand_id = v.brand_id
                JOIN catalog.location_offerings lo
                    ON lo.variant_id = v.id AND lo.tenant_id = v.tenant_id AND lo.brand_id = v.brand_id
                       AND lo.location_id = :locationId AND lo.status = 'AVAILABLE'
                LEFT JOIN catalog.translations t
                    ON t.entity_type = 'PRODUCT' AND t.entity_id = p.id AND t.tenant_id = p.tenant_id
                       AND t.brand_id = p.brand_id AND t.locale = :locale
                LEFT JOIN LATERAL (
                    SELECT c.id, c.tenant_id
                    FROM catalog.category_products cp
                    JOIN catalog.categories c
                        ON c.id = cp.category_id AND c.tenant_id = cp.tenant_id AND c.brand_id = cp.brand_id
                    WHERE cp.product_id = p.id AND cp.tenant_id = p.tenant_id AND cp.brand_id = p.brand_id
                    ORDER BY cp.sort_order, c.id
                    LIMIT 1
                ) first_category ON true
                LEFT JOIN catalog.translations ct
                    ON ct.entity_type = 'CATEGORY' AND ct.entity_id = first_category.id
                       AND ct.tenant_id = first_category.tenant_id AND ct.locale = :locale
                LEFT JOIN inventory.stock_items si
                    ON si.variant_id = v.id AND si.tenant_id = v.tenant_id AND si.location_id = :locationId
                LEFT JOIN inventory.positions pos
                    ON pos.stock_item_id = si.id AND pos.tenant_id = si.tenant_id
                WHERE v.tenant_id = :tenantId AND v.brand_id = :brandId
                  AND v.status = 'ACTIVE' AND p.status = 'ACTIVE'
                  AND (CAST(:cursor AS uuid) IS NULL OR v.id > CAST(:cursor AS uuid))
                ORDER BY v.id
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("locale", locale)
                .param("cursor", cursorVariantId)
                .param("limit", limit)
                .query((row, number) -> {
                    String trackingMode = row.getString("tracking_mode");
                    boolean available;
                    if (trackingMode == null) {
                        // No stock item at all: unlisted, and unlisted is
                        // unavailable (InventoryService.checkAvailability).
                        available = false;
                    } else if ("UNTRACKED".equals(trackingMode)) {
                        available = true;
                    } else if ("BINARY".equals(trackingMode)) {
                        available = Boolean.TRUE.equals(row.getObject("binary_available", Boolean.class));
                    } else {
                        // QUANTITY: accepted by the schema, refused by the
                        // service. Never orderable in this release.
                        available = false;
                    }
                    return new VariantAvailabilityRow(
                            row.getObject("variant_id", UUID.class),
                            row.getString("product_name"),
                            row.getString("category_name"),
                            available,
                            trackingMode);
                })
                .list();
    }

    /**
     * Modifier groups reachable from this catalog's products.
     *
     * <p>Scoped to the catalog rather than the brand: a group attached to no
     * product in this menu is not part of this menu, and validating it would
     * block a publication over something the customer will never see.
     */
    public List<ModifierGroup> modifierGroupsInCatalog(UUID tenantId, UUID brandId, UUID catalogId) {
        return jdbc.sql("""
                SELECT DISTINCT mg.* FROM catalog.modifier_groups mg
                JOIN catalog.product_modifier_groups pmg ON pmg.modifier_group_id = mg.id
                JOIN catalog.catalog_products cp ON cp.product_id = pmg.product_id
                WHERE mg.tenant_id = :tenantId AND mg.brand_id = :brandId AND cp.catalog_id = :catalogId
                ORDER BY mg.sort_order, mg.code
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .query(JdbcCatalogStore::mapModifierGroup)
                .list();
    }

    /**
     * Which products sit in which category, for the products this catalog
     * actually carries.
     *
     * <p>Both sides are constrained to the catalog. A category row is
     * catalog-scoped already, but {@code category_products} is not: it keys on
     * (category, product) alone, so a product that was removed from this
     * catalog and left in the category would otherwise be published into a menu
     * that does not carry it -- a name the customer can tap and nothing behind
     * it.
     */
    public Map<UUID, List<UUID>> productIdsByCategory(UUID tenantId, UUID brandId, UUID catalogId) {
        return membership(jdbc.sql("""
                SELECT cp.category_id AS parent_id, cp.product_id AS child_id
                FROM catalog.category_products cp
                JOIN catalog.categories c
                  ON c.id = cp.category_id AND c.tenant_id = cp.tenant_id
                 AND c.brand_id = cp.brand_id
                JOIN catalog.catalog_products link
                  ON link.product_id = cp.product_id
                WHERE cp.tenant_id = :tenantId AND cp.brand_id = :brandId
                  AND c.catalog_id = :catalogId AND link.catalog_id = :catalogId
                ORDER BY cp.sort_order
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId));
    }

    /**
     * Which modifier groups a product offers.
     *
     * <p>Product-level only. {@code variant_modifier_groups} exists in V0016 and
     * nothing writes it, so a variant-level read here would return an empty map
     * on every catalog and quietly suggest the feature works.
     */
    public Map<UUID, List<UUID>> modifierGroupIdsByProduct(UUID tenantId, UUID brandId, UUID catalogId) {
        return membership(jdbc.sql("""
                SELECT pmg.product_id AS parent_id, pmg.modifier_group_id AS child_id
                FROM catalog.product_modifier_groups pmg
                JOIN catalog.catalog_products link
                  ON link.product_id = pmg.product_id
                JOIN catalog.modifier_groups mg
                  ON mg.id = pmg.modifier_group_id AND mg.tenant_id = pmg.tenant_id
                 AND mg.brand_id = pmg.brand_id
                WHERE pmg.tenant_id = :tenantId AND pmg.brand_id = :brandId
                  AND link.catalog_id = :catalogId
                ORDER BY pmg.sort_order
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId));
    }

    /**
     * Collects a two-column parent/child read into a map, keeping the order the
     * query established.
     */
    private static Map<UUID, List<UUID>> membership(JdbcClient.StatementSpec spec) {
        Map<UUID, List<UUID>> byParent = new LinkedHashMap<>();
        spec.query((row, number) ->
                        Map.entry(row.getObject("parent_id", UUID.class), row.getObject("child_id", UUID.class)))
                .list()
                .forEach(entry -> byParent.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));
        return Map.copyOf(byParent);
    }

    public List<ModifierOption> optionsForGroups(UUID tenantId, UUID brandId, List<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT * FROM catalog.modifier_options
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND modifier_group_id = ANY(:groupIds)
                ORDER BY sort_order, code
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("groupIds", groupIds.toArray(UUID[]::new))
                .query(JdbcCatalogStore::mapModifierOption)
                .list();
    }

    public List<TranslationRow> translations(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT entity_type, entity_id, locale, name, description
                FROM catalog.translations
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> new TranslationRow(
                        EntityType.valueOf(row.getString("entity_type")),
                        row.getObject("entity_id", UUID.class),
                        row.getString("locale"),
                        row.getString("name"),
                        row.getString("description")))
                .list();
    }

    public List<MediaRelationRow> mediaRelations(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT entity_type, entity_id, media_asset_id, role, sort_order
                FROM catalog.media_relations
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                ORDER BY sort_order
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> new MediaRelationRow(
                        EntityType.valueOf(row.getString("entity_type")),
                        row.getObject("entity_id", UUID.class),
                        row.getObject("media_asset_id", UUID.class),
                        row.getString("role")))
                .list();
    }

    public List<LocationOffering> offeringsForBrand(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT * FROM catalog.location_offerings
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(JdbcCatalogStore::mapOffering)
                .list();
    }

    public List<LocationOffering> offeringsForLocation(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT * FROM catalog.location_offerings
                WHERE tenant_id = :tenantId AND location_id = :locationId AND status <> 'HIDDEN'
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcCatalogStore::mapOffering)
                .list();
    }

    // ------------------------------------------------------------ publications

    public void insertPublication(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID catalogId,
            String channel,
            PublicationStatus status,
            String contentHash,
            ValidationFinding.Report report,
            @Nullable UUID actorId,
            Instant createdAt,
            @Nullable Instant activatedAt) {
        jdbc.sql("""
                INSERT INTO catalog.publications (
                    id, tenant_id, brand_id, catalog_id, channel, status, content_hash,
                    validation_report, created_by, created_at, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, :channel, :status, :contentHash,
                    CAST(:report AS jsonb), :createdBy, :createdAt, :activatedAt)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .param("channel", channel)
                .param("status", status.name())
                .param("contentHash", contentHash)
                .param("report", jsonb(report))
                .param("createdBy", actorId)
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .param(
                        "activatedAt",
                        activatedAt == null ? null : OffsetDateTime.ofInstant(activatedAt, ZoneOffset.UTC))
                .update();
    }

    public void insertPublicationItems(UUID publicationId, UUID tenantId, UUID brandId, List<PublicationItem> items) {
        for (PublicationItem item : items) {
            jdbc.sql("""
                    INSERT INTO catalog.publication_items (
                        publication_id, tenant_id, brand_id, entity_type, entity_id,
                        entity_version, immutable_content_json)
                    VALUES (:publicationId, :tenantId, :brandId, :entityType, :entityId,
                        :entityVersion, CAST(:content AS jsonb))
                    """)
                    .param("publicationId", publicationId)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("entityType", item.entityType().name())
                    .param("entityId", item.entityId())
                    .param("entityVersion", item.entityVersion())
                    .param("content", jsonb(item.content()))
                    .update();
        }
    }

    public void retireActivePublication(UUID tenantId, UUID brandId, String channel, Instant now) {
        jdbc.sql("""
                UPDATE catalog.publications
                SET status = 'RETIRED', retired_at = :now
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND channel = :channel AND status = 'PUBLISHED'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("channel", channel)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    public void activatePublication(UUID publicationId, Instant now) {
        jdbc.sql("""
                UPDATE catalog.publications
                SET status = 'PUBLISHED', activated_at = :now, retired_at = NULL
                WHERE id = :id
                """)
                .param("id", publicationId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    public Optional<PublicationRow> findPublication(UUID tenantId, UUID brandId, UUID publicationId) {
        return jdbc.sql("""
                SELECT id, status, content_hash, catalog_id, channel
                FROM catalog.publications
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", publicationId)
                .query((row, number) -> new PublicationRow(
                        row.getObject("id", UUID.class),
                        PublicationStatus.valueOf(row.getString("status")),
                        row.getString("content_hash"),
                        row.getObject("catalog_id", UUID.class),
                        row.getString("channel")))
                .optional();
    }

    public Optional<UUID> findActivePublicationId(UUID tenantId, UUID brandId, String channel) {
        return jdbc.sql("""
                SELECT id FROM catalog.publications
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND channel = :channel AND status = 'PUBLISHED'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("channel", channel)
                .query(UUID.class)
                .optional();
    }

    /** Reads a published snapshot. The storefront's only source. */
    public List<PublicationItem> publicationItems(UUID publicationId, EntityType entityType) {
        return jdbc.sql("""
                SELECT entity_type, entity_id, entity_version, immutable_content_json
                FROM catalog.publication_items
                WHERE publication_id = :publicationId AND entity_type = :entityType
                """)
                .param("publicationId", publicationId)
                .param("entityType", entityType.name())
                .query((row, number) -> new PublicationItem(
                        EntityType.valueOf(row.getString("entity_type")),
                        row.getObject("entity_id", UUID.class),
                        row.getInt("entity_version"),
                        readJson(row.getString("immutable_content_json"))))
                .list();
    }

    // ------------------------------------------------------------------ mapping

    private static Product mapProduct(ResultSet row, int number) throws SQLException {
        return new Product(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getString("code"),
                Status.valueOf(row.getString("status")),
                row.getInt("version"));
    }

    private static Variant mapVariant(ResultSet row, int number) throws SQLException {
        return new Variant(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("product_id", UUID.class),
                row.getString("sku"),
                row.getString("unit_code"),
                row.getBoolean("is_default"),
                row.getInt("sort_order"),
                Status.valueOf(row.getString("status")),
                row.getInt("version"));
    }

    /**
     * ADR 0038's classification row.
     *
     * <p>Every nullable numeric column is read through {@code getObject}. The
     * primitive accessors answer 0 for a SQL null, and 0 is a fiscal unit code
     * that a receipt would carry as though somebody had chosen it.
     */
    private static FiscalClassification mapClassification(ResultSet row) throws SQLException {
        return new FiscalClassification(
                row.getString("mxik_code"),
                row.getString("package_code"),
                row.getObject("fiscal_unit_code", Integer.class),
                row.getString("fiscal_name"),
                row.getString("barcode"),
                row.getBoolean("marking_required"),
                MarkingScheme.valueOf(row.getString("marking_scheme")),
                row.getBoolean("excisable"),
                row.getObject("alcohol_by_volume_bp", Integer.class),
                row.getObject("age_restriction_years", Integer.class));
    }

    private static Category mapCategory(ResultSet row, int number) throws SQLException {
        return new Category(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("catalog_id", UUID.class),
                row.getObject("parent_category_id", UUID.class),
                row.getString("code"),
                row.getInt("sort_order"),
                Status.valueOf(row.getString("status")),
                row.getInt("version"));
    }

    private static ModifierGroup mapModifierGroup(ResultSet row, int number) throws SQLException {
        return new ModifierGroup(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getString("code"),
                row.getBoolean("is_required"),
                row.getInt("minimum_selections"),
                row.getInt("maximum_selections"),
                row.getBoolean("allow_same_option_multiple_times"),
                row.getInt("sort_order"),
                Status.valueOf(row.getString("status")),
                row.getInt("version"));
    }

    private static ModifierOption mapModifierOption(ResultSet row, int number) throws SQLException {
        return new ModifierOption(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("modifier_group_id", UUID.class),
                row.getString("code"),
                row.getObject("linked_variant_id", UUID.class),
                row.getInt("maximum_quantity"),
                row.getInt("sort_order"),
                Status.valueOf(row.getString("status")),
                row.getInt("version"));
    }

    private static LocationOffering mapOffering(ResultSet row, int number) throws SQLException {
        return new LocationOffering(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("variant_id", UUID.class),
                OfferingStatus.valueOf(row.getString("status")),
                List.of(row.getString("fulfillment_modes").split(",")),
                row.getInt("version"));
    }

    /**
     * Serialises to a JSON string, cast to jsonb in SQL.
     *
     * <p>A string plus a cast rather than a driver-specific type, so the
     * PostgreSQL driver stays a runtime dependency and nothing here compiles
     * against it.
     */
    private String jsonb(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> readJson(String json) {
        return objectMapper.readValue(json, java.util.Map.class);
    }

    /**
     * One row of the 86 screen (catalog.md §4.6).
     *
     * @param categoryName null when the product sits in no category
     * @param trackingMode {@code BINARY}, {@code UNTRACKED}, {@code QUANTITY},
     *                     or null when the variant carries no {@code
     *                     inventory.stock_items} row at this location at all
     */
    public record VariantAvailabilityRow(
            UUID variantId,
            String productName,
            @Nullable String categoryName,
            boolean available,
            @Nullable String trackingMode) {}

    public record PublicationRow(
            UUID id, PublicationStatus status, String contentHash, UUID catalogId, String channel) {}

    public record TranslationRow(
            EntityType entityType, UUID entityId, String locale, String name, @Nullable String description) {}

    public record MediaRelationRow(EntityType entityType, UUID entityId, UUID mediaAssetId, String role) {}
}
