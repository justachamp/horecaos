package uz.horecaos.platform.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * What a {@link StockDisposalCommand} did, per variant (ADR 0169).
 *
 * <p>The counts add up: for each line, {@code releasedUnits + returnedUnits + writtenOffUnits + noEffectUnits} is
 * the units asked for. A {@code BINARY} or {@code UNTRACKED} item has no position to move, so it writes no
 * movement, and the counts still come back as the record of what the disposal meant for it.
 *
 * @param replayed true when this is the first answer returned for a repeated idempotency key; nothing moved now
 */
public record StockDisposal(List<LineResult> lines, boolean replayed) {

    public StockDisposal {
        lines = List.copyOf(lines);
    }

    /** The movement ids of every line, in order: what an order's outcome points at. */
    public List<UUID> movementIds() {
        return lines.stream().flatMap(line -> line.movementIds().stream()).toList();
    }

    /**
     * @param releasedUnits  units that were still a hold and were given back; no movement
     * @param returnedUnits  sold units put back on the shelf (a {@code RETURN} movement)
     * @param writtenOffUnits sold units lost (a {@code RETURN} and then a {@code WASTE} movement)
     * @param noEffectUnits  sold units of an item nothing is counted for, or declared {@code NO_EFFECT}
     */
    public record LineResult(
            UUID variantId,
            int releasedUnits,
            int returnedUnits,
            int writtenOffUnits,
            int noEffectUnits,
            List<UUID> movementIds) {

        public LineResult {
            movementIds = List.copyOf(movementIds);
        }

        public int units() {
            return releasedUnits + returnedUnits + writtenOffUnits + noEffectUnits;
        }
    }
}
