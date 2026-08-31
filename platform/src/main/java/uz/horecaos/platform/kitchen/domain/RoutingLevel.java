package uz.horecaos.platform.kitchen.domain;

/**
 * Which of ADR 0041's five resolution levels put one order line on one station.
 *
 * <p>Stored on every ticket item. With five levels of precedence, "why is this
 * dish on the grill" has five possible answers and no way to tell them apart from
 * the routed row alone; a cook asking during service, and an operator trying to
 * fix a mis-mapped menu afterwards, both need the one that actually applied.
 *
 * <p>Declared most specific first, and
 * {@link uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore#resolveStation}
 * depends on that order.
 */
public enum RoutingLevel {

    /** A location override on the exact sellable variant. */
    LOCATION_VARIANT,

    /** A location override on the product the variant belongs to. */
    LOCATION_PRODUCT,

    /** A location override on a category the product sits in. */
    LOCATION_CATEGORY,

    /**
     * The brand assigned the node a station role, and the location has a station
     * carrying it. Variant, then product, then category, settled in the query.
     */
    BRAND_ROLE,

    /**
     * Nothing matched. The line goes to the location's fallback station and the
     * ticket records {@code ROUTING_UNRESOLVED}, because a line on no screen is a
     * dish nobody cooks and a customer who waits for it.
     */
    FALLBACK;

    /** Whether this level means the menu is missing a rule somebody has to write. */
    public boolean unresolved() {
        return this == FALLBACK;
    }
}
