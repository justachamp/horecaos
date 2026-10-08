package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashSet;
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

    private static ZoneId zoneOrDefault(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException unknown) {
            return PLATFORM_ZONE;
        }
    }
}
