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
     * The bot's {@code /86}: flips a BINARY-tracked variant's availability and records the
     * ADR 0027 audit fact — see {@code InventoryService#setAvailabilityAudited}, the one call
     * site both this adapter and the web controller share. The movement and any stop carry
     * the source {@code BOT} (ADR 0141 Decision 4).
     *
     * <p>A variant that is not BINARY-tracked is stopped with a {@code LOCATION} stop from
     * the bot, and "back on sale" ends the bot's own stop and no one else's. Where new stops
     * are paused (ADR 0141's freeze switch), such a variant is refused as it always was.
     *
     * @throws IllegalArgumentException if the variant is not stocked at this location
     * @throws IllegalStateException if the variant is not BINARY-tracked and cannot be stopped
     */
    void toggle(
            UUID tenantId, UUID locationId, UUID variantId, boolean available, String reasonCode, String actorSubject);
}
