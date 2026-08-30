package uz.horecaos.platform.kitchen.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The kitchen's two state machines, transcribed from ADR 0041.
 *
 * <p>Code, not configuration, for the same reason {@code OrderStateMachine} is:
 * there is no table to override and no policy key that reaches it. A tenant
 * chooses which release mode a ticket uses; it never chooses which transitions
 * exist.
 *
 * <p>The one edge that is not obvious is {@code READY -> IN_PRODUCTION}. A recall
 * is permitted here, and refused by the application once a handover has been
 * recorded on the ticket — a rule about a specific ticket's history rather than
 * about the shape of the machine, which is why it does not live in this table.
 */
public final class KitchenStateMachine {

    private static final Map<TicketStatus, Set<TicketStatus>> TICKET = ticketTransitions();
    private static final Map<TicketItemStatus, Set<TicketItemStatus>> ITEM = itemTransitions();

    private KitchenStateMachine() {}

    private static Map<TicketStatus, Set<TicketStatus>> ticketTransitions() {
        Map<TicketStatus, Set<TicketStatus>> transitions = new EnumMap<>(TicketStatus.class);

        transitions.put(TicketStatus.HELD, EnumSet.of(TicketStatus.FIRED, TicketStatus.VOIDED));
        transitions.put(TicketStatus.FIRED, EnumSet.of(TicketStatus.IN_PRODUCTION, TicketStatus.VOIDED));
        transitions.put(TicketStatus.IN_PRODUCTION, EnumSet.of(TicketStatus.READY, TicketStatus.VOIDED));
        // The recall edge, and the handover that closes the ticket.
        transitions.put(
                TicketStatus.READY,
                EnumSet.of(TicketStatus.IN_PRODUCTION, TicketStatus.HANDED_OVER, TicketStatus.VOIDED));

        for (TicketStatus status : TicketStatus.values()) {
            if (status.terminal()) {
                transitions.put(status, EnumSet.noneOf(TicketStatus.class));
            }
        }
        return Map.copyOf(transitions);
    }

    private static Map<TicketItemStatus, Set<TicketItemStatus>> itemTransitions() {
        Map<TicketItemStatus, Set<TicketItemStatus>> transitions = new EnumMap<>(TicketItemStatus.class);

        transitions.put(TicketItemStatus.QUEUED, EnumSet.of(TicketItemStatus.STARTED, TicketItemStatus.CANCELLED));
        transitions.put(TicketItemStatus.STARTED, EnumSet.of(TicketItemStatus.READY, TicketItemStatus.CANCELLED));
        // An item recall, which is what a cook presses when they marked the wrong
        // line ready. It must be one press: the alternative is finding a manager
        // mid-service, and what actually happens then is that nobody corrects it.
        transitions.put(TicketItemStatus.READY, EnumSet.of(TicketItemStatus.STARTED));
        transitions.put(TicketItemStatus.CANCELLED, EnumSet.noneOf(TicketItemStatus.class));

        return Map.copyOf(transitions);
    }

    public static boolean permits(TicketStatus from, TicketStatus to) {
        return TICKET.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean permits(TicketItemStatus from, TicketItemStatus to) {
        return ITEM.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * The ticket status implied by the states of its items.
     *
     * <p>ADR 0041's roll-up: the first item to start moves the ticket into
     * production, and every non-cancelled item reaching ready moves it to ready.
     * Expressed as a pure function of the item set rather than as a counter on the
     * ticket, so three stations finishing in the same second cannot each see a
     * different partial count. The caller applies the answer with a conditional
     * update, and only one of the three wins.
     *
     * @param current what the ticket says now, returned unchanged when the items
     *                imply nothing — a ticket that is HELD is not dragged onto a
     *                board by an item somebody started early
     * @return the status the items imply, which may be {@code current}
     */
    public static TicketStatus rollUp(TicketStatus current, Iterable<TicketItemStatus> items) {
        if (current != TicketStatus.FIRED && current != TicketStatus.IN_PRODUCTION && current != TicketStatus.READY) {
            return current;
        }

        boolean anyLive = false;
        boolean anyStartedOrFinished = false;
        boolean anyBlocking = false;

        for (TicketItemStatus item : items) {
            if (item == TicketItemStatus.CANCELLED) {
                continue;
            }
            anyLive = true;
            if (item != TicketItemStatus.QUEUED) {
                anyStartedOrFinished = true;
            }
            if (item.blocksTicketReadiness()) {
                anyBlocking = true;
            }
        }

        // Every line was cancelled. The ticket is not ready — there is no food —
        // and saying so would put an empty bag on the pass. It stays where it is
        // and a human voids it.
        if (!anyLive) {
            return current;
        }
        if (!anyBlocking) {
            return TicketStatus.READY;
        }
        if (anyStartedOrFinished) {
            return TicketStatus.IN_PRODUCTION;
        }
        // Nothing has been touched since a recall put the ticket back in
        // production. It stays in production rather than reverting to FIRED: the
        // pass has already been told this ticket is late, and a board that moves
        // backwards is a board nobody trusts.
        return current == TicketStatus.READY ? TicketStatus.IN_PRODUCTION : current;
    }
}
