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
 * @param staffSubjectId the staff member, when a person acted; null otherwise
 * @param systemJob the job, when the platform acted on a schedule; null otherwise
 * @param service the surface, when an unauthenticated caller acted through it; null otherwise
 */
public record StaffSecurityFact(
        String actionCode,
        @Nullable String staffSubjectId,
        @Nullable String systemJob,
        @Nullable String service,
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
        long attributions = (staffSubjectId == null ? 0 : 1) + (systemJob == null ? 0 : 1) + (service == null ? 0 : 1);
        if (attributions != 1) {
            // A person did this, or the platform did on a schedule, or an
            // unauthenticated caller did through one of its surfaces. "More
            // than one" is a fact nobody can act on, and "none" is an
            // actor-less audit record, which ADR 0027 has no shape for.
            throw new IllegalArgumentException(
                    "A fact is attributed to a staff member, a system job or a service, and to exactly one of them");
        }
    }

    /**
     * Something the staff member themselves caused.
     *
     * <p>Only where the platform has evidence that they did: a session, or
     * possession of a one-time token emailed to their own address. An
     * unauthenticated caller naming an account is {@link #byService}.
     */
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
                actionCode, subjectId, null, null, targetType, targetId, because, changed, correlationId, occurredAt);
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
                actionCode, null, job, null, targetType, targetId, because, changed, correlationId, occurredAt);
    }

    /**
     * Something an unauthenticated caller asked one of the platform's own
     * surfaces to do (ADR 0098).
     *
     * <p>The actor is the surface, because that is all the platform knows. A
     * reset requested from a sign-in page names an account, but nobody proved
     * they hold it -- recording the account's own subject as the actor would
     * let a stranger write, ten times a minute, an append-only record saying
     * that the person whose address they typed asked for this themselves.
     * The account is still reachable from the fact: the target is the reset
     * row, which carries the subject.
     */
    public static StaffSecurityFact byService(
            String actionCode,
            String service,
            String targetType,
            UUID targetId,
            String because,
            Map<String, Object> changed,
            String correlationId,
            Instant occurredAt) {
        return new StaffSecurityFact(
                actionCode, null, null, service, targetType, targetId, because, changed, correlationId, occurredAt);
    }
}
