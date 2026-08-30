package uz.horecaos.platform.fulfillment.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * How a confirmed order becomes something a courier will be sourced for
 * (ADR 0014, ADR 0019).
 *
 * <p>Called rather than triggered, and that is the notable thing about it. ADR
 * 0041's kitchen opens its ticket by listening to {@code OrderConfirmed} from
 * inside the kitchen module, and delivery cannot do the same: pricing already
 * depends on fulfilment for ADR 0037's delivery fee, and ordering depends on
 * pricing, so a fulfilment listener on an ordering event closes
 * {@code fulfillment -> ordering -> pricing -> fulfillment} into a cycle that
 * {@code ModularArchitectureTests} refuses. The dependency is therefore inverted:
 * fulfilment publishes what it can do, and the confirmation path calls it.
 *
 * <p>Which is also the honest direction. A delivery plan is a consequence of a
 * commercial commitment, and the module that makes the commitment is the one that
 * knows the instant it was made.
 */
public interface DeliveryPlanner {

    /**
     * Opens the plan and the sourcing job for a confirmed delivery order.
     *
     * <p>Idempotent, and against a unique index rather than a read: a replayed
     * confirmation, a retried command and two threads all produce one plan and one
     * job, because two jobs for one plan is two workers sourcing the same order.
     *
     * <p>Never throws for an order it cannot plan. A pickup order, an order that
     * is not this tenant's, and a branch nobody has placed on a map are all
     * ordinary answers, and failing a confirmation for any of them would turn a
     * delivery configuration problem into a checkout outage.
     *
     * @param confirmedAt the order's own confirmation instant, never now. Every
     *                    instant in the time model derives from it, so a plan
     *                    opened by a replay an hour later still describes the same
     *                    promise
     * @return the delivery plan id, or empty when there was nothing to plan
     */
    Optional<UUID> planFor(UUID tenantId, UUID brandId, UUID locationId, UUID orderId,
            Instant confirmedAt);
}
