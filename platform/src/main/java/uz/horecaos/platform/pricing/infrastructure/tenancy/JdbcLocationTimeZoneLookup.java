package uz.horecaos.platform.pricing.infrastructure.tenancy;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.pricing.application.LocationTimeZoneLookup;

/**
 * Reads the zone from {@code tenant.locations.timezone}, the way payments reads the
 * branch's business date (ADR 0013): pricing reads tenancy <em>tables</em> and never
 * imports tenancy <em>types</em> for a fact this small.
 */
@Component
public class JdbcLocationTimeZoneLookup implements LocationTimeZoneLookup {

    private final JdbcClient jdbc;

    public JdbcLocationTimeZoneLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ZoneId zoneOf(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT timezone FROM tenant.locations
                WHERE tenant_id = :tenantId AND id = :locationId
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(String.class)
                .optional()
                .map(JdbcLocationTimeZoneLookup::zoneOrUtc)
                .orElse(ZoneOffset.UTC);
    }

    private static ZoneId zoneOrUtc(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException unknownZone) {
            return ZoneOffset.UTC;
        }
    }
}
