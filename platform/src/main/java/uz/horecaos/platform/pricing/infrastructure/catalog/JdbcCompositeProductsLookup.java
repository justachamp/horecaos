package uz.horecaos.platform.pricing.infrastructure.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.pricing.application.CompositePricing.ComboComponentFact;
import uz.horecaos.platform.pricing.application.CompositePricing.ComboGroupFact;
import uz.horecaos.platform.pricing.application.CompositePricing.HiddenCharge;
import uz.horecaos.platform.pricing.application.CompositePricing.NestedGroupFact;
import uz.horecaos.platform.pricing.application.CompositePricing.OptionFact;
import uz.horecaos.platform.pricing.application.CompositeProductsLookup;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * Reads the composite-product facts a quote needs (ADR 0136).
 *
 * <p>From the authoring tables, restricted to rows that are {@code ACTIVE}: the
 * published snapshot carries no combo structure until a combo-aware publication
 * exists, and a quote already reads live prices from the price book and live names
 * from the draft, so the structure it prices is as current as the rest of what it
 * prices. Every query carries the tenant and the brand; none interpolates a caller's
 * text into SQL.
 *
 * <p>Attachments are read the way the catalog defines them: a product's attachments
 * with the variant's own laid over them, the variant's winning for the same group.
 */
@Component
public class JdbcCompositeProductsLookup implements CompositeProductsLookup {

    /**
     * A variant's effective attachments, product-level and variant-level, the
     * variant-level row winning for the same group. One definition for both readers
     * below, so the hidden-charge reader and the nested-group reader cannot disagree
     * about which attachment is in force.
     */
    private static final String EFFECTIVE_ATTACHMENTS = """
            SELECT DISTINCT ON (variant_id, modifier_group_id)
                   variant_id, modifier_group_id, visibility, modes,
                   required_override, minimum_override, maximum_override
            FROM (
                SELECT v.id AS variant_id, pmg.modifier_group_id, pmg.visibility,
                       pmg.applicable_fulfillment_modes AS modes,
                       pmg.required_override,
                       pmg.minimum_selections_override AS minimum_override,
                       pmg.maximum_selections_override AS maximum_override,
                       1 AS source_rank
                FROM catalog.variants v
                JOIN catalog.product_modifier_groups pmg
                  ON pmg.product_id = v.product_id AND pmg.tenant_id = v.tenant_id AND pmg.brand_id = v.brand_id
                WHERE v.tenant_id = :tenantId AND v.brand_id = :brandId AND v.id = ANY(:ids)
                UNION ALL
                SELECT vmg.variant_id, vmg.modifier_group_id, vmg.visibility,
                       vmg.applicable_fulfillment_modes,
                       vmg.required_override,
                       vmg.minimum_selections_override,
                       vmg.maximum_selections_override,
                       0
                FROM catalog.variant_modifier_groups vmg
                WHERE vmg.tenant_id = :tenantId AND vmg.brand_id = :brandId AND vmg.variant_id = ANY(:ids)
            ) levels
            ORDER BY variant_id, modifier_group_id, source_rank
            """;

    private final JdbcClient jdbc;

