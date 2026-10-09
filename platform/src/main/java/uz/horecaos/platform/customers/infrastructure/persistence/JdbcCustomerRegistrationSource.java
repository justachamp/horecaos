package uz.horecaos.platform.customers.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.CustomerRegistrationSource;

/**
 * {@link CustomerRegistrationSource} over {@code customer.brand_profiles} joined to the
 * account's {@code origin} (ADR 0167, row 7.6c).
 *
 * <p>The query returns a brand, an origin and a count. It selects no account id and no contact
 * point, so nothing that could name a person leaves the module through this seam.
 */
@Service
public class JdbcCustomerRegistrationSource implements CustomerRegistrationSource {

    private final JdbcClient jdbc;

    public JdbcCustomerRegistrationSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RegistrationCount> registeredBetween(UUID tenantId, Instant fromInclusive, Instant toExclusive) {
        return jdbc.sql("""
                SELECT p.brand_id, a.origin, count(*) AS registered
                  FROM customer.brand_profiles p
                  JOIN customer.customer_accounts a
                    ON a.id = p.customer_account_id AND a.tenant_id = p.tenant_id
                 WHERE p.tenant_id = :tenantId
                   AND p.created_at >= :from AND p.created_at < :to
                 GROUP BY p.brand_id, a.origin
                 ORDER BY p.brand_id, a.origin
                """)
                .param("tenantId", tenantId)
                .param("from", OffsetDateTime.ofInstant(fromInclusive, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC))
                .query((row, number) -> new RegistrationCount(
                        row.getObject("brand_id", UUID.class), row.getString("origin"), row.getInt("registered")))
                .list();
    }
}
