package uz.horecaos.platform.partner.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The narrowed order lifecycle for {@code fulfillment_authority = PARTNER}
 * (ADR 0040).
 *
 * <p>HorecaOS stays the only writer of {@code ordering.orders.status}. This is not
 * a second state machine competing with ADR 0019's; it is a statement of which
 * of ADR 0019's transitions a partner-fulfilled order is allowed to take, and
 * the service refuses everything else. Two authorities over one state machine
 * turn a provider bug into a commercial fact, and ADR 0019 says so about systems
 * HorecaOS instructs — a marketplace partner is not one of them.
 *
 * <p>Statuses are strings rather than {@code ordering.domain.OrderStatus}
 * because that type belongs to another module's internals. The set here and the
 * set in {@code ck_order_status} agree, and a test asserts it.
 *
 * <p>Two things are deliberately different from a HorecaOS-fulfilled order.
 *
 * <p><strong>{@code FULFILLING} is skipped.</strong> It means "HorecaOS is
 * delivering this", and HorecaOS is not. Leaving it in the path would put every
 * aggregator order in the dispatch board's queue waiting for a courier nobody is
 * going to assign.
 *
 * <p><strong>{@code COMPLETED} is reached at proven handover, not at
 * delivery.</strong> Handover is the last event HorecaOS can observe. The customer
 * experiences "delivered" minutes to an hour later, which means any
 * delivery-time metric mixing channels is measuring two different things — a
 * consequence ADR 0040 accepts explicitly rather than papering over with a
 * fabricated delivery time.
 */
public final class MarketplaceOrderLifecycle {

    public static final String RECEIVED = "RECEIVED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String PREPARING = "PREPARING";
    public static final String READY = "READY";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String REJECTED = "REJECTED";

    /** The happy path, in order. */
    public static final List<String> PATH = List.of(RECEIVED, CONFIRMED, PREPARING, READY, COMPLETED);

    /**
     * The one exception granted knowingly: a partner may cancel an order HorecaOS
     * has already confirmed. Refusing it leaves a kitchen cooking food for a
     * customer the aggregator has already refunded, and the restaurant finds out
     * when the courier does not arrive.
     */
    public static final String PARTNER_CANCELLED_REASON = "PARTNER_CANCELLED";

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            RECEIVED, Set.of(CONFIRMED, REJECTED, CANCELLED),
            CONFIRMED, Set.of(PREPARING, CANCELLED),
            PREPARING, Set.of(READY, CANCELLED),
            READY, Set.of(COMPLETED, CANCELLED),
            COMPLETED, Set.of(),
            CANCELLED, Set.of(),
            REJECTED, Set.of());

    private MarketplaceOrderLifecycle() {}

    public static boolean permits(String from, String to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Whether a partner — as opposed to the restaurant — may drive this
     * transition. Exactly one, and only from a live status: a partner cancelling
     * an order that already completed is a partner arguing about a bag of food
     * that left the building, which is a settlement conversation and not a state
     * change.
     */
    public static boolean partnerMayDrive(String from, String to) {
        return CANCELLED.equals(to)
                && Set.of(RECEIVED, CONFIRMED, PREPARING, READY).contains(from);
    }

    public static boolean isTerminal(String status) {
        return TRANSITIONS.getOrDefault(status, Set.of()).isEmpty();
    }
}
