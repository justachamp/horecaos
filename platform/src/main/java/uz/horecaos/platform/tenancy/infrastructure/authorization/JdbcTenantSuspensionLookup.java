package uz.horecaos.platform.tenancy.infrastructure.authorization;

import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.TenantSuspensionLookup;
import uz.horecaos.platform.tenancy.application.port.TenantStatusCache;

/**
 * Tenancy's answer to {@link TenantSuspensionLookup}.
 *
 * <p>Reads {@code tenant.tenants.status} and says only whether it is
 * {@code SUSPENDED}. It deliberately does not report {@code ARCHIVED} as
 * suspended: an archived tenant is finished rather than paused, and whether its
 * people keep working is a retention question this platform has not answered
 * yet — see the ADR. Answering it here by implication would settle it in the
 * one place nobody would look.
 */
@Component
public class JdbcTenantSuspensionLookup implements TenantSuspensionLookup, TenantStatusCache {

    /** ADR 0033. Same thirty seconds as {@code iam.grants}, for the same reason. */
    public static final String CACHE = "tenant.status";

    private final JdbcClient jdbc;

    public JdbcTenantSuspensionLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A tenant with no row is not suspended.
     *
     * <p>The fail-safe direction is towards allowing, and that is a decision
     * rather than an oversight: this sits on the hot path of every capability
     * check, and answering "suspended" for a tenant it simply could not find
     * would turn a bad identifier — or a replica that has not caught up — into
     * a total outage for a restaurant that is trading. Whether the scope names a
     * real tenant at all is {@code ResourceScopeVerifier}'s question, and it
     * already refuses.
     */
    @Override
    @Cacheable(cacheNames = CACHE, key = "#tenantId", sync = true)
    public boolean isSuspended(UUID tenantId) {
        return jdbc.sql("SELECT status = 'SUSPENDED' FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /**
     * Called by the writer, because a thirty-second window in which a suspension
     * has been recorded and is not yet in force is a window in which the reason
     * for suspending is still happening.
     */
    @Override
    @CacheEvict(cacheNames = CACHE, key = "#tenantId")
    public void evict(UUID tenantId) {
        // The annotation is the whole method.
    }
}
