package uz.horecaos.platform.notifications.api;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
    List<UUID> subscribedBindings(UUID tenantId, UUID brandId, @Nullable UUID locationId, String eventClass);

    /**
     * Every {@code OPERATIONS}-audience binding subscribed to {@code eventClass}
     * anywhere in the tenant, with the scope it was bound at.
     *
     * <p>What a tenant-wide supervisor digest fans out to (ADR 0058): a digest
     * has no single order's brand/location to scope {@link #subscribedBindings}
     * against, and a flat operations group wants the tenant's own numbers, or —
     * for the 15-minute live count — the numbers for the scope it was bound at.
     */
    List<ScopedBinding> tenantDigestBindings(UUID tenantId, String eventClass);

    /**
     * Every {@code PLATFORM}-audience binding subscribed to {@code eventClass},
     * platform-wide rather than filtered to one tenant (ADR 0058).
     */
    List<ScopedBinding> platformDigestBindings(String eventClass);

    /**
     * A binding with the scope it was bound at, and the tenant row it lives
     * under. {@code brandId} is never null in practice: ADR 0026's {@code
     * ck_binding_scope}/{@code ck_binding_location_implies_brand} pair forces
     * every binding to name a brand, with or without a location under it.
     */
    record ScopedBinding(
            UUID tenantId,
            UUID bindingId,
            UUID brandId,
            @Nullable UUID locationId) {}
}
