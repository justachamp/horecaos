package uz.qoida.platform.pricing.application;

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
    MODIFIER_OPTION
}
