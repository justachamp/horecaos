package uz.horecaos.platform.ordering.infrastructure.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.ordering.application.CartMenuRules;

/**
 * Reads one product's published modifier rules (ADR 0016, ADR 0019).
 *
 * <p>Only the publication is read, never {@code catalog.modifier_groups}: the
 * authoring row can be edited while a customer is choosing, and a cart refused
 * against a rule that was not on screen is worse than one that was never
 * enforced.
 *
 * <p>The product is found by the variant it contains rather than by a join,
 * because that relationship exists only inside the published document — a
 * publication item is a copy, and the whole point of the copy is that it does not
 * follow the authoring tables.
 *
 * <p>Every query carries the tenant and the brand through the publication row. A
 * variant id is a UUID a client supplied, and a lookup keyed on the id alone
 * would happily read another brand's rules onto this cart.
 */
@Component
public class JdbcCartMenuRules implements CartMenuRules {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcCartMenuRules(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ProductRules> forVariant(UUID tenantId, UUID brandId, String channelCode, UUID variantId) {

        Optional<UUID> publicationId = jdbc.sql("""
                SELECT id FROM catalog.publications
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND channel = :channel AND status = 'PUBLISHED'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("channel", channelCode)
                .query(UUID.class)
                .optional();
        if (publicationId.isEmpty()) {
            return Optional.empty();
        }

        // A containment match on the published variants array. The alternative is
        // reading every PRODUCT item of the menu on every line edit, which is the
        // whole menu for one line.
        Optional<ProductItem> product = jdbc.sql("""
                SELECT entity_id, immutable_content_json::text AS content
                FROM catalog.publication_items
                WHERE publication_id = :publicationId
                  AND entity_type = 'PRODUCT'
                  AND immutable_content_json -> 'variants' @> CAST(:variantMatch AS jsonb)
                LIMIT 1
                """)
                .param("publicationId", publicationId.get())
                .param("variantMatch", "[{\"variantId\":\"" + variantId + "\"}]")
                .query((row, number) ->
                        new ProductItem(row.getObject("entity_id", UUID.class), readJson(row.getString("content"))))
                .optional();
        if (product.isEmpty()) {
            return Optional.empty();
        }

        Map<UUID, PhysicalRules> physical = physicalRules(product.get().content());
        // ADR 0136: the groups this variant is offered with are its product's and the ones it
        // carries of its own, and where a variant states its own rules for a group it replaces the
        // product's. The cart holds the customer to what the menu showed for the variant they
        // chose, not for the product's other sizes.
        Map<String, Object> variantEntry = variantEntry(product.get().content(), variantId);
        Set<UUID> offered = new LinkedHashSet<>(idList(product.get().content(), "modifierGroupIds"));
        offered.addAll(idList(variantEntry, "modifierGroupIds"));
        if (offered.isEmpty()) {
            return Optional.of(new ProductRules(product.get().entityId(), List.of(), physical));
        }
        Map<UUID, Policy> policies = new LinkedHashMap<>(policies(product.get().content()));
        policies.putAll(policies(variantEntry));
        List<GroupRules> rules = groups(publicationId.get(), List.copyOf(offered)).stream()
                .map(group ->
                        policies.containsKey(group.groupId()) ? group.withPolicy(policies.get(group.groupId())) : group)
                .toList();
        return Optional.of(new ProductRules(product.get().entityId(), rules, physical));
    }

    /**
     * This product's own use of the groups it attaches, where it overrides the shared group's
     * rules (ADR 0136). The published values are already the effective ones, so they replace
     * the group's outright -- the cart then enforces exactly what the storefront showed.
     */
    private static Map<UUID, Policy> policies(Map<String, Object> content) {
        Map<UUID, Policy> byGroup = new LinkedHashMap<>();
        if (content.get("modifierGroupPolicies") instanceof List<?> published) {
            for (Object element : published) {
                if (element instanceof Map<?, ?> policy) {
                    byGroup.put(
                            UUID.fromString(String.valueOf(policy.get("groupId"))),
                            new Policy(
                                    Boolean.TRUE.equals(policy.get("required")),
                                    intOf(policy.get("minimumSelections"), 0),
                                    intOf(policy.get("maximumSelections"), 1)));
                }
            }
        }
        return byGroup;
    }

    /** The published entry of one variant of the product, or an empty map when the product does not list it. */
    private static Map<String, Object> variantEntry(Map<String, Object> content, UUID variantId) {
        if (content.get("variants") instanceof List<?> variants) {
            for (Object element : variants) {
                if (element instanceof Map<?, ?> variant
                        && variantId.toString().equals(String.valueOf(variant.get("variantId")))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) variant;
                    return typed;
                }
            }
        }
        return Map.of();
    }

    /**
     * Each of the product's variants that published a physical block (ADR 0137): whether
     * it is splittable and in what step. A variant without one is absent, which the rules
     * read as whole units only.
     */
    private static Map<UUID, PhysicalRules> physicalRules(Map<String, Object> content) {
        Map<UUID, PhysicalRules> byVariant = new LinkedHashMap<>();
        if (!(content.get("variants") instanceof List<?> variants)) {
            return byVariant;
        }
        for (Object element : variants) {
            if (!(element instanceof Map<?, ?> variant) || !(variant.get("physical") instanceof Map<?, ?> block)) {
                continue;
            }
            Object portion = block.get("portionSize");
            byVariant.put(
                    UUID.fromString(String.valueOf(variant.get("variantId"))),
                    new PhysicalRules(
                            Boolean.TRUE.equals(block.get("splittable")),
                            portion instanceof Number number ? new BigDecimal(number.toString()) : null,
                            Boolean.TRUE.equals(block.get("catchweight"))));
        }
        return byVariant;
    }

    private List<GroupRules> groups(UUID publicationId, List<UUID> groupIds) {
        return jdbc.sql("""
                SELECT entity_id, immutable_content_json::text AS content
                FROM catalog.publication_items
                WHERE publication_id = :publicationId
                  AND entity_type = 'MODIFIER_GROUP'
                  AND entity_id = ANY(:ids)
                """)
                .param("publicationId", publicationId)
                .param("ids", groupIds.toArray(UUID[]::new))
                .query((row, number) ->
                        toGroup(row.getObject("entity_id", UUID.class), readJson(row.getString("content"))))
                .list();
    }

    private static GroupRules toGroup(UUID groupId, Map<String, Object> content) {
        Map<UUID, Integer> options = new LinkedHashMap<>();
        if (content.get("options") instanceof List<?> published) {
            for (Object element : published) {
                if (element instanceof Map<?, ?> option) {
                    options.put(
                            UUID.fromString(String.valueOf(option.get("optionId"))),
                            // Absent reads as one rather than zero: a cap of zero
                            // would make an option nobody can choose out of one the
                            // menu offers.
                            intOf(option.get("maximumQuantity"), 1));
                }
            }
        }
        return new GroupRules(
                groupId,
                String.valueOf(content.get("code")),
                Boolean.TRUE.equals(content.get("required")),
                intOf(content.get("minimumSelections"), 0),
                // V0016 constrains maximum_selections >= 1, so there is no
                // "unlimited" sentinel to honour. A publication written without the
                // field is read as one, which is the column's own default.
                intOf(content.get("maximumSelections"), 1),
                Boolean.TRUE.equals(content.get("allowSameOptionMultipleTimes")),
                Map.copyOf(options));
    }

    private static int intOf(@Nullable Object raw, int whenAbsent) {
        return raw instanceof Number number ? number.intValue() : whenAbsent;
    }

    /**
     * A published list of identifier strings.
     *
     * <p>Absent on publications written before membership was carried. Those are
     * immutable and still served, so this reads as "no groups" rather than
     * refusing every line on an older menu.
     */
    private static List<UUID> idList(Map<String, Object> content, String key) {
        if (!(content.get(key) instanceof List<?> list)) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(list.size());
        list.forEach(value -> ids.add(UUID.fromString(String.valueOf(value))));
        return ids;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        return json == null ? Map.of() : objectMapper.readValue(json, Map.class);
    }

    private record ProductItem(UUID entityId, Map<String, Object> content) {}
}
