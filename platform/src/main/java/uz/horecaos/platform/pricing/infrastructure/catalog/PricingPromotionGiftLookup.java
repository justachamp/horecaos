package uz.horecaos.platform.pricing.infrastructure.catalog;

import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.PromotionGiftLookup;

/**
 * The gift variants of a brand's active promotions (ADR 0158, ADR 0140).
 *
 * <p>Read from the promotion's action operands, not from the published menu: the question is what the
 * promotion would give away, and an active promotion can be edited between publications.
 */
@Component
public class PricingPromotionGiftLookup implements PromotionGiftLookup {

    private final JdbcClient jdbc;

    public PricingPromotionGiftLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<UUID> giftVariantIds(UUID tenantId, UUID brandId) {
        // A CASE around the array, because the planner may run the set-returning function before it
        // applies the action-type filter, and an action of another type has no variantIds to expand.
        return Set.copyOf(jdbc.sql("""
                        SELECT DISTINCT CAST(gift.value AS uuid)
                        FROM pricing.promotion_actions action
                        JOIN pricing.promotions promotion
                          ON promotion.id = action.promotion_id
                         AND promotion.tenant_id = action.tenant_id
                         AND promotion.brand_id = action.brand_id
                        CROSS JOIN LATERAL jsonb_array_elements_text(
                            CASE WHEN jsonb_typeof(action.attributes_json -> 'variantIds') = 'array'
                                 THEN action.attributes_json -> 'variantIds'
                                 ELSE '[]'::jsonb END) AS gift(value)
                        WHERE promotion.tenant_id = :tenantId
                          AND promotion.brand_id = :brandId
                          AND promotion.status = 'ACTIVE'
                          AND action.action_type = 'FREE_ITEM'
                        """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(UUID.class)
                .list());
    }
}
