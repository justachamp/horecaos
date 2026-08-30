package uz.qoida.platform.commercial.api;

import java.util.UUID;

/**
 * The local entitlement port every product module calls (ADR 0021).
 *
 * <p>Local is the load-bearing word. Resolution reads PostgreSQL and nothing
 * else; no implementation of this interface may call a billing provider, because
 * a provider outage would then stop a restaurant taking orders. ADR 0021 rejects
 * that arrangement outright and this interface is where the rejection is kept
 * honest.
 *
 * <p>An entitlement is never an authorization decision. ADR 0025 decides whether
 * this principal may perform this action; this interface decides whether the
 * tenant's commercial terms include the capacity for it. Both run, and a plan
 * can never grant a permission.
 */
public interface EntitlementService {

    /** Everything the tenant is entitled to right now, with provenance and a hash. */
    EntitlementSnapshot snapshot(UUID tenantId);

    /**
     * Answers what would happen if the tenant consumed {@code requested} more of
     * {@code key}. Never throws for a commercial reason and never mutates.
     */
    LimitCheck check(UUID tenantId, EntitlementKey<Long> key, long requested);

    /**
     * The same question, refused when — and only when — the effective mode says
     * to refuse.
     *
     * <p>Under {@link EnforcementMode#METER_ONLY} this method cannot throw. That
     * is a guarantee rather than a consequence: the pilot runs meter-only, and a
     * check that begins refusing things because a plan was misconfigured is an
     * outage the tenant cannot diagnose and Qoida caused.
     *
     * @return the check, so a caller that proceeded can still record the overage
     *         it just incurred
     */
    LimitCheck require(UUID tenantId, EntitlementKey<Long> key, long requested);

    /** Whether a feature is available to this tenant. */
    boolean featureEnabled(UUID tenantId, EntitlementKey<Boolean> key);

    /**
     * Refuses activation of a feature the plan does not include, under
     * {@link EnforcementMode#DISABLED} only. Existing data stays readable and
     * exportable whatever this answers.
     */
    void requireFeature(UUID tenantId, EntitlementKey<Boolean> key);
}
