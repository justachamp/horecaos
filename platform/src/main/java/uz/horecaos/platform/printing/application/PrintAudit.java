package uz.horecaos.platform.printing.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;

/**
 * The ADR 0027 facts printing writes (ADR 0154, "Personal data, audit and security"):
 * {@code printing.printer.registered|changed|retired}, {@code printing.route.changed},
 * {@code printing.profile.changed} (the footer redacted to its length), {@code printing.job.reprinted} and
 * the agent's enrolment and revocation.
 *
 * <p>An automatic job has no fact of its own: its row names the route and the triggering fact. A person who
 * asks for paper does, and is named through the staff directory (ADR 0139). Written in the same transaction
 * as the change it describes, and every document is a {@link ChangeDocuments} before and after.
 */
@Component
public class PrintAudit {

    private final AuditRecorder audit;
    private final StaffDirectory staff;
    private final Clock clock;

    public PrintAudit(AuditRecorder audit, StaffDirectory staff, Clock clock) {
        this.audit = audit;
        this.staff = staff;
        this.clock = clock;
    }

    /** What a fact is about: who did it, where, and to which row. */
    public record Subject(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            String targetType,
            UUID targetId,
            String actorSubject,
            @Nullable String reason) {}

    /** A row that did not exist a moment ago. */
    void created(String action, AuditClass auditClass, Subject subject, Map<String, Object> after) {
        audit.record(prepared(action, auditClass, subject)
                .changed(ChangeDocuments.created(after))
                .build());
    }

    /** A row that changed from {@code before} to {@code after}. */
    void changed(
            String action,
            AuditClass auditClass,
            Subject subject,
            Map<String, Object> before,
            Map<String, Object> after) {
        audit.record(prepared(action, auditClass, subject)
                .changed(ChangeDocuments.diff(before, after))
                .build());
    }

    private AuditFact.Builder prepared(String action, AuditClass auditClass, Subject subject) {
        ResourceScope scope = subject.locationId() == null
                ? ResourceScope.brand(subject.tenantId(), subject.brandId())
                : ResourceScope.location(subject.tenantId(), subject.brandId(), subject.locationId());
        return AuditFact.of(action, auditClass)
                .by(ActorRef.user(subject.actorSubject(), staff.nameOf(subject.tenantId(), subject.actorSubject())))
                .at(scope)
                .target(subject.targetType(), subject.targetId())
                .outcome(AuditFact.Outcome.SUCCEEDED)
                .because(subject.reason())
                .correlatedBy(subject.targetId().toString())
                .occurredAt(clock.instant());
    }
}
