package uz.horecaos.platform.ordering.infrastructure.tenancy;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.application.OrderingTenantContext;

/**
 * Reads the tenant currency and the branch timezone (ADR 0019).
 *
 * <p>Both queries carry the tenant predicate. A location id is a UUID a client
 * supplies, and resolving a timezone from the id alone would let another
 * tenant's branch decide this order's business date.
 */
@Component
public class JdbcOrderingTenantContext implements OrderingTenantContext {

    private final JdbcClient jdbc;

    public JdbcOrderingTenantContext(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> defaultCurrency(UUID tenantId) {
        return jdbc.sql("SELECT default_currency FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<ZoneId> timezoneOf(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT timezone FROM tenant.locations
                WHERE tenant_id = :tenantId AND id = :locationId
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(String.class)
                .optional()
                .map(ZoneId::of);
    }
}
