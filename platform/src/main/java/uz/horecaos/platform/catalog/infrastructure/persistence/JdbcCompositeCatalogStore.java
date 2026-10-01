package uz.horecaos.platform.catalog.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.CompositeProducts.AttachmentOwnerType;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboComponent;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboGroup;
import uz.horecaos.platform.catalog.domain.CompositeProducts.FulfillmentMode;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ModifierAttachment;
import uz.horecaos.platform.catalog.domain.CompositeProducts.Visibility;

/**
 * Persistence for composite products (ADR 0136): combo groups and components, and
 * the modifier attachments' visibility and overrides.
 *
 * <p>A class of its own rather than more methods on {@link JdbcCatalogStore},
 * which is already three thousand lines: the two share a schema and a rule --
 * every query carries the tenant and the brand, so no path materialises another
 * brand's row -- and nothing else.
 *
 * <p>Every update is conditional on the version the caller read. The write itself
 * refuses a stale one, never a SELECT followed by an UPDATE, because the gap
 * between the two is exactly where a second editor lands.
 */
@Repository
public class JdbcCompositeCatalogStore {

    private final JdbcClient jdbc;

    public JdbcCompositeCatalogStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- combo groups

    public void insertComboGroup(ComboGroup group) {
        jdbc.sql("""
                INSERT INTO catalog.combo_groups (
                    id, tenant_id, brand_id, container_variant_id, code, minimum_selections,
                    maximum_selections, allow_same_component_multiple_times, sort_order, status)
                VALUES (:id, :tenantId, :brandId, :containerVariantId, :code, :minimum,
                    :maximum, :allowSame, :sortOrder, :status)
                """)
                .param("id", group.id())
                .param("tenantId", group.tenantId())
                .param("brandId", group.brandId())
                .param("containerVariantId", group.containerVariantId())
                .param("code", group.code())
                .param("minimum", group.minimumSelections())
                .param("maximum", group.maximumSelections())
                .param("allowSame", group.allowSameComponentMultipleTimes())
                .param("sortOrder", group.sortOrder())
                .param("status", group.status().name())
                .update();
    }

