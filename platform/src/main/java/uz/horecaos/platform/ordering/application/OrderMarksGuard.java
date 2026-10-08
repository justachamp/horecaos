package uz.horecaos.platform.ordering.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What {@link OrderStateService} asks of marked-goods capture (ADR 0161), as a narrow seam.
 *
 * <p>The state service owns every transition that hands an order over, and the guard has to run inside
 * that transition's transaction and under the order row's lock. It is a seam rather than a direct call so
 * that the many places that construct a state service by hand (the kitchen and checkout suites) need not
 * know marking exists: {@link #NONE} is what they get, and it is correct for them, because an order with
 * no marked line has nothing to guard.
 */
public interface OrderMarksGuard {

    /** For callers wired without marking: nothing is ever incomplete and nothing is ever released. */
    OrderMarksGuard NONE = new OrderMarksGuard() {
        @Override
        public List<UUID> incompleteLines(UUID tenantId, UUID brandId, UUID orderId) {
            return List.of();
        }

        @Override
        public void releaseForCancellation(UUID tenantId, UUID brandId, UUID locationId, UUID orderId, Instant now) {}
    };

    /**
     * The lines of the order that still need codes. Empty when the order needs none, when marked-goods
     * capture is off for the tenant, and when every marked line is complete.
     *
     * <p>Takes the order row's lock before it counts, and only when the order holds a marked line or a
     * live mark, so an ordinary order never pays for it. Must be called inside the transaction that will
     * change the status, so the lock is held until the status change commits.
     */
    List<UUID> incompleteLines(UUID tenantId, UUID brandId, UUID orderId);

    /**
     * A cancellation of an order that has not reached the handover frees its marks (reason {@code
     * ORDER_CANCELLED}); one that has keeps them.
     */
    void releaseForCancellation(UUID tenantId, UUID brandId, UUID locationId, UUID orderId, Instant now);
}
