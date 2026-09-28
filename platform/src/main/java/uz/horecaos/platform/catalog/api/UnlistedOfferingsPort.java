package uz.horecaos.platform.catalog.api;

import java.util.List;
import java.util.UUID;

/**
 * The backfill read {@link OfferingBecameAvailable}'s listener cannot answer
 * (gap-map row 4.1's pilot-critical follow-up): every offering this catalog
 * already set {@code AVAILABLE} before that event existed, or that its
 * best-effort {@code AFTER_COMMIT} listener missed, still has no {@code
 * inventory.stock_items} row and still reads as unsellable.
 *
 * <p>Implemented by {@code catalog} (the module that owns {@code
 * location_offerings}), consumed by {@code inventory} — the direction {@code
 * inventory} already depends in, through {@code MenuAvailabilityLookup}. Both
 * reads join {@code inventory.stock_items} directly in SQL rather than
 * through a Java import, the same "read another module's schema directly"
 * technique {@code JdbcCatalogStore#variantsAtLocation} already uses for the
 * console's own stock page (gap-map row 4.4c) — a read, never a write, is the
 * boundary compromise; writing {@code inventory}'s tables from here would be
 * a second implementation of {@code InventoryService}'s own listing rules.
 */
public interface UnlistedOfferingsPort {

    /**
     * Every variant this location offers {@code AVAILABLE} that inventory has
     * never listed, most recently offered first.
     *
     * @param limit the most variant ids to return in one call; a result at
     *              the cap means the location may still have more, and the
     *              operations backfill endpoint calling this reports as much
     *              rather than claiming the location is fully caught up
     */
    List<UUID> unlistedAvailableVariantsAtLocation(UUID tenantId, UUID brandId, UUID locationId, int limit);

    /**
     * Every location of this brand that offers this variant {@code
     * AVAILABLE} but has never listed it — the product editor's "not listed
     * at N branches" read.
     *
     * @param limit the most location ids to return; a brand's location count
     *              is never large enough for this to need real pagination,
     *              but a cap keeps one call bounded regardless
     */
    List<UUID> unlistedLocationsForVariant(UUID tenantId, UUID brandId, UUID variantId, int limit);
}
