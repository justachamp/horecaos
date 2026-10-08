package uz.horecaos.platform.loyalty.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.loyalty.api.AccrualRuleReferencePort;

/**
 * {@link AccrualRuleReferencePort} over {@code loyalty.accrual_rules}: existence and
 * status, scoped to the tenant and the brand (ADR 0112).
 */
@Repository
public class JdbcAccrualRuleReferenceAdapter implements AccrualRuleReferencePort {

    private final JdbcClient jdbc;

    public JdbcAccrualRuleReferenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AccrualRuleReference> find(UUID tenantId, UUID brandId, UUID accrualRuleId) {
        return jdbc.sql("""
                SELECT id, status FROM loyalty.accrual_rules
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", accrualRuleId)
                .query((row, number) ->
                        new AccrualRuleReference(row.getObject("id", UUID.class), row.getString("status")))
                .optional();
    }
}
