package uz.horecaos.platform.commercial.api;

/**
 * Where a resolved entitlement value came from (ADR 0021).
 *
 * <p>Carried on every resolution for the same reason ADR 0030 carries a
 * resolution trace: the first question support asks about a limit is "why does
 * this tenant have that number", and a value without a provenance can only be
 * answered by reading four tables by hand.
 */
public enum EntitlementSource {

    /** A time-bounded, approved override recorded against this tenant. */
    TENANT_OVERRIDE,

    /** The entitlement on the plan version the tenant's subscription names. */
    PLAN_VERSION,

    /**
     * The subscription is suspended and a safety policy replaced the plan value.
     * Never destructive: it degrades what may be added, not what exists.
     */
    SUSPENSION_POLICY,

    /**
     * The code catalogue's safe default. Applies when the tenant has no live
     * subscription, or when the plan version says nothing about this key.
     */
    CATALOGUE_DEFAULT
}
