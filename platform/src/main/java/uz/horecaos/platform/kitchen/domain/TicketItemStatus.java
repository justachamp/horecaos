package uz.horecaos.platform.kitchen.domain;

/**
 * One line's progress at one station (ADR 0041).
 *
 * <p>This is the fact the order cannot carry and the reason the kitchen is its
 * own aggregate: the grill can be done while the cold line is not, and an order
 * status has exactly one value.
 */
public enum TicketItemStatus {

    QUEUED,
    STARTED,
    READY,

    /** The line went away — a void or an amendment — while the ticket lived on. */
    CANCELLED;

    /** Whether this item still has to reach READY before the ticket can. */
    public boolean blocksTicketReadiness() {
        return this == QUEUED || this == STARTED;
    }
}
