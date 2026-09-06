package uz.horecaos.platform.inventory.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A versioned business fact emitted by the inventory module (ADR 0017, ADR 0032).
 *
 * <p>Sealed, in the {@code MediaEvent} / {@code VoiceEvent} genre, so a future
 * subtype cannot reach {@link uz.horecaos.platform.integration.outbox.InventoryOutboxEventListener}
 * without a catalogue entry, a schema, and a documentation row — the listener
 * calls {@code EventCatalog.require} before it appends anything, but this
 * interface is what {@code EventCatalogCompletenessTests}-style coverage would
 * enumerate to prove that requirement actually reaches every publishable
 * inventory event, not only the one wired so far.
 *
 * <p>ADR 0017 names seven inventory facts. Only {@link ItemAvailabilityChanged}
 * is published today; the ADR's implementation status names the reservation
 * lifecycle events ({@code InventoryReserved}, {@code
 * InventoryReservationCommitted}, {@code InventoryReservationReleased}, {@code
 * InventoryReservationExpired}) and the position/reconciliation facts ({@code
 * InventoryPositionChanged}, {@code InventoryReconciliationRequired}) as not
 * built — their payload shape is not yet decided (a reservation line list? a
 * quantity delta?) and inventing one to close this gap would be exactly the
 * kind of unreviewed contract ADR 0032 exists to prevent. A contract with no
 * producer is a promise this repository has not made; see {@code
 * docs/domains/events.md}'s own note on {@code MediaAssetAvailable}'s five
 * unpublished siblings for the same restraint applied there first.
 */
public sealed interface InventoryEvent permits ItemAvailabilityChanged {

    UUID eventId();

    String eventType();

    int eventVersion();

    UUID tenantId();

    UUID variantId();

    Instant occurredAt();

    Object payload();

    /**
     * {@code Variant}, not {@code StockItem}: the catalog identifier a
     * consumer or an auditor would actually search for, matching {@link
     * uz.horecaos.platform.inventory.application.InventoryService#setAvailabilityAudited}'s
     * own choice of audit target for the identical reason.
     */
    default String aggregateType() {
        return "Variant";
    }

    default UUID aggregateId() {
        return variantId();
    }
}
