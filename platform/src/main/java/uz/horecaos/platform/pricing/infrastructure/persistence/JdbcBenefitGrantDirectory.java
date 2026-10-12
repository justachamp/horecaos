package uz.horecaos.platform.pricing.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.pricing.api.BenefitGrantDirectory;

/**
 * {@link BenefitGrantDirectory} over {@code pricing.benefit_grants} (V0265, ADR 0159).
 *
 * <p>Answers whose a grant is and nothing more: never the code (stored only as a hash and shown once, at
 * the mint), never the discount's value, never its redemption. Every read names the tenant, so a grant of
 * another tenant is absent, the same answer as "does not exist".
 */
@Repository
class JdbcBenefitGrantDirectory implements BenefitGrantDirectory {

    private final JdbcClient jdbc;

    JdbcBenefitGrantDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<GrantSummary> summary(UUID tenantId, UUID grantId) {
        return jdbc.sql("""
                SELECT id, brand_id, customer_account_id, status, source_type, source_id
                  FROM pricing.benefit_grants
                 WHERE tenant_id = :tenantId AND id = :grantId
                """)
                .param("tenantId", tenantId)
                .param("grantId", grantId)
                .query((rs, n) -> new GrantSummary(
                        rs.getObject("id", UUID.class),
                        rs.getObject("brand_id", UUID.class),
                        rs.getObject("customer_account_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("source_type"),
                        rs.getObject("source_id", UUID.class)))
                .optional();
    }
}
