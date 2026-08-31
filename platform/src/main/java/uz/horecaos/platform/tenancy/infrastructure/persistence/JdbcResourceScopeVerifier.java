package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.Objects;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScopeVerifier;

/**
 * Answers {@link ResourceScopeVerifier} from the tenancy tables.
 *
 * <p>Both reads are single-row lookups against a unique constraint that V0003
 * already declares for exactly this shape — {@code uq_brands_tenant_id_id} and
 * {@code uq_locations_tenant_brand_id}. They exist because the composite
 * foreign keys elsewhere in the schema reference them, so this check costs an
 * index probe rather than a scan.
 */
@Component
public class JdbcResourceScopeVerifier implements ResourceScopeVerifier {

    private final JdbcClient jdbc;

    public JdbcResourceScopeVerifier(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(ResourceScope scope) {
        // ResourceScope's own compact constructor guarantees tenantId/brandId/locationId
        // are set for exactly the scope types that read them below; requireNonNull here
        // documents that invariant for NullAway rather than distrusting it.
        return switch (scope.type()) {
            // No identifiers to verify. Whether anyone may act at this level is a
            // capability question, and it is asked immediately after this one.
            case PLATFORM -> true;
            case TENANT -> tenantExists(Objects.requireNonNull(scope.tenantId()));
            case BRAND -> brandExists(Objects.requireNonNull(scope.tenantId()), Objects.requireNonNull(scope.brandId()));
            case LOCATION -> locationExists(
                    Objects.requireNonNull(scope.tenantId()),
                    Objects.requireNonNull(scope.brandId()),
                    Objects.requireNonNull(scope.locationId()));
        };
    }

    /**
     * Cached positively only.
     *
     * <p>{@code unless} keeps false out of the cache: a false answer means the
     * request named a hierarchy that does not exist, which is either a bug or an
     * attempt, and caching those would let a caller fill a 50,000-entry map with
     * identifiers they invented. A true answer is safe to keep because a brand's
     * tenant never changes — the only staleness is a brand created seconds ago,
     * which simply misses.
     */
    @Cacheable(cacheNames = "tenant.hierarchy", key = "'t:' + #tenantId", unless = "!#result")
    public boolean tenantExists(UUID tenantId) {
        return jdbc.sql("SELECT 1 FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    @Cacheable(cacheNames = "tenant.hierarchy", key = "'b:' + #tenantId + ':' + #brandId", unless = "!#result")
    public boolean brandExists(UUID tenantId, UUID brandId) {
        return jdbc.sql("""
                SELECT 1 FROM tenant.brands WHERE tenant_id = :tenantId AND id = :brandId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    @Cacheable(
            cacheNames = "tenant.hierarchy",
            key = "'l:' + #tenantId + ':' + #brandId + ':' + #locationId",
            unless = "!#result")
    public boolean locationExists(UUID tenantId, UUID brandId, UUID locationId) {
        return jdbc.sql("""
                SELECT 1 FROM tenant.locations
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :locationId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }
}
