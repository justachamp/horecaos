package uz.horecaos.platform.inventory.api;

/**
 * How a location tracks one item (ADR 0017).
 *
 * <p>{@link #QUANTITY} is enforced only where {@code catalog.use_stock_logic}
 * is on for the tenant (gap map row 4.4d): a stock item may be listed
 * {@code QUANTITY} regardless of the flag — the schema always allowed it, and
 * an operator reconciling counts before flipping the switch is the rollout
 * ADR 0017's own "quantity tracking for explicitly reconciled variants" phase
 * describes — but while the flag is off, a {@code QUANTITY} item behaves
 * exactly like {@link #UNTRACKED}: quantities are recorded but never block a
 * hold. See {@code InventoryService#evaluateAvailability}.
 */
public enum TrackingMode {

    /**
     * On-hand, reserved, and available quantities are enforced — while
     * {@code catalog.use_stock_logic} is on for the tenant. Off, an item
     * listed this way behaves like {@link #UNTRACKED}.
     */
    QUANTITY,

    /** An explicit available/unavailable state. What a kitchen actually toggles. */
    BINARY,

    /**
     * Inventory never blocks checkout. The catalog's location offering can still
     * hide the item, so "untracked" means unlimited, not invisible.
     */
    UNTRACKED
}
