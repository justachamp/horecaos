package uz.qoida.platform.audit.api;

import java.util.Objects;

/**
 * Who caused an audited action (ADR 0027).
 *
 * <p>{@link Type} exists so a backfill or a scheduled job is never mistaken for
 * a person during an investigation, which is the most common way an audit trail
 * misleads.
 */
public record ActorRef(Type type, String subject, String displayName, String onBehalfOfSubject) {

    public enum Type {
        USER,
        SERVICE,
        SYSTEM_JOB,
        MIGRATION
    }

    public ActorRef {
        Objects.requireNonNull(type, "An actor type is required");
        if (type == Type.USER && (subject == null || subject.isBlank())) {
            throw new IllegalArgumentException("A user actor requires a subject");
        }
    }

    public static ActorRef user(String subject, String displayName) {
        return new ActorRef(Type.USER, subject, displayName, null);
    }

    public static ActorRef service(String subject) {
        return new ActorRef(Type.SERVICE, subject, null, null);
    }

    public static ActorRef systemJob(String jobName) {
        return new ActorRef(Type.SYSTEM_JOB, jobName, null, null);
    }

    public static ActorRef migration(String runReference) {
        return new ActorRef(Type.MIGRATION, runReference, null, null);
    }

    /** Support acting for a customer: both identities are recorded. */
    public ActorRef onBehalfOf(String subject) {
        return new ActorRef(type, this.subject, displayName, subject);
    }
}
