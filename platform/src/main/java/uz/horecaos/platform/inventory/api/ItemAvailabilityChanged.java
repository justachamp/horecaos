package uz.horecaos.platform.inventory.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A binary-tracked item's availability was toggled at one location (ADR
 * 0017): a kitchen marking a dish sold out, or back on.
 *
 * <p>Symmetric by construction — {@code available} carries which direction —
 * because the fact itself is symmetric; {@code
 * uz.horecaos.platform.notifications.application.InventoryOperationsAlertTrigger}
 * is what narrows this to the ADR 0058 "stock-outs/86'd items" alert, on the
 * {@code available == false} transition alone. Carries {@code variantId}
 * only, never a product name: the same reasoning {@code MediaAssetAvailable}
 * gives for carrying an id and not a rendered field — "enough for a
 * consumer to decide whether it cares"; the trigger resolves a display name
 * through {@link uz.horecaos.platform.catalog.api.ItemDisplayLookup} rather
 * than this module reaching into catalog to pre-render one.
 *
 * <p>An in-process signal only, in the {@code payments.api.PaymentAttemptFailed}
 * genre: no ADR 0032 catalogue entry, never appended to the outbox.
 */
public record ItemAvailabilityChanged(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID variantId,
        boolean available,
        String reasonCode,
        Instant occurredAt) {

    public ItemAvailabilityChanged {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(variantId, "Variant ID is required");
        Objects.requireNonNull(reasonCode, "A reason code is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
