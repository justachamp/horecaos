package uz.qoida.platform.inventory.api;

/**
 * How a location tracks one item (ADR 0017).
 *
 * <p>The first cutover slice runs on {@link #BINARY} and {@link #UNTRACKED}.
 * {@link #QUANTITY} is defined because the model has to accommodate it, and is
 * refused at runtime: a half-built quantity path that silently allows overselling
 * is worse than one that says it does not exist.
 */
public enum TrackingMode {

    /** On-hand, reserved, and available quantities are enforced. Not yet implemented. */
    QUANTITY,

    /** An explicit available/unavailable state. What a kitchen actually toggles. */
    BINARY,

    /**
     * Inventory never blocks checkout. The catalog's location offering can still
     * hide the item, so "untracked" means unlimited, not invisible.
     */
    UNTRACKED
}
