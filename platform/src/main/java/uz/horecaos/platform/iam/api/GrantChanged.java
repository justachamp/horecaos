package uz.horecaos.platform.iam.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A grant was created or revoked (ADR 0025).
 *
 * <p>Published rather than recorded directly. {@code iam} is the lowest layer in
 * the platform — it owns {@link ResourceScope} and the capability registry, and
 * {@code audit} depends on it — so calling the audit recorder from here would
 * make the two modules cyclic.
 *
 * <p>The listener records this before commit, so the audit fact still lands in
 * the same transaction as the grant, exactly as ADR 0027 requires. Decoupling
 * the modules does not weaken the guarantee.
 *
 * <p><strong>{@code correlationId} is what a bulk action is grouped by (Staff
 * 9.3c)</strong> — not {@code grantId}. A person's twelve grants revoked in
 * one "suspend" click are twelve separate {@code GrantChanged} events, each
 * with its own {@code grantId}; before this field existed, {@link
 * uz.horecaos.platform.audit.application.GrantAuditListener} correlated by
 * the grant's own id, so the twelve audit rows carried twelve different
 * correlation ids and pasting one into the activity log's filter returned
 * exactly one row instead of the whole batch. {@link
 * GrantManagementService#grant} and {@code #revoke} resolve this from the
 * request's own {@code X-Correlation-Id} (already in MDC via {@code
 * CorrelationIdFilter} by the time either method runs) and fall back to
 * {@code grantId} only when there is no request to read one from — a
 * system-initiated grant, say. The frontend fan-out that makes this matter is
 * {@code staff-page.ts}'s {@code suspend}/{@code restore}: one correlation id
 * minted once and sent on every call in the {@code Promise.allSettled} batch.
 */
public record GrantChanged(
        UUID grantId,
        Change change,
        String principalSubject,
        ResourceScope scope,
        String actorSubject,
        String reason,
        Map<String, Object> details,
        String correlationId,
        Instant occurredAt) {

    public enum Change {
        GRANTED,
        REVOKED
    }

    public GrantChanged {
        Objects.requireNonNull(grantId, "A grant id is required");
        Objects.requireNonNull(change, "A change is required");
        Objects.requireNonNull(principalSubject, "A principal subject is required");
        Objects.requireNonNull(scope, "A scope is required");
        Objects.requireNonNull(actorSubject, "An actor subject is required");
        Objects.requireNonNull(correlationId, "A correlation id is required");
        Objects.requireNonNull(occurredAt, "An occurrence time is required");
        details = details == null ? Map.of() : Map.copyOf(details);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A grant change requires a reason");
        }
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("A correlation id is required");
        }
    }

    /** The audit action code this maps to. */
    public String actionCode() {
        return change == Change.GRANTED ? "iam.grant.granted" : "iam.grant.revoked";
    }
}
