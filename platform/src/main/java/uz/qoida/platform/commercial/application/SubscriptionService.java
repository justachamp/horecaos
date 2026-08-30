package uz.qoida.platform.commercial.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.AuditClass;
import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.api.AuditRecorder;
import uz.qoida.platform.commercial.api.EntitlementSnapshot;
import uz.qoida.platform.commercial.domain.PlanVersion;
import uz.qoida.platform.commercial.domain.Subscription;
import uz.qoida.platform.commercial.domain.SubscriptionStatus;
import uz.qoida.platform.commercial.domain.UsagePeriods;
import uz.qoida.platform.commercial.infrastructure.persistence.JdbcPlanStore;
import uz.qoida.platform.commercial.infrastructure.persistence.JdbcSubscriptionStore;
import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;

/**
 * Assigning and moving subscriptions (ADR 0021).
 *
 * <p>Manual assignment, deliberately. ADR 0021's first slice does not automate
 * recurring billing because invoicing, tax treatment and provider selection are
 * unresolved, and a subscription created by an operator with a reason attached
 * is an honest record of that. Nothing in this class talks to a payment
 * provider; ADR 0013 owns money movement.
 */
@Service
public class SubscriptionService {

    private final JdbcSubscriptionStore subscriptions;
    private final JdbcPlanStore plans;
    private final EntitlementQueryService entitlements;
    private final AuditRecorder audit;
    private final Clock clock;

    public SubscriptionService(JdbcSubscriptionStore subscriptions, JdbcPlanStore plans,
            EntitlementQueryService entitlements, AuditRecorder audit, Clock clock) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.entitlements = entitlements;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Puts a tenant on a plan version.
     *
     * <p>The entitlement snapshot is resolved and recorded on the audit fact
     * afterwards, so that "what was this tenant entitled to when it was
     * onboarded" is answerable from the audit trail rather than by replaying
     * every plan change since.
     */
    @Transactional
    public UUID start(UUID tenantId, UUID planVersionId, Integer trialDays, ActorRef actor,
            String reason, String correlationId) {

        PlanVersion version = plans.findVersion(planVersionId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such plan version"));
        if (!version.isActivated()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A tenant is only put on an activated plan version",
                    Map.of("planVersionId", planVersionId.toString()));
        }
        subscriptions.findLive(tenantId).ifPresent(live -> {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "The tenant already has a live subscription",
                    Map.of("subscriptionId", live.id().toString(), "status", live.status().name()));
        });

        Instant now = clock.instant();
        ZoneId zone = subscriptions.timezone(tenantId);
        int months = Math.max(1, version.billingMonths());
        Instant trialEnd = trialDays == null ? null : now.plus(java.time.Duration.ofDays(trialDays));

        Subscription subscription = new Subscription(
                UUID.randomUUID(), tenantId, planVersionId,
                trialDays == null ? SubscriptionStatus.ACTIVE : SubscriptionStatus.TRIALING,
                now, trialEnd, now, UsagePeriods.advance(now, zone, months),
                null, null, null, null, null, 1);

        subscriptions.insert(subscription, now);

        EntitlementSnapshot snapshot = entitlements.snapshot(tenantId);
        Map<String, Object> change = new HashMap<>();
        change.put("planCode", version.planCode());
        change.put("planVersionNumber", version.versionNumber());
        change.put("status", subscription.status().name());
        change.put("entitlementHash", snapshot.hash());

        audit.record(AuditFact.of("commercial.subscription.started", AuditClass.BUSINESS)
                .by(actor).at(ResourceScope.tenant(tenantId))
                .target("commercial.subscription", subscription.id())
                .targetVersion(1L)
                .because(reason)
                .changed(change)
                .usingCapability(Capability.COMMERCIAL_SUBSCRIPTION_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());

        return subscription.id();
    }

