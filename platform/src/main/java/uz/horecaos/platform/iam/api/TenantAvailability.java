package uz.horecaos.platform.iam.api;

/**
 * How much of a tenant is still reachable, expressed in the terms
 * authorization needs rather than in tenancy's own status vocabulary
 * (ADR 0078).
 *
 * <p>Tenancy maps its {@code TenantStatus} onto this. The indirection is the
 * point: {@code PROVISIONING} and {@code ACTIVE} are different things to
 * onboarding and the same thing to a capability check, and iam has no business
 * knowing which statuses exist.
 */
public enum TenantAvailability {

    /** Every grant applies. `PROVISIONING` and `ACTIVE` both land here. */
    OPERATING,

    /**
     * Read capabilities apply; nothing else does. A suspended tenant may look at
     * what it has and may not change it, take it, or reveal it.
     */
    READ_ONLY,

    /**
     * No tenant-scoped grant applies at all. An archived tenant is finished, and
     * whatever access its people need afterwards is a platform-side act with a
     * platform-side actor on the audit trail.
     */
    CLOSED
}
