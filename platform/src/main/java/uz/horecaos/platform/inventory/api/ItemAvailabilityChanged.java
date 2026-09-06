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
 * <p>Also an {@link InventoryEvent} — the ADR 0032 {@code
 * InventoryAvailabilityChanged} contract: {@code
 * uz.horecaos.platform.integration.outbox.InventoryOutboxEventListener}
 * appends it to {@code inventory.events} in the same {@code BEFORE_COMMIT}
 * transaction as the toggle, alongside the in-process {@link
 * uz.horecaos.platform.notifications.application.InventoryOperationsAlertTrigger}
 * listener this same publish already reaches — Spring dispatches one
 * {@code ApplicationEvent} to every listener registered for its type, so
 * promoting this record to an outbox event needed no change to {@link
 * uz.horecaos.platform.inventory.application.InventoryService#setAvailability}
 * itself, only this record gaining a catalogue-ready shape.
 */
public record ItemAvailabilityChanged(
        UUID eventId,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID variantId,
        boolean available,
        String reasonCode,
        Instant occurredAt)
        implements InventoryEvent {

    public ItemAvailabilityChanged {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(variantId, "Variant ID is required");
        Objects.requireNonNull(reasonCode, "A reason code is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }

    @Override
    public String eventType() {
        return "InventoryAvailabilityChanged";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public Object payload() {
        return new Payload(variantId, locationId, available, reasonCode);
    }

    /**
     * Never {@code brandId}: a consumer holding a {@code locationId} can
     * already resolve its brand through the authorized tenancy API, and the
     * payload stays the same shape whether or not that lookup is cheap. Never
     * a product or variant name, for the reason this record's own class doc
     * gives.
     */
    public record Payload(UUID variantId, UUID locationId, boolean available, String reasonCode) {}
}