    /**
     * Moves a subscription to another status.
     *
     * <p>The state machine is asserted before the write and the write asserts it
     * again against the row. Suspension carries a reason because ADR 0021's
     * degraded behaviour is something a tenant will ask to have explained, and a
     * suspension with no recorded cause is one nobody can lift with confidence.
     */
    @Transactional
    public void transition(UUID tenantId, SubscriptionStatus to, long expectedVersion,
            String suspensionReason, Instant cancelAt, ActorRef actor, String reason,
            String correlationId) {

        Subscription live = subscriptions.findLive(tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "The tenant has no live subscription"));

        if (!live.status().canTransitionTo(to)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "A subscription cannot go from %s to %s".formatted(live.status(), to),
                    Map.of("currentStatus", live.status().name(), "requestedStatus", to.name()));
        }
        if (to == SubscriptionStatus.SUSPENDED && (suspensionReason == null || suspensionReason.isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A suspension records why it happened");
        }
        if (live.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, live.version());
        }

        Instant now = clock.instant();
        boolean suspending = to == SubscriptionStatus.SUSPENDED;
        boolean terminal = to.isTerminal();

        boolean moved = subscriptions.transition(tenantId, live.id(), live.status(), to,
                expectedVersion,
                suspending ? now : null,
                suspending ? suspensionReason : null,
                to == SubscriptionStatus.CANCELLATION_SCHEDULED ? cancelAt : null,
                terminal ? now : null,
                now);

        if (!moved) {
            throw ApiException.staleVersion(expectedVersion, live.version());
        }

        Map<String, Object> change = new HashMap<>();
        change.put("from", live.status().name());
        change.put("to", to.name());
        change.put("entitlementHash", entitlements.snapshot(tenantId).hash());
        if (suspending) {
            change.put("suspensionReason", suspensionReason);
        }

        audit.record(AuditFact.of("commercial.subscription.transitioned", AuditClass.BUSINESS)
                .by(actor).at(ResourceScope.tenant(tenantId))
                .target("commercial.subscription", live.id())
                .targetVersion(expectedVersion + 1)
                .because(reason)
                .changed(change)
                .usingCapability(Capability.COMMERCIAL_SUBSCRIPTION_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    /**
     * Grants a time-bounded override.
     *
     * <p>Replacing a live override is a revoke and an insert rather than an
     * update, so the record of what was granted, by whom, and until when survives
     * the replacement.
     */
    @Transactional
    public UUID override(UUID tenantId, String entitlementKey, Long integerValue,
            Boolean booleanValue, Instant validUntil, ActorRef requester, String approvedBy,
            String reason, String correlationId) {

        Instant now = clock.instant();
        if (validUntil == null || !validUntil.isAfter(now)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "An override is time-bounded and expires in the future");
        }
        if (approvedBy == null || approvedBy.equals(requesterSubject(requester))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "An override is approved by somebody other than its requester (ADR 0027)");
        }

        subscriptions.revokeOverride(tenantId, entitlementKey, requesterSubject(requester), now);
        UUID id = UUID.randomUUID();
        subscriptions.insertOverride(id, tenantId, entitlementKey, integerValue, booleanValue,
                null, reason, now, validUntil, requesterSubject(requester), approvedBy, now);

        audit.record(AuditFact.of("commercial.entitlement_override.granted", AuditClass.BUSINESS)
                .by(requester).at(ResourceScope.tenant(tenantId))
                .target("commercial.entitlement_override", id)
                .because(reason)
                .changed(Map.of(
                        "entitlementKey", entitlementKey,
                        "validUntil", validUntil.toString(),
                        "approvedBy", approvedBy))
                .usingCapability(Capability.COMMERCIAL_OVERRIDE_APPROVE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return id;
    }

    public Optional<Subscription> live(UUID tenantId) {
        return subscriptions.findLive(tenantId);
    }

    public List<Subscription> history(UUID tenantId) {
        return subscriptions.history(tenantId);
    }

    private static String requesterSubject(ActorRef actor) {
        return actor.subject() == null ? "" : actor.subject();
    }
}
