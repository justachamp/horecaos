package uz.qoida.platform.customers.application;

import java.time.Instant;
import java.util.UUID;

import uz.qoida.platform.customers.api.CustomerIdentityPolicy;

/**
 * The tenant's identity policy (ADR 0015).
 *
 * <p>A port rather than a direct read so the policy can move to ADR 0030's
 * versioned configuration without changing the resolution logic that depends
 * on it.
 */
public interface CustomerPolicyLookup {

    /**
     * The policy governing this tenant at an instant.
     *
     * <p>The instant is a parameter rather than a clock read inside the
     * implementation, so that the caller's clock decides — a policy row dated for
     * a future cutover is not in effect yet, and a test with a fixed clock gets
     * the answer its clock implies rather than the answer the wall clock does.
     */
    ResolvedIdentityPolicy policyFor(UUID tenantId, Instant at);

    /**
     * A resolved policy: the mode, and which governed decision produced it.
     *
     * <p>The two travel together because an account records both, and separating
     * them is how the mode came to stand in for the version. An account row keeps
     * {@code version} so a later mode change is a migration from a known starting
     * point; deriving it from {@code mode} answers "which rule applied", which is
     * a different question and one the row already answers through its stored
     * partition.
     *
     * @param mode    how the tenant partitions customer identity
     * @param version the version of the policy row that decided it, or
     *                {@code null} when the tenant has configured nothing and the
     *                default below applies. Null is not "version zero": it says
     *                no governed decision was ever made, which is what a later
     *                migration needs to know
     */
    record ResolvedIdentityPolicy(CustomerIdentityPolicy mode, Integer version) {

        /**
         * What governs a tenant that has never chosen.
         *
         * <p>{@link CustomerIdentityPolicy#TENANT_SHARED} is the safer default of
         * the two: a shared account can be split by a governed migration, whereas
         * isolated accounts that should have been shared leave a customer unable
         * to see their own history at a sibling brand and no way to prove which
         * accounts belong together.
         */
        public static ResolvedIdentityPolicy unconfigured() {
            return new ResolvedIdentityPolicy(CustomerIdentityPolicy.TENANT_SHARED, null);
        }
    }
}
