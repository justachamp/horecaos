package uz.horecaos.platform.audit.infrastructure;

import java.util.Objects;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.audit.StaffSecurityAudit;
import uz.horecaos.platform.iam.api.audit.StaffSecurityFact;

/**
 * {@code iam}'s audit port (ADR 0098) over ADR 0027's recorder.
 *
 * <p>Nothing but a translation, and it lives here rather than in {@code iam}
 * for the reason {@code iam.api.audit}'s own package note gives: {@code audit}
 * already depends on {@code iam} — its facts are scoped by
 * {@link ResourceScope} and its approvals by {@code iam}'s authorization — so
 * the adapter has to sit on this side of the boundary or the two modules become
 * cyclic.
 *
 * <p>The two things it fills in are the same for every fact that arrives here,
 * which is why {@code iam} does not carry them: a staff account's security
 * events are {@link AuditClass#SECURITY}, and they belong to the platform
 * rather than to a tenant, because a staff account is not a tenant-owned row.
 */
@Component
class RecordedStaffSecurityAudit implements StaffSecurityAudit {

    private final AuditRecorder recorder;

    RecordedStaffSecurityAudit(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void record(StaffSecurityFact fact) {
        recorder.record(AuditFact.of(fact.actionCode(), AuditClass.SECURITY)
                .by(actorOf(fact))
                .at(ResourceScope.platform())
                .target(fact.targetType(), fact.targetId())
                .because(fact.because())
                .changed(fact.changed())
                .correlatedBy(fact.correlationId())
                .occurredAt(fact.occurredAt())
                .build());
    }

    /**
     * {@code SERVICE} rather than {@code SYSTEM_JOB} for a surface: ADR 0027's
     * {@code Type} exists so a job is never mistaken for a person, and a
     * request an anonymous caller made through a sign-in page is neither. The
     * storefront's pre-authentication paths already record {@code
     * ActorRef.service("storefront-verification")} for the same reason.
     */
    private static ActorRef actorOf(StaffSecurityFact fact) {
        String subjectId = fact.staffSubjectId();
        if (subjectId != null) {
            return ActorRef.user(subjectId, null);
        }
        String service = fact.service();
        if (service != null) {
            return ActorRef.service(service);
        }
        return ActorRef.systemJob(Objects.requireNonNull(fact.systemJob(), "one attribution or another"));
    }
}
