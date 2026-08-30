package uz.horecaos.platform.dinein.domain;

/**
 * A table visit's lifecycle (ADR 0047).
 *
 * <p>Not an order status and deliberately unlike one. An order is a contract that
 * moves forward; a session is an evening, and an evening goes back — a party that
 * asked for the bill orders one more round, and a card that declines returns the
 * table to service rather than ending it.
 */
public enum SessionStatus {

    OPEN(false),

    /** The party has asked to pay. Rounds may still be added; see the machine. */
    BILL_REQUESTED(false),

    /** Money is being taken. */
    SETTLING(false),

    /** Paid, or opened in error and closed owing nothing. Terminal. */
    CLOSED(true),

    /**
     * Closed while still owing money. Terminal, and never reachable without
     * {@code dinein.session.force_close}, a reason code, and an audit record.
     */
    FORCE_CLOSED(true);

    private final boolean terminal;

    SessionStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean terminal() {
        return terminal;
    }

    /** Whether the session still occupies its tables and can take another round. */
    public boolean live() {
        return !terminal;
    }
}
