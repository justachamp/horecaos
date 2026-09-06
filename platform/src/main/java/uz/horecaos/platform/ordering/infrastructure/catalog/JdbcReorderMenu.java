package uz.horecaos.platform.ordering.infrastructure.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.ordering.application.ReorderMenu;

/**
 * Resolves an old order's variant ids against the live publication and this
 * location's offering rows (ADR 0074).
 *
 * <p>Reads the publication and never {@code catalog.products} or {@code
 * catalog.variants}, for the reason {@link JdbcCartMenuRules} gives: the
 * authoring rows are edited while a menu is live, and a repeat offered against
 * a draft is a repeat that fails at pricing.
 *
 * <p>Offerings <em>are</em> read live, and deliberately so — that is where 86'ing
 * a dish takes effect, and a repeat button that ignores the stop list is the one
 * failure this whole endpoint exists to prevent.
 *
 * <p>Four queries regardless of how many lines the order has. The variant set is
 * matched inside one publication scan rather than one lookup per line, because an
 * order of eight lines would otherwise be sixteen round trips to answer a yes/no
 * a customer is waiting on.
 */
@Component
public class JdbcReorderMenu implements ReorderMenu {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcReorderMenu(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Snapshot at(UUID tenantId, UUID brandId, UUID locationId, String channelCode, Set<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return new Snapshot(Map.of());
        }

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
            // The brand has never published on this channel, or has retired the
            // only publication it had. Nothing is offered, so nothing is
            // repeatable — an empty snapshot rather than an exception, because
            // "no menu" is an answer a customer's screen can render.
            return new Snapshot(Map.of());
        }

        // Which of this location's variants can be sold at all. HIDDEN is filtered
        // here rather than reported, matching StorefrontCatalogQuery: a hidden
        // offering is not a sold-out dish, it is a dish this location does not
        // have on its menu.
        Map<UUID, String> offeringStatus = offeringStatuses(tenantId, locationId, variantIds);
        if (offeringStatus.isEmpty()) {
            return new Snapshot(Map.of());
        }

        List<ProductItem> products = productsCarrying(publicationId.get(), offeringStatus.keySet());
        if (products.isEmpty()) {
            return new Snapshot(Map.of());
        }

        Set<UUID> groupIds = new LinkedHashSet<>();
        products.forEach(product -> groupIds.addAll(idList(product.content(), "modifierGroupIds")));
        Map<UUID, Set<UUID>> optionsByGroup = optionsOfGroups(publicationId.get(), groupIds);

        Map<UUID, VariantOffer> offers = new HashMap<>();
        for (ProductItem product : products) {
            Set<UUID> offeredOptions = new LinkedHashSet<>();
            for (UUID groupId : idList(product.content(), "modifierGroupIds")) {
                offeredOptions.addAll(optionsByGroup.getOrDefault(groupId, Set.of()));
            }
            for (UUID variantId : publishedVariantsOf(product.content())) {
                String status = offeringStatus.get(variantId);
                if (status == null) {
                    // Published by the brand, not offered by this location.
                    continue;
                }
                offers.put(
                        variantId,
                        new VariantOffer(product.entityId(), "UNAVAILABLE".equals(status), Set.copyOf(offeredOptions)));
            }
        }
        return new Snapshot(offers);
    }

    /** Sellable offering rows for the asked-about variants, HIDDEN excluded. */
    private Map<UUID, String> offeringStatuses(UUID tenantId, UUID locationId, Set<UUID> variantIds) {
        Map<UUID, String> byVariant = new HashMap<>();
        jdbc.sql("""
                SELECT variant_id, status
                FROM catalog.location_offerings
                WHERE tenant_id = :tenantId AND location_id = :locationId
                  AND variant_id = ANY(:variantIds) AND status <> 'HIDDEN'
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("variantIds", variantIds.toArray(UUID[]::new))
                .query((row, number) -> Map.entry(
                        java.util.Objects.requireNonNull(row.getObject("variant_id", UUID.class)),
                        java.util.Objects.requireNonNull(row.getString("status"))))
                .list()
                .forEach(entry -> byVariant.put(entry.getKey(), entry.getValue()));
        return byVariant;
    }

    /**
     * The published products carrying any of these variants.
     *
     * <p>Matched on the id as text, the same assumption {@link JdbcCartMenuRules}
     * already makes when it matches a single variant by containment: the
     * publication loader writes {@code UUID#toString}, so both sides are the
     * canonical lower-case form. The {@code jsonb_typeof} guard is not
     * defensiveness about that — it is because {@code jsonb_array_elements}
     * raises on a non-array, and one hand-written publication item would
     * otherwise fail every repeat in the brand.
     */
    private List<ProductItem> productsCarrying(UUID publicationId, Set<UUID> variantIds) {
        String[] ids = variantIds.stream().map(UUID::toString).toArray(String[]::new);
        return jdbc.sql("""
                SELECT entity_id, immutable_content_json::text AS content
                FROM catalog.publication_items pi
                WHERE pi.publication_id = :publicationId
                  AND pi.entity_type = 'PRODUCT'
                  AND EXISTS (
                      SELECT 1
                      FROM jsonb_array_elements(
                               CASE WHEN jsonb_typeof(pi.immutable_content_json -> 'variants') = 'array'
                                    THEN pi.immutable_content_json -> 'variants'
                                    ELSE '[]'::jsonb END) AS variant
                      WHERE variant ->> 'variantId' = ANY(:variantIds)
                  )
                """)
                .param("publicationId", publicationId)
                .param("variantIds", ids)
                .query((row, number) ->
                        new ProductItem(row.getObject("entity_id", UUID.class), readJson(row.getString("content"))))
                .list();
    }

    private Map<UUID, Set<UUID>> optionsOfGroups(UUID publicationId, Set<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> byGroup = new HashMap<>();
        jdbc.sql("""
                SELECT entity_id, immutable_content_json::text AS content
                FROM catalog.publication_items
                WHERE publication_id = :publicationId
                  AND entity_type = 'MODIFIER_GROUP'
                  AND entity_id = ANY(:ids)
                """)
                .param("publicationId", publicationId)
                .param("ids", groupIds.toArray(UUID[]::new))
                .query((row, number) -> Map.entry(
                        java.util.Objects.requireNonNull(row.getObject("entity_id", UUID.class)),
                        optionIdsOf(readJson(row.getString("content")))))
                .list()
                .forEach(entry -> byGroup.put(entry.getKey(), entry.getValue()));
        return byGroup;
    }

    private static Set<UUID> optionIdsOf(Map<String, Object> content) {
        if (!(content.get("options") instanceof List<?> published)) {
            return Set.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (Object element : published) {
            if (element instanceof Map<?, ?> option) {
                ids.add(UUID.fromString(String.valueOf(option.get("optionId"))));
            }
        }
        return ids;
    }

    private static Set<UUID> publishedVariantsOf(Map<String, Object> content) {
        if (!(content.get("variants") instanceof List<?> published)) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>();
        for (Object element : published) {
            if (element instanceof Map<?, ?> variant) {
                ids.add(UUID.fromString(String.valueOf(variant.get("variantId"))));
            }
        }
        return ids;
    }

    /** A published list of identifier strings, absent on older publications. */
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
