package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.tenancy.api.StorefrontHostnames;

/**
 * {@link StorefrontHostnames} over {@code tenant.channel_hostnames}, {@code tenant.sales_channels},
 * {@code tenant.sales_channel_locations} and {@code tenant.locations} (ADR 0183 D27).
 *
 * <p>The tenant predicate is in every join, not only the first: a brand id is a UUID the caller may have received from
 * anywhere, and a lookup that matched on it alone would answer another tenant's storefront.
 */
@Repository
public class JdbcStorefrontHostnames implements StorefrontHostnames {

    private final JdbcClient jdbc;

    public JdbcStorefrontHostnames(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Resolution forBrand(UUID tenantId, UUID brandId) {
        List<Candidate> candidates = jdbc.sql("""
                SELECT DISTINCT h.hostname, h.verified
                FROM tenant.channel_hostnames h
                JOIN tenant.sales_channels c
                  ON c.tenant_id = h.tenant_id AND c.id = h.channel_id
                JOIN tenant.sales_channel_locations scl
                  ON scl.tenant_id = c.tenant_id AND scl.channel_id = c.id AND scl.status = 'ACTIVE'
                JOIN tenant.locations l
                  ON l.tenant_id = scl.tenant_id AND l.id = scl.location_id
                WHERE h.tenant_id = :tenantId
                  AND l.brand_id = :brandId
                  AND c.system_type = 'WEB'
                  AND c.status = 'ACTIVE'
                ORDER BY h.hostname
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> new Candidate(row.getString("hostname"), row.getBoolean("verified")))
                .list();

        if (candidates.isEmpty()) {
            return Resolution.missing(Reason.NOT_CLAIMED);
        }
        List<Candidate> verified =
                candidates.stream().filter(Candidate::verified).toList();
        if (verified.isEmpty()) {
            return Resolution.missing(Reason.NOT_VERIFIED);
        }
        return verified.size() == 1
                ? Resolution.of(verified.getFirst().hostname())
                : Resolution.missing(Reason.AMBIGUOUS);
    }

    private record Candidate(String hostname, boolean verified) {}
}
