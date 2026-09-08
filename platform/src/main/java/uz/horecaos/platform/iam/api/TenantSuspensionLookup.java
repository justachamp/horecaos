package uz.horecaos.platform.iam.api;

import java.util.UUID;

/**
 * Whether a tenant is suspended, asked by authorization and answered by
 * tenancy.
 *
 * <p>Declared here and implemented there for the reason {@link ResourceScope}'s
 * own documentation gives: tenancy already depends on {@code iam.api}, so iam
 * reading {@code tenant.tenants} directly — in Java or in SQL — would make the
 * two modules cyclic and would put tenancy's status vocabulary inside iam's
 * grant query.
 *
 * <p>The answer is consulted on the hot path of every capability check, so the
 * implementation caches it (ADR 0033, {@code tenant.status}) and the writer
 * evicts. PostgreSQL stays the authority: a miss or a cache outage degrades to
 * a read, and the fail-safe direction is deliberate — an unknown tenant is
 * <em>not</em> suspended, because a lookup failure must not lock an operating
 * restaurant out of its own tills.
 */
public interface TenantSuspensionLookup {

    /**
     * @param tenantId the tenant a request is scoped to
     * @return whether that tenant is currently suspended; false for a tenant
     *         that does not exist, which is a question the scope verifier
     *         answers and this one deliberately does not
     */
    boolean isSuspended(UUID tenantId);
}
