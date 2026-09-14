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
     * Whether the {@code AMEND} branch below actually adds the action, rather
     * than only computing it.
     *
     * <p><b>Wave P05 adversarial review.</b> This wave built {@code AMEND}'s
     * gate — {@code ORDER_AMEND} plus {@link #canAmend} — the same way as the
     * other four codes, and for one build emitted it unconditionally. That was
     * a defect, not the "harmless, inert" case {@code COMPLETE}/{@code
     * RESOLVE}/{@code ASSIGN_COURIER}/{@code ISSUE_INVOICE} are in: {@code
     * ORDER_AMEND} already reaches five real {@code PlatformRole}s, and the
     * console has no translated label or working click handler for {@code
     * AMEND} — {@code order-actions.ts}'s {@code actionLabel} falls to its
     * untranslated {@code default} case, printing the raw string
     * {@code "AMEND"} in every locale, and {@code onActionClick}'s
     * {@code default} case silently no-ops the click. A real operator holding
     * {@code ORDER_AMEND} would see a permanently dead, out-of-language button
     * on every open order.
     *
     * <p>This constant was the fix: the gate stayed built and tested exactly
     * as wave P10 (the amendment client) needed it, with emission held back
     * behind one named switch rather than deleted and rewritten later.
     *
     * <p><b>Wave P10.</b> {@code order-actions.ts} now has a real {@code
     * AMEND} case ("Изменить", the same label in all three locales that
     * `q-order-amend-menu` uses), {@code order-detail-pane.ts}'s {@code
     * onActionClick} opens that menu, and {@code order-queue.ts}'s opens the
     * order itself — the menu's five dialogs live on the detail pane, not the
     * row. Flipped to {@code true}; ADR 0105 and ADR 0113 both record the
     * decision.
     */
    private static final boolean AMEND_EMISSION_ENABLED = true;

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

        // POST .../completion, naming how the order was completed (orders.md
        // §4.6, wave P09/gap map 1.2j). Declares the same ORDER_ADVANCE the
        // generic advance above does, and is offered alongside the ADVANCE
        // entry above where the target is COMPLETED — never instead of it:
        // a client built before this wave (order-queue.ts's row action) still
        // works against the generic ADVANCE entry, unaware a better one now
        // sits beside it, while the order detail pane (wave P09) prefers this
        // one so a delivery order can be closed as "our own courier" or
        // "handed to a partner service" instead of always recording the
        // former. Both entries share one gate — exactly what
        // OrderStateMachine.permits(status, COMPLETED, mode) already computed
        // for the ADVANCE loop above — so neither can be offered without the
        // other.
        if (grantedCapabilities.contains(Capability.ORDER_ADVANCE)
                && OrderStateMachine.permits(status, OrderStatus.COMPLETED, mode)) {
            actions.add(new OrderAction(OrderActionCode.COMPLETE, null));
        }

        if (grantedCapabilities.contains(Capability.ORDER_CANCEL) && canCancel(status)) {
            actions.add(new OrderAction(OrderActionCode.CANCEL, null));
        }

        // ADR 0019 amendment (ADR 0110), wave P41 (orders.md §0.2, §11.3). A
        // compensating transition is a different power from ORDER_ADVANCE, and
        // a different table: OrderStateMachine.compensatingTransitionsFrom is
        // disjoint from transitionsFrom, so an ORDER_ADVANCE holder is never
        // offered one of these under the ADVANCE branch above, and an
        // ORDER_STATE_OVERRIDE holder never sees it merge with an ordinary
        // advance. POST .../state-overrides declares ORDER_STATE_OVERRIDE.
        if (grantedCapabilities.contains(Capability.ORDER_STATE_OVERRIDE)) {
            for (OrderStatus target : OrderStateMachine.compensatingTransitionsFrom(status)) {
                actions.add(new OrderAction(OrderActionCode.OVERRIDE, target));
            }
        }

        // POST .../amendments (propose) declares ORDER_AMEND. Only the
        // "order has not ended" half of OrderAmendmentService.propose's guard
        // is expressible here (orders.md §4.4): the seven financial commands'
        // own cut point, the open-amendment lock and the POS-export interlock
        // are all evaluated against state this status/mode pair does not
        // carry, and stay enforced only by the endpoint itself, exactly as
        // orders.md §4.2 expects for a "temporarily unavailable" case. The
        // three built commands (kitchen note, callback flag, change-due) are
        // never subject to the cut point, so the gate below is correct for
        // what a client can actually complete today.
        //
        // The gate is built and tested; emission is not. AMEND_EMISSION_ENABLED
        // (see its own doc) holds this branch inert until wave P10 ships a
        // console that can render and click AMEND — see ADR 0105.
        if (AMEND_EMISSION_ENABLED && grantedCapabilities.contains(Capability.ORDER_AMEND) && canAmend(status)) {
            actions.add(new OrderAction(OrderActionCode.AMEND, null));
        }

        return List.copyOf(actions);
    }

    /**
     * Whether {@code POST .../cancellations} would be accepted right now with
     * <em>some</em> outcome — reasonless before {@code CONFIRMED} (see {@link
     * #canCancelWithoutReason}), a registry {@code reasonId} from {@code
     * CONFIRMED} onward (see {@link OrderOutcomeService#cancel}, which checks
     * nothing narrower than this).
     *
     * <p><b>Wave P09 (gap map 1.2k).</b> Before this wave the gate additionally
     * required {@link #canCancelWithoutReason}, so {@code CANCEL} vanished from
     * {@code actions[]} the moment an order was confirmed even though a
     * reasoned cancellation was legal there — because the console's cancel
     * dialog had nowhere to pick a reason from. Now that it does (the reason
     * registry, orders.md §4.5), the read model offers {@code CANCEL} wherever
     * {@link OrderStateMachine} has an edge to {@code CANCELLED} at all, and
     * the dialog itself decides — by checking {@link #canCancelWithoutReason}
     * — whether it needs to collect a reason before it submits.
     */
    static boolean canCancel(OrderStatus status) {
        return OrderStateMachine.permits(status, OrderStatus.CANCELLED);
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
