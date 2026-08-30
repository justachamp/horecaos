package uz.horecaos.platform.audit.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.GrantChanged;

/**
 * Records grant changes as security audit facts (ADR 0025, ADR 0027).
 *
 * <p>Listens rather than being called, because {@code iam} is the lowest layer
 * and {@code audit} already depends on it for {@code ResourceScope}. A direct
 * call in the other direction would make the modules cyclic.
 *
 * <p>{@code BEFORE_COMMIT} keeps the ADR 0027 guarantee intact: the fact is
 * written inside the same transaction as the grant, so a rolled-back grant
 * leaves no evidence and a committed one always has some. This is the same
 * pattern the tenancy outbox listener uses.
 */
@Component
public class GrantAuditListener {

    private final AuditRecorder audit;

    public GrantAuditListener(AuditRecorder audit) {
        this.audit = audit;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onGrantChanged(GrantChanged event) {
        audit.record(AuditFact.of(event.actionCode(), AuditClass.SECURITY)
                .by(ActorRef.user(event.actorSubject(), null))
                .at(event.scope())
                .target("Grant", event.grantId())
                .because(event.reason())
                .changed(event.details())
                .usingCapability(Capability.IAM_GRANT_MANAGE.code())
                .correlatedBy(event.grantId().toString())
                .occurredAt(event.occurredAt())
                .build());
    }
}
