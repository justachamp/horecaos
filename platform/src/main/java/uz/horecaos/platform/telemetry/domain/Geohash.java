package uz.horecaos.platform.telemetry.domain;

/**
 * Five-character geohashes, and great-circle distance (ADR 0045).
 *
 * <p>The geohash is the only cleartext locational value on the track tier. Five
 * characters is a cell of roughly 5 km by 5 km at this latitude — call it 1.2 km
 * of positional certainty in the sense that matters, which is that it names a
 * district and nothing narrower. It exists so that a time-bounded reveal finds
 * the windows it needs without decrypting every row of every day in the range,
 * and it is deliberately too coarse to reconstruct a route from.
 *
 * <p>The distance function duplicates fulfillment's own haversine, and the
 * duplication is on purpose. {@code fulfillment.domain} is module-internal and
 * exporting a geometry helper from it would widen fulfillment's published surface
 * to serve a caller that has nothing else to do with delivery fees. Eighteen
 * lines of arithmetic with a test is a smaller cost than a shared kernel module
 * nobody owns.
 */
public final class Geohash {

    private static final char[] BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();
    private static final int EARTH_RADIUS_METERS = 6_371_000;

    private Geohash() {
    }

    /** The five-character geohash of a point. */
    public static String encode5(double latitude, double longitude) {
        double minLatitude = -90;
        double maxLatitude = 90;
        double minLongitude = -180;
        double maxLongitude = 180;

        StringBuilder hash = new StringBuilder(5);
        boolean evenBit = true;
        int bit = 0;
        int index = 0;

        while (hash.length() < 5) {
            if (evenBit) {
                double middle = (minLongitude + maxLongitude) / 2;
                if (longitude >= middle) {
                    index = index * 2 + 1;
                    minLongitude = middle;
                } else {
                    index *= 2;
                    maxLongitude = middle;
                }
            } else {
                double middle = (minLatitude + maxLatitude) / 2;
                if (latitude >= middle) {
                    index = index * 2 + 1;
                    minLatitude = middle;
                } else {
                    index *= 2;
                    maxLatitude = middle;
                }
            }
            evenBit = !evenBit;

            if (++bit == 5) {
                hash.append(BASE32[index]);
                bit = 0;
                index = 0;
            }
        }
        return hash.toString();
    }

    /**
     * Great-circle distance in whole metres.
     *
     * <p>Rounded here rather than at the column, so the value that is stored is
     * the value that was computed. A distance is never a double past this point:
     * every reader of it is a person arguing about a delivery.
     */
    public static int distanceMeters(double fromLatitude, double fromLongitude,
            double toLatitude, double toLongitude) {

        double deltaLatitude = Math.toRadians(toLatitude - fromLatitude);
        double deltaLongitude = Math.toRadians(toLongitude - fromLongitude);
        double a = Math.sin(deltaLatitude / 2) * Math.sin(deltaLatitude / 2)
                + Math.cos(Math.toRadians(fromLatitude)) * Math.cos(Math.toRadians(toLatitude))
                * Math.sin(deltaLongitude / 2) * Math.sin(deltaLongitude / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (int) Math.round(EARTH_RADIUS_METERS * c);
    }
}
