package uz.horecaos.platform.ordering.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What the kitchen's own record says about the order lines being taken off an order (ADR 0169).
 *
 * <p>Declared by ordering and implemented by the kitchen, the direction {@code KitchenOrderSource} already
 * runs the other way round: ordering asks, the kitchen answers from its tickets, and ordering never reads a
 * kitchen table. The answer can only make a disposition <em>more</em> conservative. A line the kitchen has
 * started or made is a dish, whatever the removal reason says about putting stock back on a shelf; a line it
 * has not touched, or one it has no record of, leaves the reason's disposition as it was.
 *
 * <p>A tenant that runs no kitchen display has no tickets and gets {@link State#UNKNOWN}: the reason decides,
 * which is the weaker guarantee and says so in the preview.
 */
public interface OrderLineProductionPort {

    /** How far the kitchen has got with one order line. */
    enum State {
        /** A ticket item exists and is still queued. */
        NOT_STARTED,
        /** The kitchen has started it. */
        STARTED,
        /** The kitchen has finished it. */
        MADE,
        /** No ticket item, a voided one, or an answer that could not be read. */
        UNKNOWN;

        /** A started or finished line is a made dish; anything else is the reason's to decide. */
        public boolean madeOrBeingMade() {
            return this == STARTED || this == MADE;
        }
    }

    /**
     * The state of each of these order lines. A line absent from the answer is {@link State#UNKNOWN}.
     */
    Map<UUID, State> stateOfLines(UUID tenantId, UUID orderId, Collection<UUID> orderLineIds);

    /** For an assembly with no kitchen: every line is unknown and the reason decides. */
    OrderLineProductionPort UNKNOWN_EVERYWHERE = (tenantId, orderId, orderLineIds) -> Map.of();
}
