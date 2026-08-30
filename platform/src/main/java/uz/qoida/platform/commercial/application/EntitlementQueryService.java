package uz.qoida.platform.commercial.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.commercial.api.Boundary;
import uz.qoida.platform.commercial.api.EnforcementMode;
import uz.qoida.platform.commercial.api.EntitlementKey;
import uz.qoida.platform.commercial.api.EntitlementKeys;
import uz.qoida.platform.commercial.api.EntitlementService;
import uz.qoida.platform.commercial.api.EntitlementSnapshot;
import uz.qoida.platform.commercial.api.EntitlementValue;
import uz.qoida.platform.commercial.api.LimitCheck;
import uz.qoida.platform.commercial.api.UsagePeriod;
import uz.qoida.platform.commercial.domain.BoundaryPolicy;
import uz.qoida.platform.commercial.domain.EntitlementOverride;
import uz.qoida.platform.commercial.domain.EntitlementResolution;
import uz.qoida.platform.commercial.domain.PlanEntitlement;
import uz.qoida.platform.commercial.domain.PlanVersion;
import uz.qoida.platform.commercial.domain.Subscription;
import uz.qoida.platform.commercial.domain.SubscriptionStatus;
import uz.qoida.platform.commercial.domain.UsagePeriods;
import uz.qoida.platform.commercial.domain.UsageTotals;
import uz.qoida.platform.commercial.infrastructure.persistence.JdbcPlanStore;
import uz.qoida.platform.commercial.infrastructure.persistence.JdbcSubscriptionStore;
import uz.qoida.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;

/**
 * Local entitlement resolution (ADR 0021).
 *
 * <p>Reads PostgreSQL and nothing else. There is no cache and no invalidation
 * event, which is a deliberate omission rather than an unfinished one: ADR 0021
 * lists entitlement caching among its own negative consequences, and until a
 * measured request path needs it, a plan change that is not yet visible is a
 * support ticket bought for no gain. The row is one indexed lookup per tenant.
 */
@Service
public class EntitlementQueryService implements EntitlementService {

    private final JdbcSubscriptionStore subscriptions;
    private final JdbcPlanStore plans;
    private final JdbcUsageStore usage;
    private final EnforcementCeiling ceiling;
    private final Clock clock;

    public EntitlementQueryService(JdbcSubscriptionStore subscriptions, JdbcPlanStore plans,
            JdbcUsageStore usage, EnforcementCeiling ceiling, Clock clock) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.usage = usage;
        this.ceiling = ceiling;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public EntitlementSnapshot snapshot(UUID tenantId) {
        Instant now = clock.instant();
        Context context = contextOf(tenantId, now);

        Map<String, EntitlementValue> values = new HashMap<>();
        for (EntitlementKey<?> key : EntitlementKeys.all()) {
            values.put(key.code(), context.resolve(key, now));
        }
        return new EntitlementSnapshot(tenantId,
                context.subscription().map(Subscription::id).orElse(null),
                values, now);
    }

    @Override
    @Transactional
    public LimitCheck check(UUID tenantId, EntitlementKey<Long> key, long requested) {
        Instant now = clock.instant();
        Context context = contextOf(tenantId, now);
        EntitlementValue value = context.resolve(key, now);
        UsagePeriod period = context.periodFor(value, now);
        long consumed = consumption(tenantId, key.code(), period);

        // Two answers from one set of facts. The first is what the platform will
        // do; the second is what the commercial terms alone would have done, and
        // it is the only evidence anyone will have when deciding whether the
        // ceiling can safely be raised.
        Boundary effective = BoundaryPolicy.decideCounted(value, consumed, requested, value.effectiveMode());
        Boundary declared = BoundaryPolicy.decideCounted(value, consumed, requested, value.declaredMode());

        return new LimitCheck(key.code(), tenantId, value.limit(), consumed, requested, period,
                value, effective, declared,
                BoundaryPolicy.overageQuantity(value, consumed, requested));
    }

