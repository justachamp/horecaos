package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierTypeRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierTypeUpdate;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Correcting and archiving a vehicle class, and archiving a bonus/penalty
 * reason — the three ADR 0108 mutations {@code OperationsCourierController}
 * used to send straight to {@link JdbcCourierStore} with no audit trail at
 * all (the P34-style gap the T16 adversarial review found).
 *
 * <p>{@code OperationsCourierController}'s other mutating handlers each
 * delegate to a dedicated service — {@code roster}, {@code rateCards}, {@code
 * plannedShifts} — that writes its change and its {@link AuditFact} inside one
 * {@code @Transactional} boundary, per this codebase's binding rule that an
 * audit fact is written in the same transaction as the change it describes. A
 * dispatch-ceiling correction and an ARCHIVED reason (which silently stops
 * {@code AdjustmentRuleEvaluator} from ever firing again) are exactly the
 * kind of change that must leave a record of who did it and why; this class
 * is that missing delegate for the three handlers that had none.
 */
@Service
public class CourierTypeService {

    private final JdbcCourierStore store;
    private final AuditRecorder audit;
    private final Clock clock;

    public CourierTypeService(JdbcCourierStore store, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    /** Corrects an active vehicle class's dispatch ceilings and pay-relevant attributes. */
    @Transactional
    public CourierTypeRow updateType(
            UUID tenantId, UUID typeId, CourierTypeUpdate update, int expectedVersion, ActorRef actor, String reason) {
        CourierTypeRow current = store.findType(tenantId, typeId)
                .filter(row -> "ACTIVE".equals(row.status()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such courier type: " + typeId));

        if (!store.updateType(tenantId, typeId, update, expectedVersion, clock.instant())) {
            throw ApiException.staleVersion(expectedVersion, current.version());
        }

        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("code", update.code());
        changed.put("displayName", update.displayName());
        changed.put("vehicleClass", update.vehicleClass());
        changed.put("minDistanceMeters", update.minDistanceMeters());
        changed.put("maxDistanceMeters", update.maxDistanceMeters());
        changed.put("maxConcurrentAssignments", update.maxConcurrentAssignments());
        changed.put("offerTtlSeconds", update.offerTtlSeconds());
        changed.put("startingMinuteOffset", update.startingMinuteOffset());
        changed.put("workMode", update.workMode());

        audit.record(AuditFact.of("courier-type.updated", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("CourierType", typeId)
                .targetVersion((long) (current.version() + 1))
                .because(reason)
                .changed(changed)
                .correlatedBy(correlationId())
                .occurredAt(clock.instant())
                .build());

        return store.findType(tenantId, typeId).orElseThrow();
    }

    /** Archives an active vehicle class. Still named by any rate card or courier that already used it. */
    @Transactional
    public void archiveType(UUID tenantId, UUID typeId, ActorRef actor, String reason) {
        if (!store.archiveType(tenantId, typeId, clock.instant())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND, "No active courier type %s to archive".formatted(typeId));
        }

        audit.record(AuditFact.of("courier-type.archived", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("CourierType", typeId)
                .because(reason)
                .changed(Map.of("status", "ARCHIVED"))
                .correlatedBy(correlationId())
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * Archives a bonus/penalty reason. A rule-wired reason stops evaluating the
     * instant this commits — {@code AdjustmentRuleEvaluator}'s read of {@code
     * ruleReasonsAt} only ever sees {@code status = 'ACTIVE'} rows — so the
     * audit fact says so rather than reading as an inert renaming.
     */
    @Transactional
    public void archiveAdjustmentReason(UUID tenantId, UUID reasonId, ActorRef actor, String reason) {
        if (!store.archiveAdjustmentReason(tenantId, reasonId)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND, "No active adjustment reason %s to archive".formatted(reasonId));
        }

        audit.record(AuditFact.of("adjustment-reason.archived", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("CourierAdjustmentReason", reasonId)
                .because(reason)
                .changed(Map.of(
                        "status",
                        "ARCHIVED",
                        // Not "note" or "comment" -- both are protected terms
                        // ChangeDocuments.isProtected substring-matches, which would
                        // redact this whole explanation to "[redacted]" the same way
                        // it caught "startingMinuteOffset" below (it contains "tin",
                        // as does this word if misspelled "notation"/"annotation").
                        "impact",
                        "a rule-wired reason stops evaluating immediately; AdjustmentRuleEvaluator "
                                + "only reads ACTIVE reasons"))
                .correlatedBy(correlationId())
                .occurredAt(clock.instant())
                .build());
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }
}
