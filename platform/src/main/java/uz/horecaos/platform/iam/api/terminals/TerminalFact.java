package uz.horecaos.platform.iam.api.terminals;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * An ADR 0027 SECURITY fact about a terminal, published by {@code iam} and recorded by {@code audit} in the same
 * transaction ({@code iam} is the lowest layer and {@code audit} already depends on it: a direct call the other way
 * would make the modules cyclic, which is why grants are recorded the same way).
 *
 * <p>The change document is built by {@code audit} through {@code ChangeDocuments}; the maps here hold non-secret facts
 * only. A PIN, its digest, an activation code and a session token are never in {@code before} or {@code after}: they do
 * not exist in this record to be redacted.
 *
 * @param actorSubject the person who acted: the issuer of an activation, a manager who reset a PIN, the person who
 *                     unlocked. Null for a fact the platform raised about a device (a lock raised by the guesser's
 *                     own attempts names the device, never a person)
 * @param deviceId     the device the fact happened at, when there is one
 */
public record TerminalFact(
        String actionCode,
        UUID tenantId,
        @Nullable UUID locationId,
        @Nullable UUID brandId,
        @Nullable String actorSubject,
        String targetType,
        UUID targetId,
        @Nullable UUID deviceId,
        String reason,
        @Nullable String capabilityUsed,
        @Nullable String evidenceReference,
        Map<String, Object> before,
        Map<String, Object> after,
        String correlationId,
        Instant occurredAt) {

    public TerminalFact {
        Objects.requireNonNull(actionCode, "An action code is required");
        Objects.requireNonNull(tenantId, "A tenant is required");
        Objects.requireNonNull(targetType, "A target type is required");
        Objects.requireNonNull(targetId, "A target is required");
        Objects.requireNonNull(reason, "A reason is required");
        Objects.requireNonNull(correlationId, "A correlation id is required (ADR 0027)");
        Objects.requireNonNull(occurredAt, "An instant is required");
        before = before == null ? Map.of() : new java.util.LinkedHashMap<>(before);
        after = after == null ? Map.of() : new java.util.LinkedHashMap<>(after);
    }

    /** The scope the fact is recorded at: the branch when known, else the tenant. */
    public ResourceScope scope() {
        if (locationId != null && brandId != null) {
            return ResourceScope.location(tenantId, brandId, locationId);
        }
        return ResourceScope.tenant(tenantId);
    }
}
