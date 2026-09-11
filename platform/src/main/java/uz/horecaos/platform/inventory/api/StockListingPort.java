package uz.horecaos.platform.inventory.api;

import java.util.UUID;

/**
 * Listing a variant as stocked at a location, for a consumer outside {@code
 * inventory} (ADR 0017, ADR 0099).
 *
 * <p>Separate from {@link StockAvailabilityPort}, whose own javadoc says it
 * carries "one write, nothing about stock listing" — this is that other write,
 * and folding it in would make both ports mean less. Narrow in the same way:
 * nothing about quantities, positions, movements or reservations.
 *
 * <p>It exists because an item with no {@code inventory.stock_items} row is
 * unavailable rather than available ({@code InventoryService#checkAvailability}
 * says so deliberately), so a menu that is published, offered and priced still
 * cannot be sold until something lists it. ADR 0099's sample menu is the one
 * caller today: {@code ACTIVATION_SMOKE_TEST} checks exactly this, and would
 * fail a sample menu that skipped it.
 */
public interface StockListingPort {

    /**
     * Lists a variant at a location as {@code BINARY}-tracked and available, or
     * confirms it already is listed.
     *
     * <p>Idempotent: a variant that already has a stock item here is left exactly
     * as it is, including a deliberate sold-out state. Re-listing it would
     * silently put a dish back on that a kitchen had taken off.
     *
     * @return whether this call created the stock item
     */
    boolean ensureListed(UUID tenantId, UUID brandId, UUID locationId, UUID variantId);
}
