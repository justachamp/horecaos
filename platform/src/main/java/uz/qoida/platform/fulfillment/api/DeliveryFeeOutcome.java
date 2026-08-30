package uz.qoida.platform.fulfillment.api;

/**
 * How fee resolution ended (ADR 0037).
 *
 * <p>Every value is a stable code a storefront branches on and never renders. The
 * refusals are deliberately distinct from one another: "this branch has no
 * coordinate" and "no zone covers this address" send an operator to two different
 * screens, and collapsing them into one reason sends them to the wrong one.
 */
public enum DeliveryFeeOutcome {

    /** A fee was computed and can be re-derived from the recorded evidence. */
    RESOLVED,

    /** The order is externally priced; resolution never started. */
    EXTERNALLY_PRICED,

    /**
     * The chosen location has no coordinate, so no distance and no circle can be
     * measured from it.
     */
    LOCATION_NOT_LOCATED,

    /**
     * The address is outside every {@code ACTIVE} {@code DELIVERY} zone bound to
     * the chosen location. Never re-homed to a location that does cover it: a
     * substituted branch changes the menu, the prices, the preparation time, and
     * eventually the legal entity issuing the receipt.
     */
    OUT_OF_ZONE,

    /**
     * Covered by a delivery zone but outside the location's catchment. Delever's
     * <em>не принимать заказы из других зон доставки</em>: without it, one branch
     * accepts an order from the far side of Tashkent through a shared city-wide
     * zone.
     */
    OUTSIDE_CATCHMENT,

    /**
     * No tariff on the zone, the location, or the brand. A missing rate table and
     * free delivery must never look alike, so there is no implicit zero.
     */
    NO_TARIFF,

    /** Inside the polygon, past the tariff's reach. */
    BEYOND_MAX_DISTANCE;

    public boolean isRefusal() {
        return this != RESOLVED && this != EXTERNALLY_PRICED;
    }
}
