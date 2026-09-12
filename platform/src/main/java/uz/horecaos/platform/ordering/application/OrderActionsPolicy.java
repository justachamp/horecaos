package uz.horecaos.platform.ordering.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.ordering.domain.OrderStateMachine;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * The server-supplied {@code actions[]} array orders.md §4.2 mandates: the
 * console renders exactly what this returns and never computes availability
 * from a status string itself.
 *
 * <p>Kept in the application layer, beside {@link OrderStateService}, rather
 * than in {@code OperationsOrderController} — precisely so the mutating
 * endpoints and this read model cannot drift apart into two disagreeing
 * opinions about what is legal. {@link #canCancelWithoutReason} is not a
 * second copy of {@link OrderStateService#cancel}'s guard: it <em>is</em> the
 * guard, extracted so both call sites read one implementation. Everything else
 * here is a direct read of {@link OrderStateMachine}, which is the same table
 * {@link OrderStateService#advance} and {@link OrderStateService#decide}
 * enforce.
 *
 * <p><b>Wave P05 (gap map 1.2a).</b> Before this wave {@code availableFor} took
 * no principal, so an order's status and fulfilment mode were the only inputs
 * — every actor holding {@code ORDER_READ} was offered every action legal for
 * the order, whether or not they held the capability the corresponding
 * mutating endpoint actually requires. {@code LOCATION_STAFF} (which holds
 * {@code ORDER_APPROVE}/{@code ORDER_ADVANCE} and not {@code ORDER_CANCEL})
 * was offered «Отменить» on every open order and refused with a 403 at
 * {@code OperationsOrderController}'s cancellation endpoint. {@code
 * grantedCapabilities} closes that gap: every branch below is gated by the
 * exact {@link Capability} constant the corresponding mutating endpoint
 * declares via {@code @RequiresCapability}, so an actor who cannot call the
 * endpoint is never offered the button that calls it.
 */
public final class OrderActionsPolicy {

    private OrderActionsPolicy() {}

    /**
     * Every action legal on an order at this status and fulfilment mode, for a
     * principal holding exactly {@code grantedCapabilities} — in a stable
     * order: the decision first, then every legal advance, then amend, then
     * cancel last.
     *
     * @param grantedCapabilities the capabilities the calling principal holds
     *                            at this order's {@code LOCATION} scope
     *                            (ADR 0025). An action whose mutating endpoint
     *                            requires a capability absent from this set is
     *                            never added, regardless of what the status and
     *                            mode alone would permit.
     */
    public static List<OrderAction> availableFor(
            OrderStatus status, FulfillmentMode mode, Set<Capability> grantedCapabilities) {
        List<OrderAction> actions = new ArrayList<>();

        // Mirrors OrderStateService.decide: a decision is only ever accepted
        // while the order is still open, and APPROVE/REJECT are the only two
        // outcomes it recognises. Both actions post to the same endpoint,
        // POST .../approval-decisions, which declares ORDER_APPROVE.
        if (status == OrderStatus.AWAITING_APPROVAL && grantedCapabilities.contains(Capability.ORDER_APPROVE)) {
            actions.add(new OrderAction(OrderActionCode.APPROVE, null));
            actions.add(new OrderAction(OrderActionCode.REJECT, null));
        }

        // Mirrors OrderStateService.advance's guard exactly:
        // OrderStateMachine.permits(status, target, mode). CANCELLED is excluded
        // here even though the machine models it as an ordinary edge from
        // CONFIRMED, because orders.md gives cancellation its own action, its
        // own capability (ORDER_CANCEL, not ORDER_ADVANCE) and its own dialog —
        // conflating the two would let a client reach cancellation's
        // consequences through the advance affordance. POST .../state-actions
        // declares ORDER_ADVANCE.
        if (grantedCapabilities.contains(Capability.ORDER_ADVANCE)) {
            for (OrderStatus target : OrderStateMachine.transitionsFrom(status)) {
                if (target == OrderStatus.CANCELLED) {
                    continue;
                }
                if (OrderStateMachine.permits(status, target, mode)) {
                    actions.add(new OrderAction(OrderActionCode.ADVANCE, target));
                }
            }
        }

        if (grantedCapabilities.contains(Capability.ORDER_CANCEL) && canCancel(status)) {
            actions.add(new OrderAction(OrderActionCode.CANCEL, null));
        }

        // POST .../amendments (propose) declares ORDER_AMEND. Only the
        // "order has not ended" half of OrderAmendmentService.propose's guard
        // is expressible here (orders.md §4.4): the seven financial commands'
        // own cut point, the open-amendment lock and the POS-export interlock
        // are all evaluated against state this status/mode pair does not
        // carry, and stay enforced only by the endpoint itself, exactly as
        // orders.md §4.2 expects for a "temporarily unavailable" case. The
        // three built commands (kitchen note, callback flag, change-due) are
        // never subject to the cut point, so offering AMEND up to the moment
        // the order ends is correct for what a client can actually complete
        // today.
        if (grantedCapabilities.contains(Capability.ORDER_AMEND) && canAmend(status)) {
            actions.add(new OrderAction(OrderActionCode.AMEND, null));
        }

        return List.copyOf(actions);
    }

    /**
     * Whether {@code POST .../cancellations} without a registry reason would be
     * accepted right now — exactly {@link OrderStateService#cancel}'s combined
     * guard, called from both places.
     */
    static boolean canCancel(OrderStatus status) {
        return canCancelWithoutReason(status) && OrderStateMachine.permits(status, OrderStatus.CANCELLED);
    }

    /**
     * The application-level half of the cancellation guard: refused once the
     * order is {@code CONFIRMED} or further along, because past that point the
     * stock disposition and the liable party are real decisions ADR 0019
     * refuses to guess at (orders.md §0.3, §1.1).
     *
     * <p>{@link OrderStateService#cancel} calls this directly for its reasonless
     * path, so a change here changes both the mutating endpoint and this read
     * model in the same commit — the property the drift test in {@code
     * OrderActionsPolicyTests} exists to prove.
     */
    static boolean canCancelWithoutReason(OrderStatus status) {
        return status != OrderStatus.CONFIRMED
                && status != OrderStatus.PREPARING
                && status != OrderStatus.READY
                && status != OrderStatus.FULFILLING;
    }

    /**
     * {@link OrderAmendmentService#propose}'s own status guard, read back: the
     * only check it makes before the open-amendment lock is that the order has
     * not ended, because "add a dessert" to a finished order is a new order,
     * not an edit (orders.md §4.4).
     */
    static boolean canAmend(OrderStatus status) {
        return !status.terminal();
    }
}
