package uz.horecaos.platform.iam.api.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One thing that happened to a staff account, for the audit trail (ADR 0098,
 * ADR 0027).
 *
 * <p>A deliberately small shape rather than a copy of {@code AuditFact}: this
 * is what {@code iam} needs recorded about its own security events, and the
 * adapter on the {@code audit} side fills in the rest — the security
 * classification and the platform scope, which are the same for every fact
 * that travels this way.
 *
 * <p>{@code changed} is a fact about the change, never about the person: no
 * address, no login, no token (ADR 0029). The subject id is an opaque
 * identifier the identity provider issued, which is what the audit trail keys
 * an actor by everywhere else.
 *
 * @param staffSubjectId the staff member, when a person acted; null when the platform did
 * @param systemJob the job, when the platform acted; null when a person did
 */
public record StaffSecurityFact(
        String actionCode,
        @Nullable String staffSubjectId,
        @Nullable String systemJob,
        String targetType,
        UUID targetId,
        String because,
        Map<String, Object> changed,
        String correlationId,
        Instant occurredAt) {

    public StaffSecurityFact {
        Objects.requireNonNull(actionCode, "An action code is required");
        Objects.requireNonNull(targetType, "A target type is required");
        Objects.requireNonNull(targetId, "A target is required");
        Objects.requireNonNull(because, "A reason is required");
        Objects.requireNonNull(correlationId, "A correlation id is required (ADR 0027)");
        Objects.requireNonNull(occurredAt, "An instant is required");
        changed = Map.copyOf(Objects.requireNonNull(changed, "A changed map is required"));
        if ((staffSubjectId == null) == (systemJob == null)) {
            // Either a person did this or the platform did. "Both" is a fact
            // nobody can act on, and "neither" is an actor-less audit record,
            // which ADR 0027 does not have a shape for.
            throw new IllegalArgumentException("A fact is attributed to a staff member or to a system job, not both");
        }
    }

    /** Something the staff member themselves caused. */
    public static StaffSecurityFact byStaffMember(
            String actionCode,
            String subjectId,
            String targetType,
            UUID targetId,
            String because,
            Map<String, Object> changed,
            String correlationId,
            Instant occurredAt) {
        return new StaffSecurityFact(
                actionCode, subjectId, null, targetType, targetId, because, changed, correlationId, occurredAt);
    }

    /** Something the platform did on its own, on a schedule. */
    public static StaffSecurityFact bySystemJob(
            String actionCode,
            String job,
            String targetType,
            UUID targetId,
            String because,
            Map<String, Object> changed,
            String correlationId,
            Instant occurredAt) {
        return new StaffSecurityFact(
                actionCode, null, job, targetType, targetId, because, changed, correlationId, occurredAt);
    }
}
