package uz.horecaos.platform.inventory.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Give back or write off the stock an order no longer needs (ADR 0169, the fourth ADR 0017 verb).
 *
 * <p>Keyed by the order, not by one reservation: an order can hold a {@code HELD} original reservation and a
 * {@code COMMITTED} amendment's one at once, each under a different quote id, and a line's units are tied to a
 * variant at a location rather than to the reservation that first held them. The caller names the order's quote
 * ids and the units it no longer needs per variant; inventory reads the reservations itself, in one transaction,
 * to decide what is still a hold and what is already a sale.
 *
 * @param orderQuoteIds the pricing quote ids of the order's revisions, original first: the owners of its reservations
 * @param lines         the units the order no longer needs, per variant
 * @param declared      what the reason says to do with units that were already sold
 * @param reasonCode    the ledger's reason for a {@code WASTE} movement: the removal reason's system category
 * @param actorType     {@code USER}, {@code SERVICE} or {@code SYSTEM_JOB}, as the ledger names actors
 * @param idempotencyKey the caller's key for the whole disposal; a replay returns the first answer and moves nothing
 */
public record StockDisposalCommand(
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID orderId,
        List<UUID> orderQuoteIds,
        List<Line> lines,
        Disposition declared,
        String reasonCode,
        String actorType,
        @Nullable UUID actorId,
        String idempotencyKey) {

    public StockDisposalCommand {
        Objects.requireNonNull(tenantId, "A disposal names its tenant");
        Objects.requireNonNull(brandId, "A disposal names its brand");
        Objects.requireNonNull(locationId, "A disposal names its location");
        Objects.requireNonNull(orderId, "A disposal names its order");
        orderQuoteIds = List.copyOf(orderQuoteIds);
        lines = List.copyOf(lines);
        Objects.requireNonNull(declared, "A disposal declares what to do with sold units");
        Objects.requireNonNull(reasonCode, "A disposal names its reason");
        Objects.requireNonNull(actorType, "A disposal names its actor type");
        Objects.requireNonNull(idempotencyKey, "A disposal carries an idempotency key");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A disposal names at least one line");
        }
    }

    /**
     * What to do with a unit the order had already bought.
     *
     * <p>{@code RETURN_TO_STOCK} puts it back on the shelf; {@code WRITE_OFF} records that the goods were lost
     * (the sale is reversed and the loss written, so {@code on_hand} is net unchanged); {@code NO_EFFECT} writes
     * nothing, for an item inventory does not count.
     */
    public enum Disposition {
        RETURN_TO_STOCK,
        WRITE_OFF,
        NO_EFFECT
    }

    /**
     * @param units                the whole units the order no longer needs of this variant
     * @param unitsStartedOrMade   of those, the units the kitchen has started or finished: they are written off
     *                             whatever the declared disposition says, because a dish already cooked is not
     *                             put back on a shelf
     */
    public record Line(UUID variantId, int units, int unitsStartedOrMade) {

        public Line {
            Objects.requireNonNull(variantId, "A line names its variant");
            if (units <= 0) {
                throw new IllegalArgumentException("A line disposes of at least one unit");
            }
            if (unitsStartedOrMade < 0 || unitsStartedOrMade > units) {
                throw new IllegalArgumentException("The kitchen's units are a part of the line's units");
            }
        }
    }
}
