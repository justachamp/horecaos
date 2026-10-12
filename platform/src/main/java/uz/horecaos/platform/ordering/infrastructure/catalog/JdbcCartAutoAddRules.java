package uz.horecaos.platform.ordering.infrastructure.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
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
import uz.horecaos.platform.ordering.application.CartAutoAddRules;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule.Band;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule.Kind;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule.Mode;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule.Trigger;

/**
 * Reads the auto-add rules of a channel's live publication (ADR 0158), the way {@link JdbcCartMenuRules}
 * reads modifier rules: only the publication, never the authoring tables, and every query carries the
 * tenant and the brand through the publication row.
 */
@Component
public class JdbcCartAutoAddRules implements CartAutoAddRules {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcCartAutoAddRules(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<PublishedAutoAddRule> rulesFor(UUID tenantId, UUID brandId, String channelCode) {
        Optional<UUID> publicationId = livePublication(tenantId, brandId, channelCode);
        if (publicationId.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT entity_id, entity_version, immutable_content_json::text AS content
                FROM catalog.publication_items
                WHERE publication_id = :publicationId AND entity_type = 'AUTO_ADD_RULE'
                ORDER BY entity_id
                """)
                .param("publicationId", publicationId.get())
                .query((row, number) -> toRule(
                        row.getObject("entity_id", UUID.class),
                        row.getInt("entity_version"),
                        readJson(row.getString("content"))))
                .list();
    }

    @Override
    public Map<UUID, VariantFacts> variantFacts(
            UUID tenantId, UUID brandId, String channelCode, Collection<UUID> variantIds) {
        Map<UUID, VariantFacts> facts = new LinkedHashMap<>();
        if (variantIds.isEmpty()) {
            return facts;
        }
        Optional<UUID> publicationId = livePublication(tenantId, brandId, channelCode);
        if (publicationId.isEmpty()) {
            return facts;
        }
        record Placement(UUID productId, @Nullable BigDecimal portionsPerUnit) {}
        Map<UUID, Placement> placement = new LinkedHashMap<>();
        // A CASE around the array, because the planner is free to run the set-returning function
        // before it applies the entity_type filter, and a PRODUCT item published without variants
        // must not turn the whole read into an error.
        jdbc.sql("""
                SELECT variant.value ->> 'variantId' AS variant_id,
                       item.entity_id AS product_id,
                       variant.value -> 'physical' ->> 'portionsPerUnit' AS portions
                FROM catalog.publication_items item
                CROSS JOIN LATERAL jsonb_array_elements(
                    CASE WHEN jsonb_typeof(item.immutable_content_json -> 'variants') = 'array'
                         THEN item.immutable_content_json -> 'variants'
                         ELSE '[]'::jsonb END) AS variant(value)
                WHERE item.publication_id = :publicationId
                  AND item.entity_type = 'PRODUCT'
                  AND variant.value ->> 'variantId' = ANY(:ids)
                """)
                .param("publicationId", publicationId.get())
                .param("ids", variantIds.stream().map(UUID::toString).toArray(String[]::new))
                .query(row -> {
                    String portions = row.getString("portions");
                    placement.put(
                            UUID.fromString(row.getString("variant_id")),
                            new Placement(
                                    row.getObject("product_id", UUID.class),
                                    portions == null ? null : new BigDecimal(portions)));
                });
        if (placement.isEmpty()) {
            return facts;
        }
        Map<UUID, Set<UUID>> categoriesByProduct = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT item.entity_id AS category_id, product.value AS product_id
                FROM catalog.publication_items item
                CROSS JOIN LATERAL jsonb_array_elements_text(
                    CASE WHEN jsonb_typeof(item.immutable_content_json -> 'productIds') = 'array'
                         THEN item.immutable_content_json -> 'productIds'
                         ELSE '[]'::jsonb END) AS product(value)
                WHERE item.publication_id = :publicationId
                  AND item.entity_type = 'CATEGORY'
                  AND product.value = ANY(:productIds)
                """)
                .param("publicationId", publicationId.get())
                .param(
                        "productIds",
                        placement.values().stream()
                                .map(found -> found.productId().toString())
                                .distinct()
                                .toArray(String[]::new))
                .query(row -> {
                    categoriesByProduct
                            .computeIfAbsent(UUID.fromString(row.getString("product_id")), id -> new LinkedHashSet<>())
                            .add(row.getObject("category_id", UUID.class));
                });
        placement.forEach((variantId, found) -> facts.put(
                variantId,
                new VariantFacts(
                        found.productId(),
                        categoriesByProduct.getOrDefault(found.productId(), Set.of()),
                        found.portionsPerUnit())));
        return facts;
    }

    @Override
    public Map<UUID, ComboComponentFacts> comboComponents(
            UUID tenantId, UUID brandId, String channelCode, Collection<UUID> componentIds) {
        Map<UUID, ComboComponentFacts> components = new LinkedHashMap<>();
        if (componentIds.isEmpty()) {
            return components;
        }
        Optional<UUID> publicationId = livePublication(tenantId, brandId, channelCode);
        if (publicationId.isEmpty()) {
            return components;
        }
        jdbc.sql("""
                SELECT component.value ->> 'componentId' AS component_id,
                       component.value ->> 'variantId' AS variant_id,
                       COALESCE((component.value ->> 'defaultQuantity')::int, 1) AS default_quantity
                FROM catalog.publication_items item
                CROSS JOIN LATERAL jsonb_array_elements(
                    CASE WHEN jsonb_typeof(item.immutable_content_json -> 'components') = 'array'
                         THEN item.immutable_content_json -> 'components'
                         ELSE '[]'::jsonb END) AS component(value)
                WHERE item.publication_id = :publicationId
                  AND item.entity_type = 'COMBO_GROUP'
                  AND component.value ->> 'componentId' = ANY(:ids)
                """)
                .param("publicationId", publicationId.get())
                .param("ids", componentIds.stream().map(UUID::toString).toArray(String[]::new))
                .query(row -> {
                    components.put(
                            UUID.fromString(row.getString("component_id")),
                            new ComboComponentFacts(
                                    UUID.fromString(row.getString("variant_id")), row.getInt("default_quantity")));
                });
        return components;
    }

    // ------------------------------------------------------------------------------ internals

    private Optional<UUID> livePublication(UUID tenantId, UUID brandId, String channelCode) {
        return jdbc.sql("""
                SELECT id FROM catalog.publications
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                  AND channel = :channel AND status = 'PUBLISHED'
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("channel", channelCode)
                .query(UUID.class)
                .optional();
    }

    private static PublishedAutoAddRule toRule(UUID ruleId, int version, Map<String, Object> content) {
        List<Trigger> triggers = new ArrayList<>();
        if (content.get("triggers") instanceof List<?> published) {
            for (Object element : published) {
                if (element instanceof Map<?, ?> trigger) {
                    triggers.add(new Trigger(
                            idOf(trigger.get("variantId")),
                            idOf(trigger.get("productId")),
                            idOf(trigger.get("categoryId"))));
                }
            }
        }
        List<Band> bands = new ArrayList<>();
        if (content.get("bands") instanceof List<?> published) {
            for (Object element : published) {
                if (element instanceof Map<?, ?> band) {
                    bands.add(new Band(
                            decimalOf(band.get("portionsFrom")),
                            band.get("portionsTo") == null ? null : decimalOf(band.get("portionsTo")),
                            UUID.fromString(String.valueOf(band.get("addVariantId"))),
                            decimalOf(band.get("quantity"))));
                }
            }
        }
        Map<String, String> customerText = new LinkedHashMap<>();
        if (content.get("names") instanceof Map<?, ?> names) {
            names.forEach((locale, entry) -> {
                if (entry instanceof Map<?, ?> named && named.get("name") != null) {
                    customerText.put(String.valueOf(locale), String.valueOf(named.get("name")));
                }
            });
        }
        return new PublishedAutoAddRule(
                ruleId,
                version,
                String.valueOf(content.get("code")),
                Kind.valueOf(String.valueOf(content.get("kind"))),
                Mode.valueOf(String.valueOf(content.getOrDefault("mode", "INJECT"))),
                idOf(content.get("addVariantId")),
                content.get("quantity") == null ? null : decimalOf(content.get("quantity")),
                content.get("triggerThreshold") == null ? null : decimalOf(content.get("triggerThreshold")),
                Boolean.TRUE.equals(content.get("repeatPerMultiple")),
                decimalOf(content.getOrDefault("minimumQuantity", "1")),
                decimalOf(content.getOrDefault("maximumQuantity", "20")),
                Boolean.TRUE.equals(content.get("mandatory")),
                strings(content.get("fulfillmentModes")),
                strings(content.get("channelSystemTypes")),
                ids(content.get("locationIds")),
                triggers,
                bands,
                customerText);
    }

    private static @Nullable UUID idOf(@Nullable Object raw) {
        return raw == null ? null : UUID.fromString(String.valueOf(raw));
    }

    private static BigDecimal decimalOf(@Nullable Object raw) {
        return new BigDecimal(String.valueOf(raw));
    }

    private static Set<String> strings(@Nullable Object raw) {
        Set<String> values = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            list.forEach(value -> values.add(String.valueOf(value)));
        }
        return values;
    }

    private static Set<UUID> ids(@Nullable Object raw) {
        Set<UUID> values = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            list.forEach(value -> values.add(UUID.fromString(String.valueOf(value))));
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(@Nullable String json) {
        return json == null ? Map.of() : objectMapper.readValue(json, Map.class);
    }
}