    @Override
    @Transactional
    public LimitCheck require(UUID tenantId, EntitlementKey<Long> key, long requested) {
        LimitCheck check = check(tenantId, key, requested);
        if (!check.allowed()) {
            throw refusal(check);
        }
        return check;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean featureEnabled(UUID tenantId, EntitlementKey<Boolean> key) {
        Instant now = clock.instant();
        EntitlementValue value = contextOf(tenantId, now).resolve(key, now);
        return BoundaryPolicy.decideFeature(value, value.effectiveMode()).allowed();
    }

    @Override
    @Transactional(readOnly = true)
    public void requireFeature(UUID tenantId, EntitlementKey<Boolean> key) {
        Instant now = clock.instant();
        EntitlementValue value = contextOf(tenantId, now).resolve(key, now);
        if (BoundaryPolicy.decideFeature(value, value.effectiveMode()) == Boundary.REFUSED) {
            throw new ApiException(ErrorCode.ENTITLEMENT_REQUIRED,
                    "The current plan does not include %s".formatted(key.code()),
                    Map.of("entitlementKey", key.code(),
                            "enforcementMode", value.effectiveMode().name(),
                            "upgradePath", "/api/v1/control-plane/plans"));
        }
    }

    /**
     * The consumed figure for a period, self-healing.
     *
     * <p>The aggregate is a cache and can be missing — truncated during a
     * rebuild, or simply never written because nothing has been consumed yet.
     * Treating a missing row as zero would be wrong in exactly the case that
     * matters: a rebuild in progress would silently grant every tenant its whole
     * allowance again. So a miss recomputes from the ledger and stores the
     * answer.
     */
    private long consumption(UUID tenantId, String entitlementKey, UsagePeriod period) {
        Optional<UsageTotals> stored = usage.storedTotals(tenantId, entitlementKey, period);
        if (stored.isPresent()) {
            return stored.get().consumed();
        }
        UsageTotals recomputed = usage.recompute(tenantId, entitlementKey, period);
        if (recomputed.eventCount() > 0 || recomputed.adjustmentQuantity() != 0) {
            usage.storeTotals(tenantId, recomputed, clock.instant());
        }
        return recomputed.consumed();
    }

    private static ApiException refusal(LimitCheck check) {
        Map<String, Object> details = new HashMap<>();
        details.put("entitlementKey", check.entitlementKey());
        details.put("limit", check.limit());
        details.put("consumed", check.consumed());
        details.put("requested", check.requested());
        details.put("periodKey", check.period().key());
        details.put("enforcementMode", check.value().effectiveMode().name());
        // A refusal a tenant cannot act on is an outage with extra steps, so the
        // problem document always names where the limit can be raised.
        details.put("upgradePath", "/api/v1/control-plane/plans");

        return new ApiException(ErrorCode.ENTITLEMENT_REQUIRED,
                "%s allows %d per %s and %d have been used"
                        .formatted(check.entitlementKey(), check.limit(),
                                check.period().key(), check.consumed()),
                details);
    }

    Context contextOf(UUID tenantId, Instant at) {
        Optional<Subscription> subscription = subscriptions.findLive(tenantId);
        Optional<PlanVersion> planVersion = subscription
                .flatMap(live -> plans.findVersion(live.planVersionId()));
        Map<String, PlanEntitlement> planEntitlements = planVersion
                .map(version -> plans.entitlementsOf(version.id()))
                .orElseGet(Map::of);

        return new Context(
                subscription,
                planVersion,
                planEntitlements,
                subscriptions.overrides(tenantId),
                subscriptions.timezone(tenantId),
                ceiling.forTenant(tenantId));
    }

    /**
     * Everything one tenant's resolution needs, fetched once.
     *
     * <p>Assembled before any key is resolved so that a snapshot of twelve keys
     * is four queries rather than forty-eight, and so that every key in one
     * snapshot sees the same subscription and the same ceiling.
     */
    record Context(
            Optional<Subscription> subscription,
            Optional<PlanVersion> planVersion,
            Map<String, PlanEntitlement> planEntitlements,
            Map<String, EntitlementOverride> overrides,
            ZoneId timezone,
            EnforcementMode ceiling) {

        EntitlementValue resolve(EntitlementKey<?> key, Instant at) {
            SubscriptionStatus status = subscription.map(Subscription::status).orElse(null);
            return EntitlementResolution.resolve(
                    key,
                    planEntitlements.get(key.code()),
                    overrides.get(key.code()),
                    status,
                    planVersion.map(PlanVersion::currency).orElse(null),
                    ceiling,
                    at);
        }

        UsagePeriod periodFor(EntitlementValue value, Instant at) {
            return UsagePeriods.of(value.resetPeriod(), at, timezone,
                    subscription.map(Subscription::currentPeriodStart).orElse(null),
                    subscription.map(Subscription::currentPeriodEnd).orElse(null));
        }
    }
}
