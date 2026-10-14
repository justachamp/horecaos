package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.tenancy.api.BrandContentFacts;

/** {@link BrandContentFacts} over {@code tenant.brands}, {@code tenant.tenants} and {@code tenant.channel_hostnames}. */
@Repository
public class JdbcBrandContentFacts implements BrandContentFacts {

    private static final ZoneId PLATFORM_ZONE = ZoneId.of("Asia/Tashkent");

    private final JdbcClient jdbc;

    public JdbcBrandContentFacts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean brandIsActive(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM tenant.brands
                               WHERE tenant_id = :tenantId AND id = :brandId AND status = 'ACTIVE')
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public boolean brandExists(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM tenant.brands WHERE tenant_id = :tenantId AND id = :brandId)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public ZoneId tenantZone(UUID tenantId) {
        return jdbc.sql("SELECT default_timezone FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .optional()
                .map(JdbcBrandContentFacts::zoneOrDefault)
                .orElse(PLATFORM_ZONE);
    }

    @Override
    public Set<String> verifiedChannelHostnames(UUID tenantId) {
        return new HashSet<>(
                jdbc.sql("""
                        SELECT hostname FROM tenant.channel_hostnames
                        WHERE tenant_id = :tenantId AND verified
                        """).param("tenantId", tenantId).query(String.class).list());
    }

    @Override
    public java.util.Optional<String> brandDisplayName(UUID tenantId, UUID brandId) {
        return jdbc.sql("SELECT display_name FROM tenant.brands WHERE tenant_id = :tenantId AND id = :brandId")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(String.class)
                .optional();
    }

    @Override
    public java.util.Optional<String> verifiedWebHostname(UUID tenantId, UUID channelId) {
        return jdbc.sql("""
                SELECT h.hostname
                FROM tenant.channel_hostnames h
                JOIN tenant.sales_channels c ON c.tenant_id = h.tenant_id AND c.id = h.channel_id
                WHERE h.tenant_id = :tenantId AND h.channel_id = :channelId AND h.verified
                  AND c.system_type = 'WEB' AND c.status = 'ACTIVE'
                ORDER BY h.hostname
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .query(String.class)
                .optional();
    }

    @Override
    public List<SiblingStorefront> siblingStorefronts(UUID tenantId, UUID excludingBrandId) {
        return jdbc.sql("""
                SELECT DISTINCT b.id AS brand_id, b.display_name AS brand_name, c.id AS channel_id, c.code AS channel_code,
                       (SELECT h.hostname
                          FROM tenant.channel_hostnames h
                         WHERE h.tenant_id = c.tenant_id AND h.channel_id = c.id AND h.verified
                         ORDER BY h.hostname
                         LIMIT 1) AS hostname
                FROM tenant.brands b
                JOIN tenant.locations l
                  ON l.tenant_id = b.tenant_id AND l.brand_id = b.id AND l.status = 'ACTIVE'
                JOIN tenant.sales_channel_locations cl
                  ON cl.tenant_id = l.tenant_id AND cl.location_id = l.id AND cl.status = 'ACTIVE'
                JOIN tenant.sales_channels c
                  ON c.tenant_id = cl.tenant_id AND c.id = cl.channel_id
                 AND c.system_type = 'WEB' AND c.status = 'ACTIVE'
                WHERE b.tenant_id = :tenantId AND b.id <> :excluded AND b.status = 'ACTIVE'
                ORDER BY b.display_name, c.code
                """)
                .param("tenantId", tenantId)
                .param("excluded", excludingBrandId)
                .query((row, number) -> new SiblingStorefront(
                        row.getObject("brand_id", UUID.class),
                        row.getString("brand_name"),
                        row.getObject("channel_id", UUID.class),
                        row.getString("channel_code"),
                        row.getString("hostname")))
                .list();
    }

    private static ZoneId zoneOrDefault(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException unknown) {
            return PLATFORM_ZONE;
        }
    }
}
