package uz.horecaos.platform.workforce.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;

/**
 * Writes the ADR 0027 facts of the rota and the timesheet (ADR 0156, Audit), in the transaction of
 * the change they describe: a hours change that succeeded without evidence is indistinguishable from
 * one that never happened.
 *
 * <p>The actor is the human. A fact carries the member's display reference («S-0142») and never a
 * name, a phone or an employee number; the change document comes from {@code ChangeDocuments}, whose
 * protected terms redact a key that names either. Facts are {@code BUSINESS}: hours are business
 * data, keyed by a pseudonymous member id.
 */
@Component
public class WorkforceAudit {

    private final AuditRecorder audit;

    public WorkforceAudit(AuditRecorder audit) {
        this.audit = audit;
    }

    /** A change to something that existed: the document is the per-field before/after diff. */
    public void recordChange(
            String actionCode,
            Branch branch,
            String actorSubject,
            String targetType,
            UUID targetId,
            String because,
            Map<String, Object> before,
            Map<String, Object> after,
            String capability,
            String correlationId,
            Instant at) {
        audit.record(AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(branch.scope())
                .target(targetType, targetId)
                .because(because)
                .changed(ChangeDocuments.diff(before, after))
                .usingCapability(capability)
                .correlatedBy(correlationId)
                .occurredAt(at)
                .build());
    }

    /** Something that did not exist a moment ago: every field's prior state is nothing. */
    public void recordCreated(
            String actionCode,
            Branch branch,
            String actorSubject,
            String targetType,
            UUID targetId,
            String because,
            Map<String, Object> after,
            String capability,
            String correlationId,
            Instant at) {
        audit.record(AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(branch.scope())
                .target(targetType, targetId)
                .because(because)
                .changed(ChangeDocuments.created(after))
                .usingCapability(capability)
                .correlatedBy(correlationId)
                .occurredAt(at)
                .build());
    }
}
