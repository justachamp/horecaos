package uz.qoida.platform.customers.api;

/**
 * How a tenant partitions customer identity (ADR 0015).
 *
 * <p>Changing this is a governed migration, not a toggle. Flipping
 * {@code BRAND_ISOLATED} to {@code TENANT_SHARED} silently would merge people who
 * deliberately kept separate accounts at two of a tenant's brands; flipping the
 * other way would split an account whose history and consent then have no
 * defined owner.
 */
public enum CustomerIdentityPolicy {

    /** One account per person per tenant, with a profile per brand. */
    TENANT_SHARED,

    /**
     * A separate account per brand. The same person signing in at two brands of
     * the same tenant gets two unrelated accounts, which is what a tenant
     * operating unrelated brands under one company usually wants.
     */
    BRAND_ISOLATED;

    /** Null under {@link #TENANT_SHARED}; the brand under {@link #BRAND_ISOLATED}. */
    public java.util.UUID partitionFor(java.util.UUID brandId) {
        return this == BRAND_ISOLATED ? brandId : null;
    }
}
