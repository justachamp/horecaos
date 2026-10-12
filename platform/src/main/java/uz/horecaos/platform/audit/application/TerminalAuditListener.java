package uz.horecaos.platform.audit.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.terminals.TerminalFact;

/**
 * Records what happened at a terminal as ADR 0027 SECURITY facts (ADR 0155): an activation code issued, a PIN set,
 * changed, reset or locked, a session opened or ended, a device configured or revoked.
 *
 * <p>Listens rather than being called, for the reason {@link GrantAuditListener} gives: {@code iam} is the lowest layer and
 * {@code audit} already depends on it, so a call from {@code iam} would close a module cycle. {@code BEFORE_COMMIT} keeps
 * ADR 0027's guarantee -- the fact is written inside the transaction that made the change.
 *
 * <p>The change document is built here, by {@code ChangeDocuments.diff}. The event carries no PIN, no digest, no activation
 * code and no session token -- they are not in its type -- and the shared redaction set would turn a field named for one
 * into a marker if a caller ever added it. A fact the platform raised about a device (a lock earned by wrong attempts at the
 * screen) is attributed to the device as a service actor; one a person or a manager did is attributed to that person.
 */
@Component
public class TerminalAuditListener {

    private final AuditRecorder audit;

    public TerminalAuditListener(AuditRecorder audit) {
        this.audit = audit;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onTerminalFact(TerminalFact event) {
        ActorRef actor = event.actorSubject() != null
                ? ActorRef.user(event.actorSubject(), null)
                : ActorRef.service("terminal-device:" + event.deviceId());
        AuditFact.Builder fact = AuditFact.of(event.actionCode(), AuditClass.SECURITY)
                .by(actor)
                .at(event.scope())
                .target(event.targetType(), event.targetId())
                .because(event.reason())
                .changed(ChangeDocuments.diff(event.before(), event.after()))
                .evidence(event.evidenceReference())
                .correlatedBy(event.correlationId())
                .occurredAt(event.occurredAt());
        if (event.capabilityUsed() != null) {
            fact.usingCapability(event.capabilityUsed());
        }
        audit.record(fact.build());
    }
}
