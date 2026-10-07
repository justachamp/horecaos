package uz.horecaos.platform.tenancy.api.geo;

/**
 * How far a person should trust a result without looking at it (ADR 0015).
 *
 * <p>{@link #LOW_CONFIDENCE} is not only the provider's opinion. A result outside the
 * region's box is forced to it whatever the provider claimed (ADR 0037: an unconstrained
 * geocoder asked for a Tashkent street name returns a plausible street of the same name in
 * another country), and a result at this level is never accepted without a person
 * confirming it on a map.
 */
public enum GeocodeConfidence {
    HIGH,
    MEDIUM,
    LOW_CONFIDENCE
}
