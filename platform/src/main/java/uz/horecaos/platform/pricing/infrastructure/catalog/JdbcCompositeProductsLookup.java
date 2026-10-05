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
 * <p>What a customer chooses within -- a combo's groups and components, the options of a
 * group and the choices an option opens -- is read from the publication the quote is
 * stamped with, so the rules enforced are the rules that were on screen and a combo edited
 * and not republished prices the structure that was published. The hidden charges are the
 * one live read: they are not a choice but a charge the server applies, and the authoring
 * rows are where the operator switches one on or off. Every query carries the tenant and
 * the brand; none interpolates a caller's text into SQL.
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
    public ComboCatalog comboCatalog(UUID tenantId, UUID brandId, UUID publicationId, Set<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return ComboCatalog.empty();
        }
        record Row(
                ComboGroupFact group,
                @org.jspecify.annotations.Nullable ComboComponentFact component) {}
        List<Row> rows = jdbc.sql("""
                SELECT pi.entity_id AS group_id,
                       (pi.immutable_content_json ->> 'containerVariantId')::uuid AS container_variant_id,
                       COALESCE((pi.immutable_content_json ->> 'minimumSelections')::int, 0) AS minimum_selections,
                       COALESCE((pi.immutable_content_json ->> 'maximumSelections')::int, 1) AS maximum_selections,
                       COALESCE((pi.immutable_content_json ->> 'allowSameComponentMultipleTimes')::boolean, false)
                           AS allow_same_component_multiple_times,
                       COALESCE((pi.immutable_content_json ->> 'sortOrder')::int, 0) AS group_sort,
                       (c.component ->> 'componentId')::uuid AS component_id,
                       (c.component ->> 'variantId')::uuid AS component_variant_id,
                       COALESCE((c.component ->> 'defaultQuantity')::int, 1) AS default_quantity,
                       COALESCE((c.component ->> 'sortOrder')::int, 0) AS component_sort
                FROM catalog.publication_items pi
                LEFT JOIN LATERAL (
                    SELECT e AS component
                    FROM jsonb_array_elements(
                             CASE WHEN jsonb_typeof(pi.immutable_content_json -> 'components') = 'array'
                                  THEN pi.immutable_content_json -> 'components'
                                  ELSE '[]'::jsonb END) AS e
                    -- The one live check: a variant withdrawn since the publication is not offered.
                    WHERE EXISTS (
                        SELECT 1 FROM catalog.variants v
                        WHERE v.id = (e ->> 'variantId')::uuid AND v.tenant_id = pi.tenant_id
                          AND v.brand_id = pi.brand_id AND v.status = 'ACTIVE')
                ) c ON true
                WHERE pi.publication_id = :publicationId AND pi.tenant_id = :tenantId AND pi.brand_id = :brandId
                  AND pi.entity_type = 'COMBO_GROUP'
                  AND COALESCE(pi.immutable_content_json ->> 'status', 'ACTIVE') = 'ACTIVE'
                  AND pi.immutable_content_json ->> 'containerVariantId' = ANY(:ids)
                ORDER BY group_sort, group_id, component_sort, component_id
                """)
                .param("publicationId", publicationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", variantIds.stream().map(UUID::toString).toArray(String[]::new))
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
    public NestedCatalog nestedCatalog(UUID tenantId, UUID brandId, UUID publicationId, Set<UUID> optionIds) {
        if (optionIds.isEmpty()) {
            return NestedCatalog.empty();
        }
        // The options a line names, with the choices each opens, as the publication states them.
        // One row per (option, nested group); an option that opens nothing is one row with no group.
        record OptionRow(
                OptionFact option,
                @org.jspecify.annotations.Nullable UUID nestedGroupId,
                boolean required,
                int minimum,
                int maximum) {}
        List<OptionRow> optionRows = jdbc.sql("""
                SELECT (o.option ->> 'optionId')::uuid AS option_id,
                       pi.entity_id AS group_id,
                       (o.option ->> 'linkedVariantId')::uuid AS linked_variant_id,
                       COALESCE((o.option ->> 'maximumQuantity')::int, 1) AS maximum_quantity,
                       (ng.nested ->> 'groupId')::uuid AS nested_group_id,
                       COALESCE((ng.nested ->> 'required')::boolean, false) AS nested_required,
                       COALESCE((ng.nested ->> 'minimumSelections')::int, 0) AS nested_minimum,
                       COALESCE((ng.nested ->> 'maximumSelections')::int, 1) AS nested_maximum
                FROM catalog.publication_items pi
                CROSS JOIN LATERAL (
                    SELECT e AS option
                    FROM jsonb_array_elements(
                             CASE WHEN jsonb_typeof(pi.immutable_content_json -> 'options') = 'array'
                                  THEN pi.immutable_content_json -> 'options'
                                  ELSE '[]'::jsonb END) AS e
                    WHERE e ->> 'optionId' = ANY(:ids) AND COALESCE(e ->> 'status', 'ACTIVE') = 'ACTIVE'
                ) o
                LEFT JOIN LATERAL (
                    SELECT n AS nested, ordinality
                    FROM jsonb_array_elements(
                             CASE WHEN jsonb_typeof(o.option -> 'nestedGroups') = 'array'
                                  THEN o.option -> 'nestedGroups'
                                  ELSE '[]'::jsonb END) WITH ORDINALITY AS t(n, ordinality)
                ) ng ON true
                WHERE pi.publication_id = :publicationId AND pi.tenant_id = :tenantId AND pi.brand_id = :brandId
                  AND pi.entity_type = 'MODIFIER_GROUP'
                ORDER BY option_id, ng.ordinality
                """)
                .param("publicationId", publicationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", optionIds.stream().map(UUID::toString).toArray(String[]::new))
                .query((row, number) -> new OptionRow(
                        new OptionFact(
                                row.getObject("option_id", UUID.class),
                                row.getObject("group_id", UUID.class),
                                row.getObject("linked_variant_id", UUID.class),
                                row.getInt("maximum_quantity")),
                        row.getObject("nested_group_id", UUID.class),
                        row.getBoolean("nested_required"),
                        row.getInt("nested_minimum"),
                        row.getInt("nested_maximum")))
                .list();

        Map<UUID, OptionFact> options = new LinkedHashMap<>();
        optionRows.forEach(row -> options.putIfAbsent(row.option().optionId(), row.option()));

        Set<UUID> nestedGroupIds = new LinkedHashSet<>();
        optionRows.stream()
                .map(OptionRow::nestedGroupId)
                .filter(java.util.Objects::nonNull)
                .forEach(nestedGroupIds::add);
        if (nestedGroupIds.isEmpty()) {
            return new NestedCatalog(options, Map.of());
        }

        // What each nested group offers: the options it lists as active, and whether one may be
        // taken twice. Those are the group's own, not the attachment's.
        Map<UUID, Set<UUID>> optionsOfGroup = new HashMap<>();
        Map<UUID, Boolean> allowSameOption = new HashMap<>();
        jdbc.sql("""
                SELECT pi.entity_id AS group_id,
                       COALESCE((pi.immutable_content_json ->> 'allowSameOptionMultipleTimes')::boolean, false)
                           AS allow_same,
                       (o.option ->> 'optionId')::uuid AS option_id
                FROM catalog.publication_items pi
                LEFT JOIN LATERAL (
                    SELECT e AS option
                    FROM jsonb_array_elements(
                             CASE WHEN jsonb_typeof(pi.immutable_content_json -> 'options') = 'array'
                                  THEN pi.immutable_content_json -> 'options'
                                  ELSE '[]'::jsonb END) AS e
                    WHERE COALESCE(e ->> 'status', 'ACTIVE') = 'ACTIVE'
                ) o ON true
                WHERE pi.publication_id = :publicationId AND pi.tenant_id = :tenantId AND pi.brand_id = :brandId
                  AND pi.entity_type = 'MODIFIER_GROUP' AND pi.entity_id = ANY(:groupIds)
                """)
                .param("publicationId", publicationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("groupIds", nestedGroupIds.toArray(UUID[]::new))
                .query((row, number) -> {
                    UUID groupId = row.getObject("group_id", UUID.class);
                    allowSameOption.put(groupId, row.getBoolean("allow_same"));
                    optionsOfGroup.computeIfAbsent(groupId, key -> new LinkedHashSet<>());
                    UUID optionId = row.getObject("option_id", UUID.class);
                    if (optionId != null) {
                        optionsOfGroup.get(groupId).add(optionId);
                    }
                    return groupId;
                })
                .list();

        // Keyed by the variant the option links, which is what the selection rules look up. Two
        // options linking one variant publish the same choices, so the first is enough.
        Map<UUID, List<NestedGroupFact>> byVariant = new LinkedHashMap<>();
        for (OptionRow row : optionRows) {
            UUID linkedVariant = row.option().linkedVariantId();
            if (linkedVariant == null || row.nestedGroupId() == null || byVariant.containsKey(linkedVariant)) {
                continue;
            }
            List<NestedGroupFact> groups = new ArrayList<>();
            for (OptionRow other : optionRows) {
                UUID groupId = other.nestedGroupId();
                if (groupId == null
                        || !row.option().optionId().equals(other.option().optionId())) {
                    continue;
                }
                Boolean allowSame = allowSameOption.get(groupId);
                // A group the publication does not carry cannot be chosen from.
                if (allowSame == null) {
                    continue;
                }
                groups.add(new NestedGroupFact(
                        groupId,
                        other.required(),
                        other.minimum(),
                        other.maximum(),
                        allowSame,
                        optionsOfGroup.getOrDefault(groupId, Set.of())));
            }
            byVariant.put(linkedVariant, List.copyOf(groups));
        }
        return new NestedCatalog(options, byVariant);
    }
}
