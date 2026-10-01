package uz.horecaos.platform.audit.application;

import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.staff.StaffMemberChanged;

/**
 * Records what happened to a staff member record as an ADR 0027 fact
 * (ADR 0139).
 *
 * <p>Listens rather than being called, for the reason {@link GrantAuditListener}
 * gives: {@code iam} is the lowest layer and {@code audit} already depends on it,
 * so a call from {@code iam} would close a module cycle. {@code BEFORE_COMMIT}
 * keeps ADR 0027's guarantee: the fact is written inside the transaction that
 * made the change, so a rolled-back edit leaves no evidence and a committed one
 * always has some -- including a <em>read</em> of an emergency contact, whose
 * fact is the whole point of that capability.
 *
 * <p>The change document is built here, by {@code ChangeDocuments.diff}, because
 * {@code audit} owns it. The event carries no personal value -- only whether a
 * personal field is set -- and the shared redaction set turns those keys into
 * {@code [redacted]} markers, so the trail says that a phone changed and never
 * what it changed to.
 *
 * <p>Every fact is tenant-scoped: a staff member is a tenant-owned row, and the
 * tenant's own activity log is where "who changed Aziza's phone" is asked. The
 * actor is the human, and on a self-edit the actor and the target's subject are
 * the same person.
 */
@Component
public class StaffMemberAuditListener {

    private final AuditRecorder audit;

    public StaffMemberAuditListener(AuditRecorder audit) {
        this.audit = audit;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onStaffMemberChanged(StaffMemberChanged event) {
        AuditFact.Builder fact = AuditFact.of(event.actionCode(), AuditClass.SECURITY)
                .by(actorOf(event))
                .at(ResourceScope.tenant(event.tenantId()))
                .target("iam.staff_member", event.memberId())
                .because(event.reason())
                .changed(ChangeDocuments.diff(event.before(), event.after()))
                .correlatedBy(event.correlationId())
                .occurredAt(event.occurredAt());
        if (event.capabilityUsed() != null) {
            fact.usingCapability(event.capabilityUsed());
        }
        if (event.targetVersion() != null) {
            fact.targetVersion(event.targetVersion());
        }
        audit.record(fact.build());
    }

    private static ActorRef actorOf(StaffMemberChanged event) {
        String subject = event.actorSubject();
        return subject != null
                ? ActorRef.user(subject, null)
                : ActorRef.systemJob(Objects.requireNonNull(event.systemJob(), "one attribution or another"));
    }
}
