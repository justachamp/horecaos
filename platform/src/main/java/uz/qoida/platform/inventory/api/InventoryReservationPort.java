package uz.qoida.platform.inventory.api;

import java.util.Map;
import java.util.UUID;

/**
 * What checkout and the ADR 0019 inventory process manager need from inventory
 * (ADR 0017).
 *
 * <p>Deliberately three verbs. Ordering may take a hold, turn it into a sale, or
 * give it back; it may not read or write the movement ledger, which is the
 * evidence of what actually happened and is appended by inventory alone.
 *
 * <p>Everything here is keyed by the quote a hold was taken for rather than by a
 * reservation id, so a retried checkout and a restarted process manager both
 * name the same hold without having to remember an identifier they may never
 * have received.
 */
public interface InventoryReservationPort {

    /**
     * Checks availability and takes the hold in one transaction, so a dish marked
     * sold out between the check and the hold cannot slip through.
     *
     * <p>Repeating the call for the same quote returns the existing hold rather
     * than taking a second one.
     */
    ReservationResult reserveForQuote(UUID tenantId, UUID brandId, UUID locationId,
            UUID quoteId, Map<UUID, Integer> quantitiesByVariant);

    /**
     * Turns a hold into a committed sale when an order is confirmed.
     *
     * @return false when there was no hold to commit, or it had already been
     *         released or expired — the status predicate is inside the UPDATE, so
     *         a late commit cannot revive stock that was already given back
     */
    boolean commit(UUID tenantId, UUID quoteId);

    /** Frees a hold when a checkout fails, an order is rejected, or a cart is abandoned. */
    boolean release(UUID tenantId, UUID quoteId);
}
