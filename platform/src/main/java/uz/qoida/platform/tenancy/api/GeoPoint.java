package uz.qoida.platform.tenancy.api;

/**
 * A point on the earth, as WGS 84 decimal degrees.
 *
 * <p>In {@code tenancy.api} rather than in the domain because a branch's point is
 * something other modules legitimately need — delivery has to navigate to it, and
 * ADR 0037's zones are polygons drawn around it — while the rest of a location's
 * address is tenancy's own business.
 *
 * <p>A record with two primitives, so the pair cannot be half-present. The
 * three-valued-logic hole that let a latitude exist without a longitude into
 * {@code customer.addresses} was only possible because the pair was two
 * independently nullable columns; nothing constructed through this type can
 * reproduce it.
 */
public record GeoPoint(double latitude, double longitude) {

    public GeoPoint {
        // Finiteness first, and separately. Every comparison against NaN is false,
        // so a NaN latitude satisfies both "not below -90" and "not above 90" and
        // walks straight through a range check — then poisons every distance and
        // every zone test downstream, silently, because NaN also compares false
        // against the thresholds those use. PostgreSQL's double precision accepts
        // it happily, so the schema will not catch it either.
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            throw new IllegalArgumentException(
                    "A coordinate must be a finite number, was " + latitude + ", " + longitude);
        }
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude out of range: " + latitude);
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude out of range: " + longitude);
        }
    }
}
