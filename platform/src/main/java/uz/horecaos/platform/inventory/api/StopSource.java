package uz.horecaos.platform.inventory.api;

/**
 * Who said a dish is stopped (ADR 0141 Decision 4): a closed, code-owned
 * vocabulary written on every stop, never parsed back out of a free-text reason.
 *
 * <p>Sources do not lift each other. A POS "back in stock" reading ends only
 * {@link #POS} stops, so it can no longer un-86 a dish an operator stopped, and
 * an operator lifting theirs no longer erases the POS's opinion.
 */
public enum StopSource {

    /** The console, single and bulk. */
    OPERATOR(true),

    /** The Telegram {@code /86} command (ADR 0060). */
    BOT(true),

    /** {@code PosAvailabilityPoll}; {@code source_ref} is the {@code integration.bindings} id. */
    POS(true),

    /** ADR 0041's kitchen device origin. Reserved: refused until its device write path exists. */
    KITCHEN_DEVICE(false),

    /** A future rule engine. Reserved: refused. */
    RULE(false);

    private final boolean writable;

    StopSource(boolean writable) {
        this.writable = writable;
    }

    /** Whether this source may write a stop today. */
    public boolean writable() {
        return writable;
    }
}
