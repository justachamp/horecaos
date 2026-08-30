package uz.qoida.platform.kitchen.domain;

/**
 * The production ticket's own lifecycle (ADR 0041).
 *
 * <p>Separate from {@code OrderStatus} on purpose. ADR 0019's machine is the
 * commercial contract with a customer and correctly forbids moving backwards from
 * {@code READY}; a kitchen recalls a dish from the pass, which is a real and
 * ordinary event. Modelling the kitchen as a projection of the order would leave
 * a recall with nowhere to go: it would either have to reverse the order, which
 * ADR 0019 refuses, or lie about the pass.
 */
public enum TicketStatus {

    /** In the buffer. The kitchen has not been told to start. */
    HELD(false),

    /** On a station's screen, nothing started yet. */
    FIRED(false),

    /** At least one item is being cooked. */
    IN_PRODUCTION(false),

    /** Every non-cancelled item is ready. The food is on the pass. */
    READY(false),

    /** Given to the customer or the courier. Terminal. */
    HANDED_OVER(true),

    /** The order died before the food did. Terminal. */
    VOIDED(true);

    private final boolean terminal;

    TicketStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean terminal() {
        return terminal;
    }

    /** Whether the ticket is on a kitchen board rather than in the buffer or gone. */
    public boolean live() {
        return this == FIRED || this == IN_PRODUCTION || this == READY;
    }
}
