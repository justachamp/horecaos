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
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = allowed();

    private OrderStateMachine() {
    }

    private static Map<OrderStatus, Set<OrderStatus>> allowed() {
        Map<OrderStatus, Set<OrderStatus>> transitions = new EnumMap<>(OrderStatus.class);

        // Three ways out of RECEIVED, chosen by payment timing and acceptance mode.
        transitions.put(OrderStatus.RECEIVED, EnumSet.of(
                OrderStatus.PAYMENT_AUTHORIZING,
                OrderStatus.AWAITING_APPROVAL,
                OrderStatus.CONFIRMED,
                OrderStatus.CANCELLED));

        transitions.put(OrderStatus.PAYMENT_AUTHORIZING, EnumSet.of(
                OrderStatus.AWAITING_APPROVAL,
                OrderStatus.CONFIRMED,
                OrderStatus.PAYMENT_FAILED,
                OrderStatus.CANCELLED));

        transitions.put(OrderStatus.AWAITING_APPROVAL, EnumSet.of(
                OrderStatus.CONFIRMED,
                OrderStatus.REJECTED,
                OrderStatus.EXPIRED,
                OrderStatus.CANCELLED));

        // CONFIRMED -> CANCELLED is in the canonical diagram, gated on policy.
        // The gate lives in the application because "policy permits" is a runtime
        // question; the transition existing at all is a modelling question and
        // belongs here.
        transitions.put(OrderStatus.CONFIRMED, EnumSet.of(
                OrderStatus.PREPARING,
                OrderStatus.CANCELLED));

        transitions.put(OrderStatus.PREPARING, EnumSet.of(OrderStatus.READY));

        // Delivery goes through FULFILLING; pickup completes straight from READY.
        // Both edges exist here and the fulfilment mode picks between them, rather
        // than a pickup order being able to enter a courier state it has no
        // courier for.
        transitions.put(OrderStatus.READY, EnumSet.of(
                OrderStatus.FULFILLING,
                OrderStatus.COMPLETED));

        transitions.put(OrderStatus.FULFILLING, EnumSet.of(OrderStatus.COMPLETED));

        for (OrderStatus status : OrderStatus.values()) {
            if (status.terminal()) {
                transitions.put(status, EnumSet.noneOf(OrderStatus.class));
            }
        }
        return Map.copyOf(transitions);
    }

    public static Set<OrderStatus> transitionsFrom(OrderStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    public static boolean permits(OrderStatus from, OrderStatus to) {
        return transitionsFrom(from).contains(to);
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
            super("An order cannot move from %s to %s (docs/domains/state-machines.md)"
                    .formatted(from, to));
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
