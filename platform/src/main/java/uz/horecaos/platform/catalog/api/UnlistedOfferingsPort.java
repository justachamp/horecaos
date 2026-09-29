package uz.horecaos.platform.catalog.api;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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

    /**
     * The same set {@link #unlistedAvailableVariantsAtLocation} lists, described
     * for a human (gap-map row 4.4c's stock-page report, and the backfill
     * runbook's dry run): every variant this location offers {@code AVAILABLE}
     * that inventory has never listed, with the name an operator recognises it
     * by, plus the exact size of the backlog. Read-only — the action that
     * clears it is the backfill.
     *
     * @param locale the locale to prefer for names; a product with no name in
     *               it falls back to another locale, then to its product code,
     *               so a row is never nameless
     * @param limit  the most items to describe; {@link UnlistedOfferings#totalCount}
     *               stays the exact backlog regardless
     */
    UnlistedOfferings describeUnlistedAvailableAtLocation(
            UUID tenantId, UUID brandId, UUID locationId, String locale, int limit);

    /**
     * @param productName the product's name in the asked locale (or a fallback)
     * @param variantName the variant's own name when it has one — a
     *                    single-variant product usually does not
     * @param sku         the variant's SKU when it has one
     */
    record UnlistedOffering(
            UUID variantId,
            String productName,
            @Nullable String variantName,
            @Nullable String sku) {}

    /**
     * @param totalCount every AVAILABLE, never-listed offering at the
     *                   location — not just the {@code items} returned
     * @param items      at most the asked {@code limit} of them, by product
     *                   name then variant id
     */
    record UnlistedOfferings(int totalCount, List<UnlistedOffering> items) {}
}
