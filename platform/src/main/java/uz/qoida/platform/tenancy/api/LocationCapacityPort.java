package uz.qoida.platform.tenancy.api;

import java.util.UUID;

/**
 * The ADR 0036 concurrent-order ceiling, claimed and released by ADR 0019's
 * checkout (rule 8 of the serviceability resolver).
 *
 * <p>Rule 8 as answered by {@link ServiceabilityResolver} is advisory: it is a
 * number that was true a moment ago, which is fine for browse and never enough
 * to commit on. This port is the authoritative half — the claim is taken under a
 * row lock inside the checkout transaction, so two customers racing for the last
 * slot are settled by the database rather than by a count either of them read a
 * second earlier.
 *
 * <p>The hold id is the order id. That is what makes a retried checkout re-claim
 * its own slot instead of consuming a second and reporting the kitchen busier
 * than it is.
 */
public interface LocationCapacityPort {

    /**
     * Claims one slot, or reports the kitchen full.
     *
     * <p>Must be called inside the caller's transaction. A slot claimed in a
     * transaction of its own would survive a checkout that then rolled back, and
     * the branch would report itself full of orders that were never placed.
     */
    CapacityOutcome claimCapacity(UUID tenantId, UUID brandId, UUID locationId, UUID holdId);

    /** Frees a slot when the order reaches a state that no longer occupies the kitchen. */
    boolean releaseCapacity(UUID tenantId, UUID holdId);

    enum CapacityOutcome {
        CLAIMED,
        AT_CAPACITY
    }
}
