package uz.horecaos.platform.tenancy.application.port;

import java.util.UUID;

/**
 * Drops the cached answer to "is this tenant suspended".
 *
 * <p>A one-method port rather than a direct dependency on the JDBC lookup, so a
 * caller that does not care about caching — every unit test of the control
 * plane — can pass {@code tenantId -> {}} instead of a database client. The
 * production implementation is
 * {@code tenancy.infrastructure.authorization.JdbcTenantSuspensionLookup}.
 */
@FunctionalInterface
public interface TenantStatusCache {

    void evict(UUID tenantId);
}
