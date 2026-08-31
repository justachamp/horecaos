package uz.horecaos.platform.web.idempotency;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One attempt at an effectful mutation (ADR 0031).
 *
 * @param scopeKey         names the operation, so one client key reused against
 *                         two operations does not collide
 * @param idempotencyKey   the client-supplied key, stable across retries
 * @param tenantId         tenant scope, null for platform-level operations
 * @param requestBody      the canonical body, hashed to detect key misuse
 * @param retention        how long the record is kept; longer for money
 */
public record IdempotencyRequest(
        String scopeKey,
        String idempotencyKey,
        @Nullable UUID tenantId,
        String principalSubject,
        String requestBody,
        Duration retention) {

    public IdempotencyRequest {
        Objects.requireNonNull(scopeKey, "A scope key is required");
        Objects.requireNonNull(principalSubject, "A principal subject is required");
        Objects.requireNonNull(requestBody, "A request body is required");
        Objects.requireNonNull(retention, "A retention period is required");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("An idempotency key is required");
        }
        if (idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("An idempotency key must be at most 255 characters");
        }
    }

    public static IdempotencyRequest of(
            String scopeKey,
            String idempotencyKey,
            @Nullable UUID tenantId,
            String principalSubject,
            String requestBody) {
        return new IdempotencyRequest(
                scopeKey,
                idempotencyKey,
                tenantId,
                principalSubject,
                requestBody,
                IdempotencyService.DEFAULT_RETENTION);
    }
}
