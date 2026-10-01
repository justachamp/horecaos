package uz.horecaos.platform.inventory.api;

/**
 * How far a stop reaches (ADR 0141 Decision 2). The union of every covering
 * stop stops the sale; there is no "allow" record that overrides a broader one.
 */
public enum StopScopeType {

    /** Every channel at one branch. */
    LOCATION,

    /**
     * Every branch of the brand, including one bound after the stop was made:
     * the row holds no location, so there is nothing to go stale.
     */
    BRAND,

    /**
     * Every {@code (location, channel)} whose resolved {@code
     * catalog.branch_menu_bindings} menu is the one named.
     */
    MENU,

    /** One {@code tenant.sales_channels} row, at one location or everywhere it runs. */
    CHANNEL,

    /**
     * A name the platform knows and refuses (ADR 0141 Decision 3). A device that
     * takes orders and needs its own menu behaviour is already a sales channel
     * (ADR 0036: {@code KIOSK}, {@code POS} and {@code QR_TABLE}), so a stop "for
     * the kiosk" is a {@link #CHANNEL} stop at that location. This stays refused
     * until a device registry exists that can name two terminals in one channel.
     */
    TERMINAL;

    /** Whether a stop may be written at this scope today. */
    public boolean writable() {
        return this != TERMINAL;
    }
}
