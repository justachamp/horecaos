package uz.horecaos.platform.catalog.api;

import java.util.List;
import java.util.UUID;

/**
 * The 86 list's read side, for a consumer outside {@code catalog}
 * (ADR 0060 §3's bot {@code /86} typed command).
 *
 * <p>{@code available} here is inventory's own binary-availability fact,
 * joined with the product name by {@code CatalogAuthoringService
 * #variantsAtLocation} in one query — the same join catalog.md §4.6's read
 * screen uses. The toggle itself is not this port: 86'ing a dish is an
 * inventory fact ("a kitchen marking a dish sold out, or back on"), so it is
 * {@code inventory.api.StockAvailabilityPort#toggle}, not a method here.
 * {@code TelegramUpdateHandler} calls both — this one to render the list and
 * resolve a typed reference to a variant id, the other to flip it.
 */
public interface StopListPort {

    /** Every sellable variant at one location, with whether it can be sold right now. */
    List<Item> listAtLocation(UUID tenantId, UUID brandId, UUID locationId);

    record Item(UUID variantId, String productName, boolean available) {}
}
