package uz.horecaos.platform.ordering.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * The authoritative order state machine (ADR 0019), transcribed from
 * {@code docs/domains/state-machines.md}.
 *
 * <p>Code, not configuration. ADR 0036's omission list says tenants may not
 * reorder the order lifecycle, and this is where that is true rather than merely
 * intended: there is no table to override, no policy key that reaches it, and no
 * constructor. A tenant changes <em>which</em> transitions it uses — by choosing
 * an acceptance policy — never which transitions exist.
 *
 * <p>POS and delivery providers propose transitions through this same table.
 * They never write {@code ordering.orders.status} directly, because two
 * authorities over one column means a provider bug becomes a commercial fact.
 *
 * <p><b>ADR 0019 amendment (ADR 0110), wave P41.</b> An order advanced past
 * its true state by mistake has no <em>reversal</em> — {@link #permits} and
 * {@link #transitionsFrom} answer the forward graph exactly as before, and
 * neither ever routes backward. What exists instead is {@link #COMPENSATING},
 * a second, disjoint table of edges that happen to restore an earlier status
 * while remaining ordinary forward steps: each one is its own fact, with its
 * own reason and its own {@code order_state_history} row, gated on {@code
 * Capability.ORDER_STATE_OVERRIDE} rather than the {@code ORDER_ADVANCE} every
 * line cook holds. See {@link #compensatingTransitionsFrom} and {@link
 * #isCompensating}.
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = allowed();

    /**
     * Compensating edges (ADR 0019 amendment, ADR 0110; orders.md §0.2, §11.3).
     *
     * <p>Deliberately its own table, never merged into {@link #ALLOWED}. An
     * order advanced to {@code READY} by mistake has no literal way back — this
     * platform never lets two authorities write one column, and a reversible
     * edge would make the forward graph and the correction graph the same
     * table, which is exactly how a correction turns into an undecidable
     * "which direction is this" for a reader of {@code order_state_history}.
     * Keeping the two tables separate is also what stops a compensating target
     * from ever being offered to an {@code ORDER_ADVANCE} holder: {@link
     * uz.horecaos.platform.ordering.application.OrderActionsPolicy#availableFor}
     * reads {@link #transitionsFrom} for {@code ADVANCE} and {@link
     * #compensatingTransitionsFrom} for {@code OVERRIDE}, and the two capability
     * checks never share a branch.
     *
     * <p>Two edges, both one step back along the kitchen path: {@code READY ->
     * PREPARING} (advanced to ready by mistake) and {@code FULFILLING -> READY}
     * (a courier assignment or handover reversed before the food ever left).
     * Nothing further back is declared — an operator who mis-clicked all the
     * way past {@code CONFIRMED} corrects the commercial record with a fresh
     * amendment or cancellation, not a chain of reversals, and no terminal
     * status ever appears here: {@link OrderStatus#terminal()} orders stay
     * terminal, by omission rather than by a guard somebody could forget to
     * call.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> COMPENSATING = compensating();

    private OrderStateMachine() {}

    private static Map<OrderStatus, Set<OrderStatus>> allowed() {
        Map<OrderStatus, Set<OrderStatus>> transitions = new EnumMap<>(OrderStatus.class);

        // Three ways out of RECEIVED, chosen by payment timing and acceptance mode.
        transitions.put(
                OrderStatus.RECEIVED,
                EnumSet.of(
                        OrderStatus.PAYMENT_AUTHORIZING,
                        OrderStatus.AWAITING_APPROVAL,
                        OrderStatus.CONFIRMED,
                        OrderStatus.CANCELLED));

        transitions.put(
                OrderStatus.PAYMENT_AUTHORIZING,
                EnumSet.of(
                        OrderStatus.AWAITING_APPROVAL,
                        OrderStatus.CONFIRMED,
                        OrderStatus.PAYMENT_FAILED,
                        OrderStatus.CANCELLED));

        transitions.put(
                OrderStatus.AWAITING_APPROVAL,
                EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.REJECTED, OrderStatus.EXPIRED, OrderStatus.CANCELLED));

        // CONFIRMED -> CANCELLED is in the canonical diagram, gated on policy.
        // The gate lives in the application because "policy permits" is a runtime
        // question; the transition existing at all is a modelling question and
        // belongs here.
        transitions.put(OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.PREPARING, OrderStatus.CANCELLED));

        transitions.put(OrderStatus.PREPARING, EnumSet.of(OrderStatus.READY));

        // Delivery goes through FULFILLING; pickup completes straight from READY.
        // Both edges exist here and the fulfilment mode picks between them, rather
        // than a pickup order being able to enter a courier state it has no
        // courier for.
        transitions.put(OrderStatus.READY, EnumSet.of(OrderStatus.FULFILLING, OrderStatus.COMPLETED));

        transitions.put(OrderStatus.FULFILLING, EnumSet.of(OrderStatus.COMPLETED));

        for (OrderStatus status : OrderStatus.values()) {
            if (status.terminal()) {
                transitions.put(status, EnumSet.noneOf(OrderStatus.class));
            }
        }
        return Map.copyOf(transitions);
    }

    private static Map<OrderStatus, Set<OrderStatus>> compensating() {
        Map<OrderStatus, Set<OrderStatus>> transitions = new EnumMap<>(OrderStatus.class);
        transitions.put(OrderStatus.READY, EnumSet.of(OrderStatus.PREPARING));
        transitions.put(OrderStatus.FULFILLING, EnumSet.of(OrderStatus.READY));
        return Map.copyOf(transitions);
    }

    public static Set<OrderStatus> transitionsFrom(OrderStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    public static boolean permits(OrderStatus from, OrderStatus to) {
        return transitionsFrom(from).contains(to);
    }

    /**
     * Every compensating edge declared from this status (ADR 0019 amendment,
     * ADR 0110) — a new forward step that happens to restore an earlier status,
     * never a literal reversal of the edge that produced {@code from}. Gated at
     * the call site on {@code Capability.ORDER_STATE_OVERRIDE}, never on {@code
     * ORDER_ADVANCE}: see {@link #COMPENSATING}'s own doc for why the two tables
     * are kept apart.
     */
    public static Set<OrderStatus> compensatingTransitionsFrom(OrderStatus from) {
        return COMPENSATING.getOrDefault(from, Set.of());
    }

    /** Whether {@code from -> to} is a declared compensating edge, not a forward one. */
    public static boolean isCompensating(OrderStatus from, OrderStatus to) {
        return compensatingTransitionsFrom(from).contains(to);
    }

    /**
     * Whether the transition is permitted for an order fulfilled this way.
     *
     * <p>{@code READY -> FULFILLING} is delivery only and {@code READY ->
     * COMPLETED} is pickup and dine-in only. Allowing either for both modes would
     * let a pickup order sit in a courier state nobody will ever advance, which
     * is one of the ways an order becomes permanently stuck.
     */
    public static boolean permits(OrderStatus from, OrderStatus to, FulfillmentMode mode) {
        if (!permits(from, to)) {
            return false;
        }
        if (from == OrderStatus.READY && to == OrderStatus.FULFILLING) {
            return mode == FulfillmentMode.DELIVERY;
        }
        if (from == OrderStatus.READY && to == OrderStatus.COMPLETED) {
            return mode != FulfillmentMode.DELIVERY;
        }
        return true;
    }

    /**
     * Fails rather than returning false, for the call sites where an illegal
     * transition is a programming error rather than a user's request.
     */
    public static void require(OrderStatus from, OrderStatus to) {
        if (!permits(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }

    /** Thrown when a caller asks for a transition the canonical machine does not have. */
    public static final class IllegalTransitionException extends IllegalStateException {

        private final OrderStatus from;
        private final OrderStatus to;

        public IllegalTransitionException(OrderStatus from, OrderStatus to) {
            super("An order cannot move from %s to %s (docs/domains/state-machines.md)".formatted(from, to));
            this.from = from;
            this.to = to;
        }

        public OrderStatus from() {
            return from;
        }

        public OrderStatus to() {
            return to;
        }
    }
}
