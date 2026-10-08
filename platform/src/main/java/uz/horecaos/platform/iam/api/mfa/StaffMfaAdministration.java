package uz.horecaos.platform.iam.api.mfa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How {@code audit} and the consoles reach a person's second factor without
 * {@code iam} depending back on them (ADR 0148): read where it stands, and reset it.
 *
 * <p>Implemented by {@code iam}. The platform-scope reset's second signature (ADR 0027, ADR 0050)
 * is the {@code audit} module's to ask, for the reason {@code iam.api.grants.PlatformGrantAuthority}
 * gives: an approval gate in {@code iam} would close a cycle. What {@code iam} owns is the act
 * itself, and nothing here ever carries a secret, a code or a token.
 */
public interface StaffMfaAdministration {

    /** Whether this subject holds an active {@code PLATFORM}-scope grant: a platform account. */
    boolean isPlatformAccount(String subjectId);

    /**
     * The person's second factor as the identity provider holds it, cached for sixty seconds
     * (ADR 0033) and evicted by every enrolment, removal and reset. Empty when the identity
     * provider has no account under the subject.
     */
    Optional<MfaStatus> status(String subjectId);

    /**
     * Removes every authenticator, ends every session, records an ADR 0027 fact with the reason
     * and emails the person (ADR 0148, Decision 5). The recorded reason is the administrator's
     * words; nothing identifying the authenticator is.
     *
     * @throws IllegalStateException when the identity provider has no such account
     */
    ResetResult reset(ResetCommand command);

    enum Requirement {
        NOT_REQUIRED,
        OFFERED,
        REQUIRED
    }

    /** One authenticator. {@code label} is whatever the person typed and is shown to an administrator only. */
    record Authenticator(
            String id, @Nullable String label, @Nullable Instant createdAt) {

        @Override
        public String toString() {
            return "Authenticator[id=" + id + "]";
        }
    }

    record MfaStatus(boolean enrolled, List<Authenticator> authenticators, Requirement requirement) {}

    /**
     * @param tenantId the tenant whose route asked, for the ADR 0027 fact's scope; null for a
     *     platform-scope reset
     */
    record ResetCommand(
            String subjectId,
            String actorSubject,
            String reason,
            @Nullable UUID tenantId) {}

    record ResetResult(int authenticatorsRemoved, boolean sessionsEnded, boolean personNotified) {}
}
