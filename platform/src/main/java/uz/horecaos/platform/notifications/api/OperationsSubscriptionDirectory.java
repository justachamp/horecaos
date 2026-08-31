package uz.horecaos.platform.notifications.api;

import java.util.List;
import java.util.UUID;

/**
 * Which ADR 0026 provider bindings want a given operations event class, at a
 * scope (ADR 0058).
 *
 * <p>The seam that keeps {@code notifications} ignorant of Telegram, exactly the
 * way {@link NotificationTransport} keeps it ignorant of Camel: a trigger names
 * an event class and a scope, and how many chats that fans out to — today,
 * always Telegram bindings — is an {@code integration} answer.
 */
public interface OperationsSubscriptionDirectory {

    /**
     * Every ADR 0026 binding subscribed to {@code eventClass} at this scope,
     * as a {@code provider_binding_id} — what
     * {@code notifications.recipient_endpoints.provider_binding_id} names.
     */
    List<UUID> subscribedBindings(UUID tenantId, UUID brandId, UUID locationId, String eventClass);
}
