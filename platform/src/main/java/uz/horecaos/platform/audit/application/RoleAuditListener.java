package uz.horecaos.platform.audit.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.RoleChanged;

/**
 * Records a tenant defining, changing or closing one of its own jobs as a security audit fact
 * (ADR 0186, ADR 0027).
 *
 * <p>The sibling of {@link GrantAuditListener}, for the same reason it listens instead of being
 * called: {@code iam} is the lowest layer and {@code audit} depends on it, so a direct call the
 * other way would close a module cycle. {@code BEFORE_COMMIT} keeps ADR 0027's guarantee: the
 * fact is written in the transaction that made the change, so a rolled-back edit leaves no
 * evidence and a committed one always has some.
 *
 * <p>The action codes are written as literals in the calls below so the console's action-code
 * dictionary, which scans these sources for the literal first argument of each audit-fact
 * builder call, finds them and fails its coverage spec for a code without a sentence in all
 * three languages.
 */
@Component
public class RoleAuditListener {

    private final AuditRecorder audit;

    public RoleAuditListener(AuditRecorder audit) {
        this.audit = audit;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onRoleChanged(RoleChanged event) {
        AuditFact.Builder fact =
                switch (event.change()) {
                    case CREATED ->
                        AuditFact.of("iam.role.created", AuditClass.SECURITY)
                                .changed(ChangeDocuments.created(event.after()));
                    case EDITED ->
                        AuditFact.of("iam.role.changed", AuditClass.SECURITY)
                                .changed(ChangeDocuments.diff(event.before(), event.after()));
                    case RETIRED ->
                        AuditFact.of("iam.role.retired", AuditClass.SECURITY)
                                .changed(ChangeDocuments.diff(event.before(), event.after()));
                };
        audit.record(fact.by(ActorRef.user(event.actorSubject(), null))
                .at(ResourceScope.tenant(event.tenantId()))
                .target("Role", event.roleId())
                .because(event.reason())
                .usingCapability(Capability.IAM_ROLE_MANAGE.code())
                .correlatedBy(event.correlationId())
                .occurredAt(event.occurredAt())
                .build());
    }
}