    public JdbcCompositeProductsLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ComboCatalog comboCatalog(UUID tenantId, UUID brandId, Set<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return ComboCatalog.empty();
        }
        record Row(
                ComboGroupFact group,
                @org.jspecify.annotations.Nullable ComboComponentFact component) {}
        List<Row> rows = jdbc.sql("""
                SELECT g.id AS group_id, g.container_variant_id, g.minimum_selections, g.maximum_selections,
                       g.allow_same_component_multiple_times, g.sort_order AS group_sort,
                       c.id AS component_id, c.component_variant_id, c.default_quantity,
                       c.sort_order AS component_sort
                FROM catalog.combo_groups g
                LEFT JOIN catalog.combo_components c
                       ON c.combo_group_id = g.id AND c.tenant_id = g.tenant_id AND c.brand_id = g.brand_id
                      AND c.status = 'ACTIVE'
                      AND EXISTS (
                          SELECT 1 FROM catalog.variants v
                          WHERE v.id = c.component_variant_id AND v.tenant_id = c.tenant_id
                            AND v.brand_id = c.brand_id AND v.status = 'ACTIVE')
                WHERE g.tenant_id = :tenantId AND g.brand_id = :brandId AND g.status = 'ACTIVE'
                  AND g.container_variant_id = ANY(:ids)
                ORDER BY g.sort_order, g.id, c.sort_order, c.id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", variantIds.toArray(UUID[]::new))
                .query((row, number) -> {
                    UUID groupId = row.getObject("group_id", UUID.class);
                    ComboGroupFact group = new ComboGroupFact(
                            groupId,
                            row.getObject("container_variant_id", UUID.class),
                            row.getInt("minimum_selections"),
                            row.getInt("maximum_selections"),
                            row.getBoolean("allow_same_component_multiple_times"),
                            row.getInt("group_sort"));
                    UUID componentId = row.getObject("component_id", UUID.class);
                    ComboComponentFact component = componentId == null
                            ? null
                            : new ComboComponentFact(
                                    componentId,
                                    groupId,
                                    row.getObject("component_variant_id", UUID.class),
                                    row.getInt("default_quantity"),
                                    row.getInt("component_sort"));
                    return new Row(group, component);
                })
                .list();

        Map<UUID, ComboGroupFact> groups = new LinkedHashMap<>();
        Map<UUID, List<UUID>> groupIdsByContainer = new LinkedHashMap<>();
        Map<UUID, ComboComponentFact> components = new LinkedHashMap<>();
        for (Row row : rows) {
            if (groups.putIfAbsent(row.group().id(), row.group()) == null) {
                groupIdsByContainer
                        .computeIfAbsent(row.group().containerVariantId(), key -> new ArrayList<>())
                        .add(row.group().id());
            }
            if (row.component() != null) {
                components.put(row.component().id(), row.component());
            }
        }
        return new ComboCatalog(groups, groupIdsByContainer, components);
    }

    @Override
    public Map<UUID, List<HiddenCharge>> hiddenCharges(
            UUID tenantId, UUID brandId, Set<UUID> variantIds, FulfillmentMode mode) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<HiddenCharge>> byVariant = new LinkedHashMap<>();
        jdbc.sql("""
                WITH effective AS (%s)
                SELECT e.variant_id, e.modifier_group_id,
                       COALESCE(e.required_override, mg.is_required) AS required,
                       COALESCE(
                           array_agg(mo.id ORDER BY mo.sort_order, mo.id) FILTER (WHERE mo.id IS NOT NULL),
                           ARRAY[]::uuid[]) AS option_ids
                FROM effective e
                JOIN catalog.modifier_groups mg
                  ON mg.id = e.modifier_group_id AND mg.tenant_id = :tenantId AND mg.brand_id = :brandId
                 AND mg.status = 'ACTIVE'
                LEFT JOIN catalog.modifier_options mo
                  ON mo.modifier_group_id = mg.id AND mo.tenant_id = mg.tenant_id AND mo.brand_id = mg.brand_id
                 AND mo.status = 'ACTIVE'
                WHERE e.visibility = 'HIDDEN_AUTO_SELECT'
                  AND (e.modes IS NULL OR :mode = ANY(e.modes))
                GROUP BY e.variant_id, e.modifier_group_id, e.required_override, mg.is_required
                ORDER BY e.variant_id, e.modifier_group_id
                """.formatted(EFFECTIVE_ATTACHMENTS))
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", variantIds.toArray(UUID[]::new))
                .param("mode", mode.name())
                .query((row, number) -> {
                    UUID groupId = row.getObject("modifier_group_id", UUID.class);
                    Object[] optionIds = (Object[]) row.getArray("option_ids").getArray();
                    // The validator blocks this at publication; authoring moves on after
                    // that, and with no customer to choose, guessing is the one thing a
                    // charge on a receipt may not do.
                    if (!row.getBoolean("required") || optionIds.length != 1) {
                        throw new HiddenModifierAmbiguousException(groupId);
                    }
                    byVariant
                            .computeIfAbsent(row.getObject("variant_id", UUID.class), key -> new ArrayList<>())
                            .add(new HiddenCharge(groupId, UUID.fromString(String.valueOf(optionIds[0]))));
                    return groupId;
                })
                .list();
        return byVariant;
    }

    @Override
    public NestedCatalog nestedCatalog(UUID tenantId, UUID brandId, Set<UUID> optionIds) {
        if (optionIds.isEmpty()) {
            return NestedCatalog.empty();
        }
        Map<UUID, OptionFact> options = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT id, modifier_group_id, linked_variant_id, maximum_quantity
                FROM catalog.modifier_options
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = ANY(:ids) AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", optionIds.toArray(UUID[]::new))
                .query((row, number) -> {
                    UUID id = row.getObject("id", UUID.class);
                    options.put(
                            id,
                            new OptionFact(
                                    id,
                                    row.getObject("modifier_group_id", UUID.class),
                                    row.getObject("linked_variant_id", UUID.class),
                                    row.getInt("maximum_quantity")));
                    return id;
                })
                .list();

        Set<UUID> linkedVariants = new LinkedHashSet<>();
        options.values().stream()
                .map(OptionFact::linkedVariantId)
                .filter(java.util.Objects::nonNull)
                .forEach(linkedVariants::add);
        if (linkedVariants.isEmpty()) {
            return new NestedCatalog(options, Map.of());
        }

        record GroupRow(
                UUID variantId, UUID groupId, boolean required, int minimum, int maximum, boolean allowSameOption) {}
        List<GroupRow> groupRows = jdbc.sql("""
                WITH effective AS (%s)
                SELECT e.variant_id, e.modifier_group_id,
                       COALESCE(e.required_override, mg.is_required) AS required,
                       COALESCE(e.minimum_override, mg.minimum_selections) AS minimum,
                       COALESCE(e.maximum_override, mg.maximum_selections) AS maximum,
                       mg.allow_same_option_multiple_times AS allow_same
                FROM effective e
                JOIN catalog.modifier_groups mg
                  ON mg.id = e.modifier_group_id AND mg.tenant_id = :tenantId AND mg.brand_id = :brandId
                 AND mg.status = 'ACTIVE'
                WHERE e.visibility = 'VISIBLE'
                ORDER BY e.variant_id, mg.id
                """.formatted(EFFECTIVE_ATTACHMENTS))
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", linkedVariants.toArray(UUID[]::new))
                .query((row, number) -> new GroupRow(
                        row.getObject("variant_id", UUID.class),
                        row.getObject("modifier_group_id", UUID.class),
                        row.getBoolean("required"),
                        row.getInt("minimum"),
                        row.getInt("maximum"),
                        row.getBoolean("allow_same")))
                .list();
        if (groupRows.isEmpty()) {
            return new NestedCatalog(options, Map.of());
        }

        Map<UUID, Set<UUID>> optionsOfGroup = new HashMap<>();
        jdbc.sql("""
                SELECT modifier_group_id, id FROM catalog.modifier_options
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND modifier_group_id = ANY(:groupIds) AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param(
                        "groupIds",
                        groupRows.stream().map(GroupRow::groupId).distinct().toArray(UUID[]::new))
                .query((row, number) -> {
                    optionsOfGroup
                            .computeIfAbsent(
                                    row.getObject("modifier_group_id", UUID.class), key -> new LinkedHashSet<>())
                            .add(row.getObject("id", UUID.class));
                    return 0;
                })
                .list();

        Map<UUID, List<NestedGroupFact>> byVariant = new LinkedHashMap<>();
        for (GroupRow group : groupRows) {
            byVariant
                    .computeIfAbsent(group.variantId(), key -> new ArrayList<>())
                    .add(new NestedGroupFact(
                            group.groupId(),
                            group.required(),
                            group.minimum(),
                            group.maximum(),
                            group.allowSameOption(),
                            optionsOfGroup.getOrDefault(group.groupId(), Set.of())));
        }
        return new NestedCatalog(options, byVariant);
    }
}
