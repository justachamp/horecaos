package uz.horecaos.platform.inventory.application;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.catalog.api.UnlistedOfferingsPort;
import uz.horecaos.platform.catalog.api.UnlistedOfferingsPort.UnlistedOfferings;
import uz.horecaos.platform.inventory.api.StockListingPort;

/**
 * The operations catch-up {@link uz.horecaos.platform.inventory.application.CatalogOfferingListingTrigger}
 * cannot give a pre-existing tenant (gap-map row 4.1's pilot-critical
 * backfill): every offering set {@code AVAILABLE} before that listener
 * existed, in either direction — one location's whole unlisted backlog, or
 * one variant's missing branches, the product editor's own "not listed at N
 * branches" action.
 *
 * <p>Each candidate is listed independently and a failure is caught and
 * logged rather than left to fail the rest, the same per-item resilience
 * {@code InventoryBulkAvailabilityService#apply} already gives the console's
 * bulk stop/unstop: one variant's constraint surprise must not stop its
 * siblings, in the same backfill call, from being listed.
 */
@Service
public class OfferingListingBackfillService {

    private static final Logger log = LoggerFactory.getLogger(OfferingListingBackfillService.class);

    /**
     * The most unlisted variants one {@link #backfillLocation} call lists. A
     * result at the cap means the location may still have more — the caller
     * (the operations endpoint, or the runbook driving it) calls again, and
     * each call shrinks what is left because a variant this call just listed
     * drops out of the next call's own unlisted read.
     */
    public static final int MAX_LOCATION_BACKFILL = 500;

    /**
     * The most locations one {@link #backfillVariant} call lists. A brand's
     * location count is never large enough for this to need real pagination
     * — this bounds one call's own work, not a realistic ceiling.
     */
    public static final int MAX_VARIANT_BACKFILL = 500;

    /**
     * The most offerings one {@link #describeUnlistedAtLocation} call names.
     * The report's {@code totalCount} is exact regardless; this only bounds
     * how many rows the stock page is sent to read.
     */
    public static final int MAX_REPORTED_OFFERINGS = 200;

    private final UnlistedOfferingsPort unlisted;
    private final StockListingPort stock;

    public OfferingListingBackfillService(UnlistedOfferingsPort unlisted, StockListingPort stock) {
        this.unlisted = unlisted;
        this.stock = stock;
    }

    /**
     * Lists every AVAILABLE-offered, never-listed variant at one location, up
     * to {@link #MAX_LOCATION_BACKFILL}.
     */
    public LocationBackfillResult backfillLocation(UUID tenantId, UUID brandId, UUID locationId) {
        List<UUID> candidates =
                unlisted.unlistedAvailableVariantsAtLocation(tenantId, brandId, locationId, MAX_LOCATION_BACKFILL);
        int listed = 0;
        for (UUID variantId : candidates) {
            if (listOne(tenantId, brandId, locationId, variantId)) {
                listed++;
            }
        }
        return new LocationBackfillResult(candidates.size(), listed, candidates.size() >= MAX_LOCATION_BACKFILL);
    }

    /**
     * The stock page's "unlisted offered dishes" report, and the runbook's dry
     * run: the same set {@link #backfillLocation} would list, described but
     * not touched. Its {@code totalCount} is the exact backlog, so it can be
     * compared with a later {@link LocationBackfillResult#candidateCount()}.
     */
    public UnlistedOfferings describeUnlistedAtLocation(
            UUID tenantId, UUID brandId, UUID locationId, String locale, int limit) {
        return unlisted.describeUnlistedAvailableAtLocation(
                tenantId, brandId, locationId, locale, Math.min(Math.max(limit, 1), MAX_REPORTED_OFFERINGS));
    }

    /** The product editor's own read: every branch offering this variant AVAILABLE but never listing it. */
    public List<UUID> unlistedLocationsForVariant(UUID tenantId, UUID brandId, UUID variantId) {
        return unlisted.unlistedLocationsForVariant(tenantId, brandId, variantId, MAX_VARIANT_BACKFILL);
    }

    /** The product editor's own one-click action: lists this variant at every branch {@link #unlistedLocationsForVariant} names. */
    public VariantBackfillResult backfillVariant(UUID tenantId, UUID brandId, UUID variantId) {
        List<UUID> candidateLocations = unlistedLocationsForVariant(tenantId, brandId, variantId);
        int listed = 0;
        for (UUID locationId : candidateLocations) {
            if (listOne(tenantId, brandId, locationId, variantId)) {
                listed++;
            }
        }
        return new VariantBackfillResult(candidateLocations.size(), listed);
    }

    private boolean listOne(UUID tenantId, UUID brandId, UUID locationId, UUID variantId) {
        try {
            return stock.ensureListed(tenantId, brandId, locationId, variantId);
        } catch (RuntimeException failure) {
            log.warn("Backfill could not list variant {} at location {}", variantId, locationId, failure);
            return false;
        }
    }

    /**
     * @param candidateCount how many unlisted variants this call found
     * @param listedCount    how many it actually listed (never more than
     *                       {@code candidateCount}; less only on a per-item
     *                       failure, logged and skipped)
     * @param mayHaveMore    the read hit {@link #MAX_LOCATION_BACKFILL} — the
     *                       location may still have unlisted variants beyond
     *                       this call's own page; call again
     */
    public record LocationBackfillResult(int candidateCount, int listedCount, boolean mayHaveMore) {}

    /**
     * @param candidateCount how many branches offered this variant unlisted
     * @param listedCount    how many were actually listed
     */
    public record VariantBackfillResult(int candidateCount, int listedCount) {}
}
