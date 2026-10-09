package uz.horecaos.platform.pricing.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.content.api.PromotionLiveLookup;

/**
 * {@link PromotionLiveLookup} over {@code pricing.promotions} (ADR 0157, Decision 5): whether the
 * promotions a promo card is bound to are in front of customers at an instant.
 *
 * <p>One indexed read ({@code ix_promotions_active}) however many promotions are asked about,
 * scoped to the tenant and the brand. "Live" is exactly what the pricing engine reads -- {@code
 * ACTIVE} and inside the window, start inclusive and end exclusive -- so a card cannot outlive the
 * promotion it advertises, and nothing consumes {@code PromotionActivated}/{@code
 * PromotionSuspended} to keep a copy that could drift.
 */
@Repository
public class JdbcPromotionLiveLookup implements PromotionLiveLookup {

    private final JdbcClient jdbc;

    public JdbcPromotionLiveLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<UUID> live(UUID tenantId, UUID brandId, Collection<UUID> promotionIds, Instant at) {
        if (promotionIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        SELECT id FROM pricing.promotions
                        WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = ANY(:ids)
                          AND status = 'ACTIVE'
                          AND valid_from <= :at AND (valid_until IS NULL OR :at < valid_until)
                        """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", promotionIds.toArray(UUID[]::new))
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(UUID.class)
                .list());
    }

    @Override
    public Set<UUID> existing(UUID tenantId, UUID brandId, Collection<UUID> promotionIds) {
        if (promotionIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        SELECT id FROM pricing.promotions
                        WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = ANY(:ids)
                        """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("ids", promotionIds.toArray(UUID[]::new))
                .query(UUID.class)
                .list());
    }
}
