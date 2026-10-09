package uz.horecaos.platform.tenancy.api.geo;

/**
 * What the point actually names, from a door to a district.
 *
 * <p>A street centroid is a perfectly honest answer to "where is Amir Temur street" and a
 * dangerous one to "where is house 107": delivery fee and courier navigation both depend
 * on telling the two apart, which is why precision travels beside the point rather than
 * being folded into confidence.
 */
public enum GeocodePrecision {
    /** The building itself. */
    HOUSE,
    /** A building next to the one asked for, or an interpolated number on its street. */
    NEAR_HOUSE,
    /** The street, as a representative point. */
    STREET,
    /** A district, a city or a landmark with no street address. */
    LOCALITY,
    /** The provider did not say. Treated like the least precise answer, never like the best. */
    UNKNOWN
}
