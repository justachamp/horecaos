package uz.horecaos.platform.audit.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.SupportSessionChanged;

/**
 * Records support sessions opening and ending as security facts (ADR 0081),
 * in the same transaction, for the reason {@link GrantAuditListener} gives.
 *
 * <p>Filed at the tenant's scope so the tenant's own audit log shows who from
 * HorecaOS came in, when and why, beside everything they did while inside.
 */
@Component
public class SupportSessionAuditListener {

    private final AuditRecorder audit;

    public SupportSessionAuditListener(AuditRecorder audit) {
        this.audit = audit;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onSupportSessionChanged(SupportSessionChanged event) {
        audit.record(AuditFact.of(event.actionCode(), AuditClass.SECURITY)
                .by(ActorRef.user(event.actorSubject(), null))
                .at(ResourceScope.tenant(event.tenantId()))
                .target("SupportSession", event.sessionId())
                .because(event.reason())
                .changed(event.details())
                .usingCapability(
                        event.change() == SupportSessionChanged.Change.OPENED
                                ? Capability.SUPPORT_SESSION_START.code()
                                : Capability.IAM_GRANT_MANAGE.code())
                .correlatedBy(event.sessionId().toString())
                .occurredAt(event.occurredAt())
                .build());
    }
}
