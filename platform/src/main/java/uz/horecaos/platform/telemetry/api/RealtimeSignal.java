package uz.horecaos.platform.telemetry.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * "Something in this scope changed" (ADR 0045).
 *
 * <p><strong>A signal is not state.</strong> It names a channel, a scope, a
 * resource, and a version, and the client re-reads that resource through the
 * ordinary authorized API. That is the property that makes the stream unable to
 * leak what the API would not return, because the API is what returns it — and
 * it is the property a full-state frame would destroy: a stream opened while the
 * principal held a location grant would keep emitting order contents after the
 * grant was revoked.
 *
 * <p><strong>Nothing here is personal data, and that is structural rather than
 * careful.</strong> This record is the payload on the {@code realtime.signals}
 * topic, and ADR 0032 forbids anything above {@code INTERNAL} on any topic. A
 * moving courier produces a signal carrying a courier id, a time, and a scope
 * key; the replica that receives it reads the live row it already has access to
 * and pushes a snapshot to the subscribers authorized for that location. No
 * coordinate, accuracy, heading, speed, or battery value is reachable from this
 * type, which {@code CourierTelemetryPrivacyTests} asserts with ADR 0029's
 * structural scanner rather than by reading it.
 *
 * @param version the resource's version after the change, so a client that has
 *                already read a newer copy can drop the frame instead of
 *                re-reading; absent where the source has no version
 */
public record RealtimeSignal(
        UUID signalId,
        UUID tenantId,
        StreamChannel channel,
        ScopeKey scopeKey,
        String resourceType,
        UUID resourceId,
        Long version,
        Instant occurredAt) {

    public RealtimeSignal {
        Objects.requireNonNull(signalId, "A signal id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(channel, "A channel is required");
        Objects.requireNonNull(scopeKey, "A scope key is required");
        Objects.requireNonNull(resourceType, "A resource type is required");
        Objects.requireNonNull(occurredAt, "An occurrence time is required");
        if (!channel.isSubscribableAt(scopeKey.type())) {
            throw new IllegalArgumentException(
                    "Channel %s is not carried at %s scope".formatted(channel, scopeKey.type()));
        }
    }

    public static RealtimeSignal of(
            UUID tenantId,
            StreamChannel channel,
            ScopeKey scopeKey,
            String resourceType,
            UUID resourceId,
            Long version,
            Instant occurredAt) {
        return new RealtimeSignal(
                UUID.randomUUID(), tenantId, channel, scopeKey, resourceType, resourceId, version, occurredAt);
    }

    /** What a subscription is matched on: one channel at one scope. */
    public Subscription subscription() {
        return new Subscription(channel, scopeKey);
    }

    /** The pair a client subscribes to and a signal is routed by. */
    public record Subscription(StreamChannel channel, ScopeKey scopeKey) {

        public Subscription {
            Objects.requireNonNull(channel, "A channel is required");
            Objects.requireNonNull(scopeKey, "A scope key is required");
        }
    }
}
