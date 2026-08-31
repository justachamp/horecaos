package uz.horecaos.platform.inventory.api;

import java.util.UUID;

/**
 * The 86 toggle itself, for a consumer outside {@code inventory}
 * (ADR 0060 §3's bot {@code /86} typed command).
 *
 * <p>Narrow by design, matching {@link InventoryReservationPort}'s own
 * shape: one write, nothing about stock listing or the reservation path.
 * {@code InventoryController} keeps calling {@code InventoryService}
 * directly, in-module; this port exists only because {@code integration}'s
 * {@code TelegramUpdateHandler} cannot reach {@code inventory.application}
 * at all under Spring Modulith.
 */
public interface StockAvailabilityPort {

    /**
     * Flips a BINARY-tracked variant's availability and records the ADR 0027
     * audit fact — see {@code InventoryService#setAvailabilityAudited}, the
     * one call site both this adapter and the web controller share.
     *
     * @throws IllegalArgumentException if the variant is not stocked at this location
     * @throws IllegalStateException if the variant is not BINARY-tracked
     */
    void toggle(
            UUID tenantId, UUID locationId, UUID variantId, boolean available, String reasonCode, String actorSubject);
}
