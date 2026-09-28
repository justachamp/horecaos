package uz.horecaos.platform.iam.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A support session was opened or ended (ADR 0081).
 *
 * <p>Published rather than recorded, for the reason {@link GrantChanged} gives:
 * {@code audit} depends on {@code iam}, so the audit listener records this
 * before commit, in the same transaction as the session and its grant.
 *
 * @param actorSubject who did it: the support person opening, or whoever
 *                     ended it — the person themselves, a platform
 *                     administrator, or the tenant's own administrator
 * @param before empty for a fresh {@link Change#OPENED} session, which has
 *               no prior state; the session's own prior field values for
 *               {@link Change#ENDED}. Staff 9.3a: {@code audit}, not {@code
 *               iam}, owns {@code ChangeDocuments}, so {@link
 *               uz.horecaos.platform.audit.application.SupportSessionAuditListener}
 *               is the one that builds the audit fact's before/after diff
 *               from this and {@link #after}.
 * @param after  the fields as they stand once this change has happened
 */
public record SupportSessionChanged(
        UUID sessionId,
        UUID tenantId,
        Change change,
        String principalSubject,
        String actorSubject,
        String reason,
        Map<String, Object> before,
        Map<String, Object> after,
        Instant occurredAt) {

    public enum Change {
        OPENED,
        ENDED
    }

    public String actionCode() {
        return change == Change.OPENED ? "iam.support_session.opened" : "iam.support_session.ended";
    }
}
