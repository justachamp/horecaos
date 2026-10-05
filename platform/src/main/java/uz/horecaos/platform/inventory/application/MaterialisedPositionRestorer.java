package uz.horecaos.platform.inventory.application;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.ChannelOfferingLookup;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore.StopRow;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore.PositionRow;

/**
 * Gives back what a materialisation run took (ADR 0141, rollback switch three): when the stop a run
 * wrote onto a position ends, the position does not stay off for good.
 *
 * <p>The run turns a stop in force into {@code binary_available = false} -- a boolean with no end
 * time and no author but "the run". Nothing else ever turns it back: the stop is lifted by hand, the
 * POS says the dish is back in stock (once; the poll acts on the transition and will not say it
 * again), or a timed stop's end passes, and the stop row goes {@code LIFTED} or {@code EXPIRED}
 * while the position keeps saying no. The run is repeatable and is not tied to the switch, so that
 * can be days. This is the other half of the write: called in the transaction that ends a stop, it
 * restores every position of the stop's variant that is unavailable <em>only</em> because of a run
 * and that no other stop in force still holds off.
 *
 * <h2>What "only because of a run" means</h2>
 *
 * <p>The latest availability movement of the position is the run's own ({@link
 * InventoryService#MATERIALISED_REASON}). A kitchen that 86'd the dish after the run, or a POS
 * reading, is a later movement and the position is theirs: it is left alone. A position that was
 * already off when the run came is counted by the run as already unavailable and was never written,
 * so it has no such movement either.
 *
 * <h2>What "no other stop holds it" means</h2>
 *
 * <p>A position can be held by several stops at once, and the run wrote it for whichever it met
 * first. So the question is not "was this the stop that wrote it" but "is anything still in force
 * that the run would have written it for": a {@code BRAND} stop, a {@code LOCATION} stop at that
 * branch, a {@code MENU} stop that is exact there ({@link StopPositionReach}). A {@code CHANNEL}
 * stop never held a position. Asked of every position of the variant at every end, the answer
 * converges: the last of the stops holding a position to end gives it back, whichever it was.
 *
 * <p>No event is published: the stop ending is itself an {@code InventoryStopChanged} in the same
 * transaction, which is what wakes the reconciler and the open stop lists, and the movement ledger
 * records the write.
 */
@Component
public class MaterialisedPositionRestorer {

    private static final Logger log = LoggerFactory.getLogger(MaterialisedPositionRestorer.class);

    /** The movement reason a position leaves with when the stop that was written onto it has ended. */
    public static final String RESTORED_REASON = "EMBARGO_ENDED";

    private final JdbcAvailabilityStopStore stops;
    private final JdbcInventoryStore inventory;
    private final ChannelOfferingLookup catalog;

    public MaterialisedPositionRestorer(
            JdbcAvailabilityStopStore stops, JdbcInventoryStore inventory, ChannelOfferingLookup catalog) {
        this.stops = stops;
        this.inventory = inventory;
        this.catalog = catalog;
    }

    /**
     * @param ended the stop that was just lifted or expired, as its row now reads
     * @param now the instant "in force" is judged at
     * @return how many positions were given back
     */
    public int restoreAfter(StopRow ended, Instant now) {
        List<PositionRow> candidates = inventory.unavailablePositionsLastSetBy(
                ended.tenantId(), ended.variantId(), InventoryService.MATERIALISED_REASON);
        if (candidates.isEmpty()) {
            return 0; // The usual case: no run ever wrote this dish.
        }
        List<StopRow> inForce = stops.activeForVariants(ended.tenantId(), List.of(ended.variantId()), now);
        Map<UUID, List<UUID>> exactByMenu = new HashMap<>();
        int restored = 0;
        for (PositionRow position : candidates) {
            if (heldBy(inForce, position.locationId(), ended, exactByMenu)) {
                continue;
            }
            inventory.setBinaryAvailability(
                    ended.tenantId(),
                    position.stockItemId(),
                    true,
                    "restore:%s:%d".formatted(ended.id(), position.positionSequence()),
                    RESTORED_REASON,
                    "SERVICE",
                    null,
                    ended.source().name(),
                    now);
            restored++;
        }
        if (restored > 0) {
            log.info("Gave back {} positions that a materialisation run had written for an ended stop", restored);
        }
        return restored;
    }

    private boolean heldBy(List<StopRow> inForce, UUID locationId, StopRow ended, Map<UUID, List<UUID>> exactByMenu) {
        for (StopRow other : inForce) {
            switch (other.scopeType()) {
                case BRAND -> {
                    return true;
                }
                case LOCATION -> {
                    if (locationId.equals(other.locationId())) {
                        return true;
                    }
                }
                case MENU -> {
                    if (menuHolds(other, locationId, ended, exactByMenu)) {
                        return true;
                    }
                }
                case CHANNEL, TERMINAL -> {
                    // Never written onto a position: it cannot be what holds one off.
                }
            }
        }
        return false;
    }

    private boolean menuHolds(StopRow menuStop, UUID locationId, StopRow ended, Map<UUID, List<UUID>> exactByMenu) {
        UUID menuId = menuStop.menuId();
        if (menuId == null) {
            return false;
        }
        try {
            List<UUID> exact = exactByMenu.computeIfAbsent(
                    menuId,
                    menu -> StopPositionReach.ofMenu(
                                    stops, catalog, ended.tenantId(), ended.brandId(), ended.variantId(), menu)
                            .exact());
            return exact.contains(locationId);
        } catch (RuntimeException lookupFailed) {
            // Ending a stop must not fail because a catalog read did, and a position left off is the
            // direction a mistake costs least. The next stop to end asks again.
            log.warn("Could not tell whether a menu stop holds a position; leaving it as it is", lookupFailed);
            return true;
        }
    }
}
