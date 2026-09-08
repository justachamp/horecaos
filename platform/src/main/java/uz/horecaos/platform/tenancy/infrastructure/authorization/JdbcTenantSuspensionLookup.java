package uz.horecaos.platform.tenancy.infrastructure.authorization;

import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.TenantAvailability;
import uz.horecaos.platform.iam.api.TenantSuspensionLookup;
import uz.horecaos.platform.tenancy.application.port.TenantStatusCache;

/**
 * Tenancy's answer to {@link TenantSuspensionLookup}.
 *
 * <p>Maps {@code tenant.tenants.status} onto the three states authorization
 * distinguishes (ADR 0078): {@code PROVISIONING} and {@code ACTIVE} are both
 * {@code OPERATING} — onboarding has to be able to finish — {@code SUSPENDED} is
 * {@code READ_ONLY}, and {@code ARCHIVED} is {@code CLOSED}.
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
     * A tenant with no row is operating.
     *
     * <p>The fail-safe direction is towards allowing, and that is a decision
     * rather than an oversight: this sits on the hot path of every capability
     * check, and answering "closed" for a tenant it simply could not find would
     * turn a bad identifier — or a replica that has not caught up — into a total
     * outage for a restaurant that is trading. Whether the scope names a
     * real tenant at all is {@code ResourceScopeVerifier}'s question, and it
     * already refuses.
     */
    @Override
    @Cacheable(cacheNames = CACHE, key = "#tenantId", sync = true)
    public TenantAvailability availabilityOf(UUID tenantId) {
        return jdbc.sql("SELECT status FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .optional()
                .map(JdbcTenantSuspensionLookup::availabilityOfStatus)
                .orElse(TenantAvailability.OPERATING);
    }

    /**
     * A status this code does not recognise is {@code OPERATING}, for the same
     * fail-safe reason a missing row is: a status added by a later migration and
     * not yet mapped here must not silently close every tenant that has it.
     */
    private static TenantAvailability availabilityOfStatus(String status) {
        return switch (status) {
            case "SUSPENDED" -> TenantAvailability.READ_ONLY;
            case "ARCHIVED" -> TenantAvailability.CLOSED;
            default -> TenantAvailability.OPERATING;
        };
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
