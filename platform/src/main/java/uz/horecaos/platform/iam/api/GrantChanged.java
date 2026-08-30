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
 */
public record GrantChanged(
        UUID grantId,
        Change change,
        String principalSubject,
        ResourceScope scope,
        String actorSubject,
        String reason,
        Map<String, Object> details,
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
        Objects.requireNonNull(occurredAt, "An occurrence time is required");
        details = details == null ? Map.of() : Map.copyOf(details);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A grant change requires a reason");
        }
    }

    /** The audit action code this maps to. */
    public String actionCode() {
        return change == Change.GRANTED ? "iam.grant.granted" : "iam.grant.revoked";
    }
}
