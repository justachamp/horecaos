package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.tenancy.api.BrandDisplayNames;

/** {@link BrandDisplayNames} over {@code tenant.brands}. */
@Component
class JdbcBrandDisplayNames implements BrandDisplayNames {

    private final JdbcClient jdbc;

    JdbcBrandDisplayNames(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> displayName(UUID tenantId, UUID brandId) {
        return jdbc.sql("SELECT display_name FROM tenant.brands WHERE tenant_id = :tenantId AND id = :brandId")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(String.class)
                .optional();
    }
}
