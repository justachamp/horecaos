package uz.horecaos.platform.catalog.infrastructure.tenancy;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.application.CatalogTenantContext;

/**
 * Reads {@code tenant.locations.timezone} directly, the same cross-schema-by-
 * raw-SQL shape {@code ordering.infrastructure.tenancy.JdbcOrderingTenantContext}
 * already uses for the identical fact: a plain-SQL read across schemas is not
 * a Java import across module boundaries, so this stays inside catalog's own
 * module without depending on tenancy's application or domain packages.
 *
 * <p>The query carries the tenant predicate. A location id is a UUID a client
 * supplies, and resolving a timezone from the id alone would let one tenant's
 * item-schedule save resolve against another tenant's branch clock.
 */
@Component
public class JdbcCatalogTenantContext implements CatalogTenantContext {

    private final JdbcClient jdbc;

    public JdbcCatalogTenantContext(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
