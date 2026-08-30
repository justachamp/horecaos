package uz.qoida.platform.pricing.infrastructure.catalog;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import uz.qoida.platform.pricing.application.MenuMembershipLookup;

/**
 * Reads variant membership from the catalog schema (ADR 0018).
 *
 * <p>One query for the whole cart rather than one per line: a promotion is
 * evaluated on every pricing call, and a per-line lookup would put a round trip
 * per basket item on the hot path of the checkout screen.
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

        Map<UUID, Membership> membership = new HashMap<>();
        productByVariant.forEach((variantId, productId) -> membership.put(variantId,
                new Membership(productId, categoriesByProduct.getOrDefault(productId, Set.of()))));
        return Map.copyOf(membership);
    }
}
