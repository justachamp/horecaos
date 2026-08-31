package uz.horecaos.platform.integration.camel.notification;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.notifications.api.NotificationDispatch;

/**
 * One provider-neutral notification command carried through the route (ADR 0007).
 *
 * <p>Two shapes in one type: a send carries the rendered dispatch, a status query
 * carries only the key it is asking about. They travel together because they share
 * a route, a circuit breaker, and a binding resolution, and splitting them would
 * duplicate all three.
 *
 * <p>{@code toString} is overridden for the same reason as on
 * {@link NotificationDispatch}: the dispatch holds a recipient and a rendered body,
 * and Camel prints exchange bodies into route logs and error messages by default.
 */
public record NotificationSendOperation(
        Kind kind,
        UUID tenantId,
        UUID brandId,
        @Nullable UUID locationId,
        String channel,
        @Nullable NotificationDispatch dispatch,
        String providerIdempotencyKey) {

    public enum Kind {
        SEND,
        QUERY_STATUS
    }

    public NotificationSendOperation {
        Objects.requireNonNull(kind, "A kind is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(channel, "A channel is required");
        Objects.requireNonNull(providerIdempotencyKey, "A provider idempotency key is required");
        if (kind == Kind.SEND && dispatch == null) {
            throw new IllegalArgumentException("A send needs a rendered dispatch");
        }
        if (dispatch != null && !tenantId.equals(dispatch.tenantId())) {
            // A dispatch from another tenant would text that tenant's customer
            // through this tenant's gateway. Checked here rather than trusted,
            // because the route has no other place to catch it.
            throw new IllegalArgumentException("The dispatch belongs to a different tenant");
        }
    }

    public static NotificationSendOperation send(NotificationDispatch dispatch) {
        return new NotificationSendOperation(
                Kind.SEND,
                dispatch.tenantId(),
                dispatch.brandId(),
                dispatch.locationId(),
                dispatch.channel(),
                dispatch,
                dispatch.providerIdempotencyKey());
    }

    public static NotificationSendOperation queryStatus(
            UUID tenantId, UUID brandId, @Nullable UUID locationId, String channel, String providerIdempotencyKey) {
        return new NotificationSendOperation(
                Kind.QUERY_STATUS, tenantId, brandId, locationId, channel, null, providerIdempotencyKey);
    }

    @Override
    public String toString() {
        return "NotificationSendOperation[kind=%s, channel=%s, key=%s]"
                .formatted(kind, channel, providerIdempotencyKey);
    }
}
