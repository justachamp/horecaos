package uz.horecaos.platform.commercial.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One thing that happened, expressed as a signed movement (ADR 0021).
 *
 * <p>{@code sourceType} and {@code sourceEventId} together are the idempotency
 * key. A consumer that replays its inbox, a retry after a timeout, and an
 * out-of-order redelivery all present the same pair and the second one is
 * dropped, which is what makes the ledger safe to feed from at-least-once
 * delivery.
 *
 * @param tenantId       the tenant that consumed
 * @param key            the entitlement being metered
 * @param quantity       signed, never zero; -1 is a location removed
 * @param sourceType     the kind of thing that produced this, e.g. {@code ordering.OrderConfirmed}
 * @param sourceEventId  that thing's own identifier, unique within its source type
 * @param occurredAt     when it happened, which decides the period it counts against
 * @param dimensions     allowlisted, non-personal breakdown values (ADR 0029)
 */
public record UsageMovement(
        UUID tenantId,
        EntitlementKey<Long> key,
        long quantity,
        String sourceType,
        String sourceEventId,
        Instant occurredAt,
        Map<String, String> dimensions) {

    public UsageMovement {
        Objects.requireNonNull(tenantId, "A tenant is required");
        Objects.requireNonNull(key, "An entitlement key is required");
        Objects.requireNonNull(sourceType, "A source type is required");
        Objects.requireNonNull(sourceEventId, "A source event id is required");
        Objects.requireNonNull(occurredAt, "An occurrence time is required");
        dimensions = Map.copyOf(Objects.requireNonNull(dimensions, "Dimensions are required"));

        if (quantity == 0) {
            throw new IllegalArgumentException("A movement of zero is not a movement: " + key.code());
        }
        // The allowlist is checked here rather than at the database, because the
        // failure it prevents is a customer identifier reaching a column that is
        // append-only and can therefore never be scrubbed (ADR 0029).
        for (String dimension : dimensions.keySet()) {
            if (!key.permitsDimension(dimension)) {
                throw new IllegalArgumentException(
                        "Dimension \"%s\" is not allowlisted for %s".formatted(dimension, key.code()));
            }
        }
    }

    public static UsageMovement of(
            UUID tenantId,
            EntitlementKey<Long> key,
            long quantity,
            String sourceType,
            String sourceEventId,
            Instant occurredAt) {
        return new UsageMovement(tenantId, key, quantity, sourceType, sourceEventId, occurredAt, Map.of());
    }
}
