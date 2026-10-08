package uz.horecaos.platform.integration.camel.notification;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What the platform can tell a gateway when asking what became of a send
 * (ADR 0146 Decision 2, {@code resolve}).
 *
 * <p>Only the fields a gateway can actually use are filled: a provider that holds
 * our idempotency key reads {@code providerIdempotencyKey}; one that can only be
 * searched by number and day reads {@code destination}, and compares what it finds
 * to {@code renderedContentHash} or to {@code providerMessageId} when the send's
 * answer gave one.
 *
 * <p>{@code toString} omits the destination, as {@code NotificationDispatch} does.
 */
public record ResolveRequest(
        String providerIdempotencyKey,
        @Nullable String providerMessageId,
        @Nullable String destination,
        @Nullable String renderedContentHash,
        Instant requestedAt) {

    @Override
    public String toString() {
        return "ResolveRequest[key=%s]".formatted(providerIdempotencyKey);
    }
}
