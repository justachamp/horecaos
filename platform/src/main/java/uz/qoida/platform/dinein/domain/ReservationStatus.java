package uz.qoida.platform.dinein.domain;

/**
 * A booking's lifecycle (ADR 0047).
 *
 * <p>{@link #CONFIRMED} and {@link #SEATED} are the only two statuses that hold a
 * table out of the market, and V0034's exclusion constraint names exactly those
 * two. Everything else — a request nobody has answered, a rejection, a
 * cancellation, a no-show, a finished dinner — holds nothing, which is why a
 * cancelled booking frees its table the moment its status changes rather than
 * when its interval elapses.
 */
public enum ReservationStatus {

    /** Asked for and not yet answered. Holds no table. */
    REQUESTED(false, false),

    /** The restaurant has committed. This is where the hold begins. */
    CONFIRMED(true, false),

    REJECTED(false, true),

    /** The party arrived and a session was opened. Still holds the table. */
    SEATED(true, false),

    CANCELLED(false, true),

    NO_SHOW(false, true),

    COMPLETED(false, true);

    private final boolean holdsTable;
    private final boolean terminal;

    ReservationStatus(boolean holdsTable, boolean terminal) {
        this.holdsTable = holdsTable;
        this.terminal = terminal;
    }

    /** Whether V0034's exclusion constraint enforces this booking's interval. */
    public boolean holdsTable() {
        return holdsTable;
    }

    public boolean terminal() {
        return terminal;
    }
}
