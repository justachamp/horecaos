package uz.qoida.platform.pos.domain;

/**
 * What kind of thing the provider says a row is (ADR 0012).
 *
 * <p>Normalized from the provider's own vocabulary, and kept because the kind
 * decides whether a row is a menu candidate at all. The first real provider
 * returns ingredients and preparations from the same product list as dishes —
 * its own documentation example returns a tomato and an onion — so a normalizer
 * that ignored the kind would have the difference engine proposing vegetables as
 * draft customer-facing products on day one.
 */
public enum SourceKind {

    /** A stockable sellable item. May be a shell whose variants carry the price. */
    GOODS,

    /** A prepared dish. The only kind the first real provider lets carry modifiers. */
    DISH,

    /**
     * Billed by elapsed time — the provider's own example is a games console
     * rental, priced by rules in an opaque settings object. Excluded explicitly
     * rather than by accident, because "we did not think about it" and "we
     * decided against it" look identical in a filter.
     */
    TIMER,

    /** An intermediate the kitchen makes. Inventory, not menu. */
    PREPARATION,

    /** A raw input. Inventory, not menu. */
    INGREDIENT,

    /** A size or colour of a parent product, carrying its own price and stock. */
    VARIANT,

    /**
     * The provider sent a kind this platform does not recognise.
     *
     * <p>Not comparable, and not silently dropped either. A new kind appearing in
     * a provider's catalog is worth an operator seeing, because it usually means
     * the restaurant started using a feature nobody told us about.
     */
    UNKNOWN;

    /** Whether a row of this kind is a candidate for a customer-facing menu. */
    public boolean menuCandidate() {
        return this == GOODS || this == DISH || this == VARIANT;
    }
}