    public Optional<ComboGroup> comboGroup(UUID tenantId, UUID brandId, UUID comboGroupId) {
        return jdbc.sql("""
                SELECT * FROM catalog.combo_groups
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", comboGroupId)
                .query(JdbcCompositeCatalogStore::mapGroup)
                .optional();
    }

    /** A container variant's groups, in the order a customer meets them. */
    public List<ComboGroup> comboGroupsForContainer(UUID tenantId, UUID brandId, UUID containerVariantId) {
        return jdbc.sql("""
                SELECT * FROM catalog.combo_groups
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND container_variant_id = :containerVariantId
                ORDER BY sort_order, code
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("containerVariantId", containerVariantId)
                .query(JdbcCompositeCatalogStore::mapGroup)
                .list();
    }

    /**
     * Every group whose container variant is sold from this catalog, for the
     * validator.
     *
     * <p>Scoped to the catalog by the container, the way {@code
     * modifierGroupsInCatalog} scopes a group by the product that offers it. A
     * component may be a variant of a product in another catalog of the brand:
     * that is a variant, and variants are brand-wide.
     */
    public List<ComboGroup> comboGroupsInCatalog(UUID tenantId, UUID brandId, UUID catalogId) {
        return jdbc.sql("""
                SELECT DISTINCT g.* FROM catalog.combo_groups g
                JOIN catalog.variants v
                  ON v.id = g.container_variant_id AND v.tenant_id = g.tenant_id AND v.brand_id = g.brand_id
                JOIN catalog.catalog_products link
                  ON link.product_id = v.product_id AND link.tenant_id = g.tenant_id AND link.brand_id = g.brand_id
                WHERE g.tenant_id = :tenantId AND g.brand_id = :brandId AND link.catalog_id = :catalogId
                ORDER BY g.sort_order, g.code
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .query(JdbcCompositeCatalogStore::mapGroup)
                .list();
    }

    /**
     * Writes a group's mutable fields if, and only if, it is still at the version
     * the caller read.
     *
     * @return false when the version has moved on or the group is not this brand's
     */
    public boolean updateComboGroup(ComboGroup group, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE catalog.combo_groups
                           SET minimum_selections = :minimum,
                               maximum_selections = :maximum,
                               allow_same_component_multiple_times = :allowSame,
                               sort_order = :sortOrder,
                               status = :status,
                               version = version + 1,
                               updated_at = now()
                         WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                           AND version = :expectedVersion
                        """)
                        .param("minimum", group.minimumSelections())
                        .param("maximum", group.maximumSelections())
                        .param("allowSame", group.allowSameComponentMultipleTimes())
                        .param("sortOrder", group.sortOrder())
                        .param("status", group.status().name())
                        .param("tenantId", group.tenantId())
                        .param("brandId", group.brandId())
                        .param("id", group.id())
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    // --------------------------------------------------------- combo components

    public void insertComboComponent(ComboComponent component) {
        jdbc.sql("""
                INSERT INTO catalog.combo_components (
                    id, tenant_id, brand_id, combo_group_id, component_variant_id,
                    default_quantity, sort_order, status)
                VALUES (:id, :tenantId, :brandId, :comboGroupId, :componentVariantId,
                    :defaultQuantity, :sortOrder, :status)
                """)
                .param("id", component.id())
                .param("tenantId", component.tenantId())
                .param("brandId", component.brandId())
                .param("comboGroupId", component.comboGroupId())
                .param("componentVariantId", component.componentVariantId())
                .param("defaultQuantity", component.defaultQuantity())
                .param("sortOrder", component.sortOrder())
                .param("status", component.status().name())
                .update();
    }

    public Optional<ComboComponent> comboComponent(UUID tenantId, UUID brandId, UUID componentId) {
        return jdbc.sql("""
                SELECT * FROM catalog.combo_components
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", componentId)
                .query(JdbcCompositeCatalogStore::mapComponent)
                .optional();
    }

    public List<ComboComponent> componentsForGroups(UUID tenantId, UUID brandId, List<UUID> comboGroupIds) {
        if (comboGroupIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT * FROM catalog.combo_components
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND combo_group_id = ANY(:groupIds)
                ORDER BY sort_order, id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("groupIds", comboGroupIds.toArray(UUID[]::new))
                .query(JdbcCompositeCatalogStore::mapComponent)
                .list();
    }

    public boolean updateComboComponent(ComboComponent component, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE catalog.combo_components
                           SET default_quantity = :defaultQuantity,
                               sort_order = :sortOrder,
                               status = :status,
                               version = version + 1,
                               updated_at = now()
                         WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                           AND version = :expectedVersion
                        """)
                        .param("defaultQuantity", component.defaultQuantity())
                        .param("sortOrder", component.sortOrder())
                        .param("status", component.status().name())
                        .param("tenantId", component.tenantId())
                        .param("brandId", component.brandId())
                        .param("id", component.id())
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    /** Whether a variant is the container of any combo group, whatever its status. */
    public boolean isComboContainer(UUID tenantId, UUID brandId, UUID variantId) {
        return jdbc.sql("""
                        SELECT count(*) FROM catalog.combo_groups
                        WHERE tenant_id = :tenantId AND brand_id = :brandId AND container_variant_id = :variantId
                        """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("variantId", variantId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** Whether a variant is a component of any combo group, whatever its status. */
    public boolean isComboComponent(UUID tenantId, UUID brandId, UUID variantId) {
        return jdbc.sql("""
                        SELECT count(*) FROM catalog.combo_components
                        WHERE tenant_id = :tenantId AND brand_id = :brandId AND component_variant_id = :variantId
                        """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("variantId", variantId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** The statuses of the variants the caller names, for an existence-and-activity check. */
    public Map<UUID, Status> variantStatuses(UUID tenantId, UUID brandId, Set<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Status> statuses = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT id, status FROM catalog.variants
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = ANY(:ids)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", variantIds.toArray(UUID[]::new))
                .query((row, number) ->
                        Map.entry(row.getObject("id", UUID.class), Status.valueOf(row.getString("status"))))
                .list()
                .forEach(entry -> statuses.put(entry.getKey(), entry.getValue()));
        return statuses;
    }

    // ------------------------------------------------------ modifier attachments

    /** Attaches a modifier group to a variant, or re-sorts the attachment if it is already there. */
    public void attachModifierGroupToVariant(
            UUID tenantId, UUID brandId, UUID variantId, UUID modifierGroupId, int sortOrder) {
        jdbc.sql("""
                INSERT INTO catalog.variant_modifier_groups (
                    tenant_id, brand_id, variant_id, modifier_group_id, sort_order)
                VALUES (:tenantId, :brandId, :variantId, :groupId, :sortOrder)
                ON CONFLICT (variant_id, modifier_group_id) DO UPDATE SET sort_order = EXCLUDED.sort_order
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("variantId", variantId)
                .param("groupId", modifierGroupId)
                .param("sortOrder", sortOrder)
                .update();
    }

    public Optional<ModifierAttachment> attachment(
            UUID tenantId, UUID brandId, AttachmentOwnerType ownerType, UUID ownerId, UUID modifierGroupId) {
        String sql = "SELECT %s FROM %s WHERE tenant_id = :tenantId AND brand_id = :brandId AND %s = :ownerId"
                        .formatted(ownerSelect(ownerType), table(ownerType), ownerColumn(ownerType))
                + " AND modifier_group_id = :groupId";
        return jdbc.sql(sql)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ownerId", ownerId)
                .param("groupId", modifierGroupId)
                .query((row, number) -> mapAttachment(row, ownerType))
                .optional();
    }

    public List<ModifierAttachment> attachmentsOf(
            UUID tenantId, UUID brandId, AttachmentOwnerType ownerType, UUID ownerId) {
        String sql = "SELECT %s FROM %s WHERE tenant_id = :tenantId AND brand_id = :brandId AND %s = :ownerId"
                        .formatted(ownerSelect(ownerType), table(ownerType), ownerColumn(ownerType))
                + " ORDER BY sort_order, modifier_group_id";
        return jdbc.sql(sql)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ownerId", ownerId)
                .query((row, number) -> mapAttachment(row, ownerType))
                .list();
    }

    /**
     * Every attachment the brand has, at both levels, for the validator.
     *
     * <p>Brand-wide rather than catalog-scoped for the reason fiscal
     * classifications are: a nested option links a variant whose product may sit
     * in another of the brand's catalogs, and a catalog-scoped read would report
     * that variant as carrying no groups when it does.
     */
    public List<ModifierAttachment> attachmentsForBrand(UUID tenantId, UUID brandId) {
        List<ModifierAttachment> all = new ArrayList<>();
        for (AttachmentOwnerType type : AttachmentOwnerType.values()) {
            all.addAll(jdbc.sql("SELECT %s FROM %s WHERE tenant_id = :tenantId AND brand_id = :brandId"
                                    .formatted(ownerSelect(type), table(type))
                            + " ORDER BY sort_order, modifier_group_id")
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .query((row, number) -> mapAttachment(row, type))
                    .list());
        }
        return List.copyOf(all);
    }

    /**
     * Writes an attachment's policy -- visibility, modes and overrides -- if, and
     * only if, it is still at the version the caller read.
     *
     * <p>The table and owner column come from a switch over a closed enum, never
     * from caller text.
     */
    public boolean updateAttachmentPolicy(ModifierAttachment attachment, int expectedVersion) {
        String[] modes = attachment.modes() == null
                ? null
                : attachment.modes().stream().map(Enum::name).sorted().toArray(String[]::new);
        String sql = ("UPDATE %s SET visibility = :visibility,"
                        + " applicable_fulfillment_modes = CAST(:modes AS text[]),"
                        + " required_override = :requiredOverride,"
                        + " minimum_selections_override = :minimumOverride,"
                        + " maximum_selections_override = :maximumOverride,"
                        + " version = version + 1"
                        + " WHERE tenant_id = :tenantId AND brand_id = :brandId AND %s = :ownerId"
                        + " AND modifier_group_id = :groupId AND version = :expectedVersion")
                .formatted(table(attachment.ownerType()), ownerColumn(attachment.ownerType()));
        return jdbc.sql(sql)
                        .param("visibility", attachment.visibility().name())
                        .param("modes", modes)
                        .param("requiredOverride", attachment.requiredOverride())
                        .param("minimumOverride", attachment.minimumOverride())
                        .param("maximumOverride", attachment.maximumOverride())
                        .param("tenantId", attachment.tenantId())
                        .param("brandId", attachment.brandId())
                        .param("ownerId", attachment.ownerId())
                        .param("groupId", attachment.modifierGroupId())
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    private static String table(AttachmentOwnerType type) {
        return switch (type) {
            case PRODUCT -> "catalog.product_modifier_groups";
            case VARIANT -> "catalog.variant_modifier_groups";
        };
    }

    private static String ownerColumn(AttachmentOwnerType type) {
        return switch (type) {
            case PRODUCT -> "product_id";
            case VARIANT -> "variant_id";
        };
    }

    private static String ownerSelect(AttachmentOwnerType type) {
        return "tenant_id, brand_id, " + ownerColumn(type) + " AS owner_id, modifier_group_id, sort_order, "
                + "visibility, applicable_fulfillment_modes, required_override, "
                + "minimum_selections_override, maximum_selections_override, version";
    }

    // ---------------------------------------------------------------- mappers

    private static ComboGroup mapGroup(ResultSet row, int number) throws SQLException {
        return new ComboGroup(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("container_variant_id", UUID.class),
                row.getString("code"),
                row.getInt("minimum_selections"),
                row.getInt("maximum_selections"),
                row.getBoolean("allow_same_component_multiple_times"),
                row.getInt("sort_order"),
                Status.valueOf(row.getString("status")),
                row.getInt("version"));
    }

    private static ComboComponent mapComponent(ResultSet row, int number) throws SQLException {
        return new ComboComponent(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("combo_group_id", UUID.class),
                row.getObject("component_variant_id", UUID.class),
                row.getInt("default_quantity"),
                row.getInt("sort_order"),
                Status.valueOf(row.getString("status")),
                row.getInt("version"));
    }

    private static ModifierAttachment mapAttachment(ResultSet row, AttachmentOwnerType ownerType) throws SQLException {
        Array modes = row.getArray("applicable_fulfillment_modes");
        Set<FulfillmentMode> parsed = null;
        if (modes != null) {
            parsed = EnumSet.noneOf(FulfillmentMode.class);
            for (Object element : (Object[]) modes.getArray()) {
                parsed.add(FulfillmentMode.valueOf(String.valueOf(element)));
            }
        }
        return new ModifierAttachment(
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                ownerType,
                row.getObject("owner_id", UUID.class),
                row.getObject("modifier_group_id", UUID.class),
                row.getInt("sort_order"),
                Visibility.valueOf(row.getString("visibility")),
                parsed,
                nullableBoolean(row, "required_override"),
                nullableInt(row, "minimum_selections_override"),
                nullableInt(row, "maximum_selections_override"),
                row.getInt("version"));
    }

    private static @Nullable Integer nullableInt(ResultSet row, String column) throws SQLException {
        Object value = row.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }

    private static @Nullable Boolean nullableBoolean(ResultSet row, String column) throws SQLException {
        Object value = row.getObject(column);
        return value == null ? null : (Boolean) value;
    }
}
