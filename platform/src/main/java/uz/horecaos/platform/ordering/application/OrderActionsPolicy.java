package uz.horecaos.platform.ordering.application;

import java.util.ArrayList;
import java.util.List;
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
 */
public final class OrderActionsPolicy {

    private OrderActionsPolicy() {}

    /**
     * Every action legal on an order at this status and fulfilment mode, in a
     * stable order: the decision first, then every legal advance, then cancel
     * last.
     */
    public static List<OrderAction> availableFor(OrderStatus status, FulfillmentMode mode) {
        List<OrderAction> actions = new ArrayList<>();

        // Mirrors OrderStateService.decide: a decision is only ever accepted
        // while the order is still open, and APPROVE/REJECT are the only two
        // outcomes it recognises.
        if (status == OrderStatus.AWAITING_APPROVAL) {
            actions.add(new OrderAction(OrderActionCode.APPROVE, null));
            actions.add(new OrderAction(OrderActionCode.REJECT, null));
        }

        // Mirrors OrderStateService.advance's guard exactly:
        // OrderStateMachine.permits(status, target, mode). CANCELLED is excluded
        // here even though the machine models it as an ordinary edge from
        // CONFIRMED, because orders.md gives cancellation its own action, its
        // own capability (ORDER_CANCEL, not ORDER_ADVANCE) and its own dialog —
        // conflating the two would let a client reach cancellation's
        // consequences through the advance affordance.
        for (OrderStatus target : OrderStateMachine.transitionsFrom(status)) {
            if (target == OrderStatus.CANCELLED) {
                continue;
            }
            if (OrderStateMachine.permits(status, target, mode)) {
                actions.add(new OrderAction(OrderActionCode.ADVANCE, target));
            }
        }

        if (canCancel(status)) {
            actions.add(new OrderAction(OrderActionCode.CANCEL, null));
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
}
