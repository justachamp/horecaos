package uz.horecaos.platform.fulfillment.domain;

import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * Great-circle distance, which is what {@code RADIUS} mode means (ADR 0037).
 *
 * <p>Computed here rather than by PostGIS so the number that decides a fee is
 * produced by code with a test on it, and so a resolution can be re-derived
 * without a database. The two agree to well under a metre at city scale, which is
 * far inside the granularity anything downstream cares about — bands are metres
 * and the variable component rounds up to whole kilometres.
 *
 * <p><strong>This is not road distance.</strong> A straight line through a river,
 * a railway or a closed courtyard is shorter than anything a courier can ride,
 * and in a city laid out on a grid the difference is routinely a third. Under
 * {@code RADIUS} that under-measurement is the tenant's deliberate, published
 * choice; under {@code ROAD} it is only ever a fallback, multiplied by the
 * tariff's detour factor and recorded as {@code RADIUS_FALLBACK} so nobody
 * mistakes one for the other.
 */
public final class Haversine {

    /**
     * IUGG mean radius. Not the equatorial radius: at Tashkent's latitude the
     * equatorial figure overstates every distance by roughly a tenth of a percent,
     * which is invisible until it moves a 3,000 m address across a band boundary.
     */
    private static final double EARTH_MEAN_RADIUS_METERS = 6_371_008.8;

    private Haversine() {
    }

    /** Metres, rounded half up, because a band boundary is stated in whole metres. */
    public static int metersBetween(GeoPoint from, GeoPoint to) {
        double fromLatitude = Math.toRadians(from.latitude());
        double toLatitude = Math.toRadians(to.latitude());
        double deltaLatitude = toLatitude - fromLatitude;
        double deltaLongitude = Math.toRadians(to.longitude() - from.longitude());

        double a = Math.pow(Math.sin(deltaLatitude / 2), 2)
                + Math.cos(fromLatitude) * Math.cos(toLatitude)
                * Math.pow(Math.sin(deltaLongitude / 2), 2);

        // atan2 rather than asin. asin loses precision as the argument approaches
        // 1, which is the antipodal case; it never arises for a delivery, but a
        // formula that is correct only for the inputs someone expected is how a
        // NaN reaches a fee.
        double centralAngle = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return Math.toIntExact(Math.round(EARTH_MEAN_RADIUS_METERS * centralAngle));
    }
}
