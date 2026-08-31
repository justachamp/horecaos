package uz.horecaos.platform.migration.application;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * The ADR 0027 evidence for everything this package does.
 *
 * <p>One collaborator rather than a private method on each service, because the
 * two ways a migration action is recorded differ in a way that is easy to get
 * wrong once and then copy: a transition that happened is written in the
 * transaction that made it, and a transition that was <em>refused</em> is written
 * in a transaction of its own.
 */
@Component
class MigrationAudit {

    private final AuditRecorder audit;
    private final Clock clock;

    MigrationAudit(AuditRecorder audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Records what happened, in the caller's transaction.
     *
     * <p>An audit failure fails the transition, which is the correct direction:
     * a capability that changed hands without a record is indistinguishable from
     * one that never moved, and the second question anyone asks after a bad
     * cutover is who authorised it.
     */
    void record(
            String actionCode,
            ActorRef actor,
            ResourceScope scope,
            String targetType,
            UUID targetId,
            @Nullable Integer targetVersion,
            String reason,
            Map<String, Object> changes,
            @Nullable UUID approvalRequestId) {

        AuditFact.Builder fact = AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(actor)
                .at(scope)
                .target(targetType, targetId)
                .outcome(AuditFact.Outcome.SUCCEEDED)
                .because(reason)
                .changed(changes)
                .correlatedBy(correlationId())
                .occurredAt(clock.instant());
        if (targetVersion != null) {
            fact.targetVersion(targetVersion.longValue());
        }
        if (approvalRequestId != null) {
            fact.underApproval(approvalRequestId);
        }
        audit.record(fact.build());
    }

    /**
     * Records an attempt one of the ADR 0024 gates refused, in a transaction of
     * its own.
     *
     * <p>{@code REQUIRES_NEW} contradicts the rule that evidence commits with the
     * change, and does so deliberately: there is no change to commit with, because
     * the caller is about to throw and roll the whole attempt back. An operator
     * trying to reach {@code CUTOVER_READY} past an open critical difference is
     * precisely the event ADR 0024 exists to make visible, and recording it in the
     * doomed transaction would erase every one of those attempts.
     *
     * <p>Correspondingly it must not throw. A control plane that refuses a
     * transition and then fails on the way out reports the audit fault to the
     * operator as though it were the refusal, and they retry against the wrong
     * problem.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordRefusal(
            String actionCode,
            ActorRef actor,
            ResourceScope scope,
            String targetType,
            UUID targetId,
            @Nullable Integer targetVersion,
            String reason,
            Map<String, Object> changes) {

        AuditFact.Builder fact = AuditFact.of(actionCode, AuditClass.SECURITY)
                .by(actor)
                .at(scope)
                .target(targetType, targetId)
                .outcome(AuditFact.Outcome.REJECTED)
                .because(reason)
                .changed(changes)
                .correlatedBy(correlationId())
                .occurredAt(clock.instant());
        if (targetVersion != null) {
            fact.targetVersion(targetVersion.longValue());
        }
        audit.record(fact.build());
    }

    /**
     * The narrowest scope the row actually claims.
     *
     * <p>Not always the tenant: a scope narrowed to one branch is a fact about
     * that branch, and recording it at tenant level would hide which of a
     * network's forty locations cut over.
     */
    static ResourceScope scopeOf(UUID tenantId, @Nullable UUID brandId, @Nullable UUID locationId) {
        if (locationId != null) {
            // ck_scope_narrowing: a scope narrowed to a location always names the
            // location's brand too, so this is never actually null here.
            return ResourceScope.location(
                    tenantId, Objects.requireNonNull(brandId, "A location scope names its brand"), locationId);
        }
        if (brandId != null) {
            return ResourceScope.brand(tenantId, brandId);
        }
        return ResourceScope.tenant(tenantId);
    }

    private static String correlationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }
}
