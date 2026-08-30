package uz.qoida.platform.ordering.infrastructure.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import uz.qoida.platform.ordering.application.CartMenuRules;

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
    public Optional<ProductRules> forVariant(UUID tenantId, UUID brandId, String channelCode,
            UUID variantId) {

        Optional<UUID> publicationId = jdbc.sql("""
                SELECT id FROM catalog.publications
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND channel = :channel AND status = 'PUBLISHED'
                """)
                .param("tenantId", tenantId).param("brandId", brandId)
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
                .query((row, number) -> new ProductItem(
                        row.getObject("entity_id", UUID.class),
                        readJson(row.getString("content"))))
                .optional();
        if (product.isEmpty()) {
            return Optional.empty();
        }

        List<UUID> groupIds = idList(product.get().content(), "modifierGroupIds");
        if (groupIds.isEmpty()) {
            return Optional.of(new ProductRules(product.get().entityId(), List.of()));
        }
        return Optional.of(new ProductRules(product.get().entityId(),
                groups(publicationId.get(), groupIds)));
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
                .query((row, number) -> toGroup(
                        row.getObject("entity_id", UUID.class),
                        readJson(row.getString("content"))))
                .list();
    }

    private static GroupRules toGroup(UUID groupId, Map<String, Object> content) {
        Map<UUID, Integer> options = new LinkedHashMap<>();
        if (content.get("options") instanceof List<?> published) {
            for (Object element : published) {
                if (element instanceof Map<?, ?> option) {
                    options.put(UUID.fromString(String.valueOf(option.get("optionId"))),
                            // Absent reads as one rather than zero: a cap of zero
                            // would make an option nobody can choose out of one the
                            // menu offers.
                            intOf(option.get("maximumQuantity"), 1));
                }
            }
        }
        return new GroupRules(groupId,
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

    private static int intOf(Object raw, int whenAbsent) {
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

    private record ProductItem(UUID entityId, Map<String, Object> content) { }
}
