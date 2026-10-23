package uz.horecaos.platform.pricing.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
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

    @Override
    public List<GrantListing> forCustomer(UUID tenantId, UUID brandId, UUID customerAccountId) {
        return jdbc.sql("""
                SELECT id, benefit_type, value, currency, status, source_type, source_id, code_hint,
                       created_at, expires_at
                  FROM pricing.benefit_grants
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND customer_account_id = :customerId
                 ORDER BY created_at DESC, id
                 LIMIT 50
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("customerId", customerAccountId)
                .query((rs, n) -> new GrantListing(
                        rs.getObject("id", UUID.class),
                        rs.getString("benefit_type"),
                        rs.getLong("value"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getString("source_type"),
                        rs.getObject("source_id", UUID.class),
                        rs.getString("code_hint"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at") == null
                                ? null
                                : rs.getObject("expires_at", OffsetDateTime.class)
                                        .toInstant()))
                .list();
    }
}
