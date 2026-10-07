package uz.horecaos.platform.tenancy.api.geo;

import java.util.Objects;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * A south-west / north-east box (ADR 0037's region box), the constraint every geocoder call
 * is made inside.
 *
 * <p>Oriented on construction. An inverted or empty box accepts nothing or everything and
 * fails silently in both directions, which is exactly what {@code ck_region_bbox_oriented}
 * exists to prevent in the table; this refuses the same shapes so a hand-built value in a
 * test or an adapter cannot do what the database would not.
 */
public record GeoBoundingBox(GeoPoint southWest, GeoPoint northEast) {

    public GeoBoundingBox {
        Objects.requireNonNull(southWest, "A south-west corner is required");
        Objects.requireNonNull(northEast, "A north-east corner is required");
        if (northEast.latitude() <= southWest.latitude() || northEast.longitude() <= southWest.longitude()) {
            throw new IllegalArgumentException(
                    "A bounding box needs its north-east corner strictly " + "north and east of its south-west corner");
        }
    }

    /** Inclusive on every edge, like the database's own {@code BETWEEN}. */
    public boolean contains(GeoPoint point) {
        return point.latitude() >= southWest.latitude()
                && point.latitude() <= northEast.latitude()
                && point.longitude() >= southWest.longitude()
                && point.longitude() <= northEast.longitude();
    }
}
