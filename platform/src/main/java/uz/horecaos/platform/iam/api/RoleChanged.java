package uz.horecaos.platform.iam.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A tenant-defined job was created, edited or retired (ADR 0186).
 *
 * <p>In-process only, like {@link GrantChanged}: {@code iam} is the lowest layer and {@code
 * audit} depends on it, so the audit fact is written by a listener that runs before commit,
 * in the same transaction as the change (ADR 0027). Nothing outside the process consumes it,
 * so it has no Kafka schema and no outbox row; a consumer that ever needs one adds the schema
 * in its own record (ADR 0032).
 *
 * @param before empty for {@link Change#CREATED}; the job's fields before the write otherwise
 * @param after  the job's fields once the change has happened. Names a job's name, level,
 *               status and capability codes only: no person, no phone, nothing ADR 0029
 *               classifies
 */
public record RoleChanged(
        UUID roleId,
        UUID tenantId,
        String roleCode,
        Change change,
        String actorSubject,
        String reason,
        Map<String, Object> before,
        Map<String, Object> after,
        String correlationId,
        Instant occurredAt) {

    public enum Change {
        CREATED,
        EDITED,
        RETIRED
    }

    public RoleChanged {
        Objects.requireNonNull(roleId, "A role id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(roleCode, "A role code is required");
        Objects.requireNonNull(change, "A change is required");
        Objects.requireNonNull(actorSubject, "An actor subject is required");
        Objects.requireNonNull(correlationId, "A correlation id is required");
        Objects.requireNonNull(occurredAt, "An occurrence time is required");
        before = before == null ? Map.of() : Map.copyOf(before);
        after = after == null ? Map.of() : Map.copyOf(after);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A role change requires a reason");
        }
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("A correlation id is required");
        }
    }
}
