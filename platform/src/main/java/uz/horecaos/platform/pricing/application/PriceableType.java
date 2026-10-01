package uz.horecaos.platform.pricing.application;

/**
 * What a price can be attached to (ADR 0018).
 *
 * <p>The schema's third value, {@code FEE}, is deliberately absent from
 * authoring. A delivery charge is resolved from an ADR 0037 zone and tariff, not
 * looked up in a price book, and an operator able to put a second delivery price
 * somewhere the resolver never reads would be authoring a number that silently
 * does nothing.
 */
public enum PriceableType {
    VARIANT,
    MODIFIER_OPTION,
    /**
     * ADR 0136. What one variant costs <em>as offered inside one combo</em>, keyed to
     * a {@code catalog.combo_components} id and not to the variant. The same drink
     * can sit in two combos at two prices -- "free with the family box", "+3,000
     * som in the lunch box" -- and a variant-keyed price could not say so.
     *
     * <p>A type like the others and not a parallel path: price-book scope,
     * priority resolution, the close-and-open write and the simulator all apply to
     * it unchanged. The price is per unit of the component; the combo's container
     * variant is never priced.
     */
    COMBO_COMPONENT
}
