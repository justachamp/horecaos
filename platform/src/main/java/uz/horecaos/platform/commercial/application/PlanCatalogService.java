package uz.horecaos.platform.commercial.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.domain.PlanEntitlement;
import uz.horecaos.platform.commercial.domain.PlanVersion;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlanStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring and activating plan versions (ADR 0021).
 *
 * <p>Activation is the only irreversible act in this module and it is guarded
 * three ways: the entitlements are validated against the code catalogue, the
 * approver must not be the author, and the row becomes immutable at the database
 * afterwards. All three exist because a plan version is the document a tenant's
 * commercial terms are read from years later.
 */
@Service
public class PlanCatalogService {

    private final JdbcPlanStore plans;
    private final AuditRecorder audit;
    private final Clock clock;

    public PlanCatalogService(JdbcPlanStore plans, AuditRecorder audit, Clock clock) {
        this.plans = plans;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public UUID createPlan(String code, String name, ActorRef actor, String reason, String correlationId) {
        plans.findPlanIdByCode(code).ifPresent(existing -> {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A plan with code %s already exists".formatted(code),
                    Map.of("planCode", code));
        });

        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        plans.insertPlan(id, code, name, now);

        audit.record(AuditFact.of("commercial.plan.created", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.platform())
                .target("commercial.plan", id)
                .because(reason)
                .changed(Map.of("code", code, "name", name))
                .usingCapability(Capability.COMMERCIAL_PLAN_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return id;
    }

    /**
     * Drafts a new version of a plan.
     *
     * <p>Always a new version. There is no path here that edits an existing one,
     * which is what makes "the tenant was on version 3" a complete answer.
     */
    @Transactional
    public UUID draftVersion(
            UUID planId,
            String currency,
            long priceMinor,
            String billingPeriod,
            String termsReference,
            Map<String, PlanEntitlement> entitlements,
            ActorRef actor,
            String reason,
            String correlationId) {

        validate(entitlements);

        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        int versionNumber = plans.nextVersionNumber(planId);

        plans.insertPlanVersion(
                id,
                planId,
                versionNumber,
                currency,
                priceMinor,
                billingPeriod,
                termsReference,
                actorSubject(actor),
                now);
        entitlements.forEach((key, entitlement) -> plans.upsertPlanEntitlement(id, key, entitlement));

        Map<String, Object> change = new HashMap<>();
        change.put("versionNumber", versionNumber);
        change.put("currency", currency);
        change.put("priceMinor", priceMinor);
        change.put("billingPeriod", billingPeriod);
        change.put("entitlementKeys", List.copyOf(entitlements.keySet()));

        audit.record(AuditFact.of("commercial.plan_version.drafted", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.platform())
                .target("commercial.plan_version", id)
                .because(reason)
                .changed(change)
                .usingCapability(Capability.COMMERCIAL_PLAN_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return id;
    }

    /**
     * Makes a version live.
     *
     * <p>The four-eyes check is here as well as in the table's constraint. The
     * constraint is the one that cannot be bypassed; this one exists so the
     * caller gets a Problem Details response naming what is wrong rather than a
     * constraint violation naming a column.
     */
    @Transactional
    public void activate(UUID planVersionId, ActorRef approver, String reason, String correlationId) {
        PlanVersion version = plans.findVersion(planVersionId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such plan version"));

        if (version.isActivated()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Plan version %d is already active".formatted(version.versionNumber()));
        }
        if (actorSubject(approver).equals(version.createdBy())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A plan version is approved by somebody other than its author (ADR 0027)",
                    Map.of("createdBy", version.createdBy()));
        }

        Map<String, PlanEntitlement> entitlements = plans.entitlementsOf(planVersionId);
        validate(entitlements);

        Instant now = clock.instant();
        if (!plans.activate(planVersionId, actorSubject(approver), now, now)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "The plan version changed while it was being activated");
        }

        audit.record(AuditFact.of("commercial.plan_version.activated", AuditClass.BUSINESS)
                .by(approver)
                .at(ResourceScope.platform())
                .target("commercial.plan_version", planVersionId)
                .because(reason)
                .changed(Map.of(
                        "planCode", version.planCode(),
                        "versionNumber", version.versionNumber(),
                        "priceMinor", version.priceMinor(),
                        "currency", version.currency()))
                .usingCapability(Capability.COMMERCIAL_PLAN_ACTIVATE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    public List<PlanVersion> activeVersions() {
        return plans.listActiveVersions();
    }

    public Map<String, PlanEntitlement> entitlementsOf(UUID planVersionId) {
        return plans.entitlementsOf(planVersionId);
    }

    /**
     * Refuses a plan whose entitlements the code does not recognise.
     *
     * <p>An unknown key would sit in the table resolving to nothing, and a key
     * whose type disagrees with its declaration would resolve to a null limit —
     * which reads as unlimited. Both failures are silent and both are worth
     * failing a plan activation over.
     */
    private void validate(Map<String, PlanEntitlement> entitlements) {
        entitlements.forEach((code, entitlement) -> {
            EntitlementKey<?> key = EntitlementKeys.find(code)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.VALIDATION_FAILED,
                            "Unknown entitlement key %s".formatted(code),
                            Map.of("entitlementKey", code)));

            boolean counted = entitlement.integerValue() != null;
            if (counted != key.isCounted()) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Entitlement %s is declared as %s"
                                .formatted(code, key.isCounted() ? "a counted limit" : "a feature"),
                        Map.of("entitlementKey", code));
            }
            if (!key.isCounted() && entitlement.overageUnitPriceMinor() != null) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "A feature has no overage to price: %s".formatted(code),
                        Map.of("entitlementKey", code));
            }
        });
    }

    private static String actorSubject(ActorRef actor) {
        return actor.subject() == null ? "" : actor.subject();
    }
}
