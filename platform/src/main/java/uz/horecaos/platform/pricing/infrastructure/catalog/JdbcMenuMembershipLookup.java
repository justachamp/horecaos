package uz.horecaos.platform.pricing.infrastructure.catalog;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.pricing.application.MenuMembershipLookup;

/**
 * Reads variant membership from the catalog schema (ADR 0018).
 *
 * <p>One query for the whole cart rather than one per line: a promotion is
 * evaluated on every pricing call, and a per-line lookup would put a round trip
 * per basket item on the hot path of the checkout screen. A second walks the
 * category ancestry (ADR 0140), so a category condition matches every product
 * beneath it.
 */
@Component
public class JdbcMenuMembershipLookup implements MenuMembershipLookup {

    private final JdbcClient jdbc;

    public JdbcMenuMembershipLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, Membership> membershipOf(UUID tenantId, UUID brandId, Set<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, UUID> productByVariant = new HashMap<>();
        Map<UUID, Set<UUID>> categoriesByProduct = new HashMap<>();

        // The tenant and the brand are both in the predicate. A variant id from
        // another brand would otherwise resolve to a real product and let one
        // tenant's promotion match another tenant's line.
        //
        // LEFT JOIN because a product in no category is ordinary, and an inner
        // join would drop its variant from the result entirely -- which reads
        // downstream as "this brand does not own that variant".
        jdbc.sql("""
                SELECT v.id AS variant_id, v.product_id, cp.category_id
                FROM catalog.variants v
                LEFT JOIN catalog.category_products cp
                       ON cp.product_id = v.product_id
                      AND cp.tenant_id = v.tenant_id
                      AND cp.brand_id = v.brand_id
                WHERE v.tenant_id = :tenantId AND v.brand_id = :brandId
                  AND v.id = ANY(:variantIds)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("variantIds", variantIds.toArray(UUID[]::new))
                .query((row, number) -> {
                    UUID variantId = row.getObject("variant_id", UUID.class);
                    UUID productId = row.getObject("product_id", UUID.class);
                    UUID categoryId = row.getObject("category_id", UUID.class);
                    productByVariant.put(variantId, productId);
                    if (categoryId != null) {
                        categoriesByProduct
                                .computeIfAbsent(productId, key -> new HashSet<>())
                                .add(categoryId);
                    }
                    return variantId;
                })
                .list();

        // ADR 0140: a promotion on "Pizza" matches a Margherita that sits in
        // "Pizza > Classic", so every direct category brings its ancestors with
        // it. Walked in one recursive query for the whole cart, bounded to this
        // tenant and brand like the read above.
        Set<UUID> direct = new HashSet<>();
        categoriesByProduct.values().forEach(direct::addAll);
        Map<UUID, Set<UUID>> ancestry = ancestorsOf(tenantId, brandId, direct);

        Map<UUID, Membership> membership = new HashMap<>();
        productByVariant.forEach((variantId, productId) -> {
            Set<UUID> categories = new HashSet<>();
            for (UUID category : categoriesByProduct.getOrDefault(productId, Set.of())) {
                categories.add(category);
                categories.addAll(ancestry.getOrDefault(category, Set.of()));
            }
            membership.put(variantId, new Membership(productId, categories));
        });
        return Map.copyOf(membership);
    }

    /** For each starting category, every category above it in the same brand. */
    private Map<UUID, Set<UUID>> ancestorsOf(UUID tenantId, UUID brandId, Set<UUID> startingCategories) {
        if (startingCategories.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> parentOf = new HashMap<>();
        jdbc.sql("""
                WITH RECURSIVE chain AS (
                    SELECT id, parent_category_id
                    FROM catalog.categories
                    WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = ANY(:ids)
                  UNION
                    SELECT c.id, c.parent_category_id
                    FROM catalog.categories c
                    JOIN chain ON c.id = chain.parent_category_id
                    WHERE c.tenant_id = :tenantId AND c.brand_id = :brandId
                )
                SELECT id, parent_category_id FROM chain
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", startingCategories.toArray(UUID[]::new))
                .query((row, number) -> {
                    UUID parent = row.getObject("parent_category_id", UUID.class);
                    if (parent != null) {
                        parentOf.put(row.getObject("id", UUID.class), parent);
                    }
                    return 0;
                })
                .list();

        Map<UUID, Set<UUID>> ancestry = new HashMap<>();
        for (UUID start : startingCategories) {
            Set<UUID> above = new HashSet<>();
            UUID cursor = parentOf.get(start);
            // The set guards a cycle: the schema refuses a self-parent and the
            // validator walks the rest, but this loop must terminate on any data.
            while (cursor != null && above.add(cursor)) {
                cursor = parentOf.get(cursor);
            }
            ancestry.put(start, above);
        }
        return ancestry;
    }
}
