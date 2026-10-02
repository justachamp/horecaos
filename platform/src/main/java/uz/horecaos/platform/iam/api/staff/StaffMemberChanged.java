package uz.horecaos.platform.iam.api.staff;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Something happened to a staff member record, for the audit trail (ADR 0139,
 * ADR 0027).
 *
 * <p>Published rather than recorded directly, for the reason {@link
 * uz.horecaos.platform.iam.api.GrantChanged} gives: {@code iam} is the lowest
 * layer and {@code audit} already depends on it, so calling the recorder from
 * here would close a module cycle. The listener records this before commit, so
 * the fact still lands in the transaction that made the change.
 *
 * <p><strong>No personal value travels in this event.</strong> {@code before}
 * and {@code after} carry the staff member's non-personal fields as they are,
 * and for a personal field (name, phone, employee number, an emergency contact)
 * only that it was set or cleared -- a presence marker, never the value. The
 * audit side builds the change document with {@code ChangeDocuments.diff}, whose
 * shared redaction set turns those keys into {@code [redacted]} markers, so the
 * trail shows that a phone changed and never what it changed to. Keeping the
 * values out of the event as well means no listener, however it is later wired,
 * can leak one (ADR 0029, ADR 0032).
 *
 * @param actorSubject the person acting; null when {@code systemJob} acted
 * @param systemJob    the scheduled job, when the platform acted on its own
 * @param capabilityUsed the capability the act was authorised by, or null for
 *                     a self-service act and for the platform's own jobs
 */
public record StaffMemberChanged(
        String actionCode,
        UUID tenantId,
        @Nullable String actorSubject,
        @Nullable String systemJob,
        UUID memberId,
        String reason,
        @Nullable String capabilityUsed,
        @Nullable Long targetVersion,
        Map<String, Object> before,
        Map<String, Object> after,
        String correlationId,
        Instant occurredAt) {

    public StaffMemberChanged {
        Objects.requireNonNull(actionCode, "An action code is required");
        Objects.requireNonNull(tenantId, "A tenant is required");
        Objects.requireNonNull(memberId, "A member is required");
        Objects.requireNonNull(reason, "A reason is required");
        Objects.requireNonNull(correlationId, "A correlation id is required (ADR 0027)");
        Objects.requireNonNull(occurredAt, "An instant is required");
        // Map.copyOf refuses nulls, and "the field was previously unset" is
        // evidence that must stay distinguishable from "redacted" (ChangeDocuments).
        before = before == null ? Map.of() : new java.util.LinkedHashMap<>(before);
        after = after == null ? Map.of() : new java.util.LinkedHashMap<>(after);
        if ((actorSubject == null) == (systemJob == null)) {
            throw new IllegalArgumentException(
                    "A fact is attributed to a person or to a system job, and to exactly one");
        }
    }
}
