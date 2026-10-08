package uz.horecaos.platform.pricing.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.pricing.api.PromotionReferencePort;

/**
 * {@link PromotionReferencePort} over {@code pricing.promotions}: existence, scoped to the
 * tenant and the brand, and nothing of the rule (ADR 0112).
 */
@Repository
public class JdbcPromotionReferenceAdapter implements PromotionReferencePort {

    private final JdbcClient jdbc;

    public JdbcPromotionReferenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PromotionReference> find(UUID tenantId, UUID brandId, UUID promotionId) {
        return jdbc.sql("""
                SELECT id, code, name, status FROM pricing.promotions
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", promotionId)
                .query((row, number) -> new PromotionReference(
                        row.getObject("id", UUID.class),
                        row.getString("code"),
                        row.getString("name"),
                        row.getString("status")))
                .optional();
    }
}
