package uz.horecaos.platform.integration.camel.geo;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.geo.GeoRegion;

/**
 * One provider-neutral lookup, the in-process command the route carries (ADR 0007).
 *
 * <p>Camel writes an exchange body into route logs and into the message of any exception it
 * wraps, and this body is somebody's address or somebody's coordinate. {@code toString}
 * therefore prints the kind and the locale and nothing else, which is the discipline the
 * SMS route applies to a phone number.
 *
 * @param text  the address being typed or resolved; absent for a reverse lookup
 * @param point the point to reverse geocode, or the point to rank suggestions near
 */
public record GeoOperation(
        Kind kind, @Nullable String text, @Nullable GeoPoint point, GeoRegion region, String locale) {

    /** What is being asked. Bounded, and the metric label for the operation. */
    public enum Kind {
        SUGGEST,
        GEOCODE,
        REVERSE;

        public String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public GeoOperation {
        Objects.requireNonNull(kind, "A kind is required");
        Objects.requireNonNull(region, "A region is required");
        Objects.requireNonNull(locale, "A locale is required");
        if (kind == Kind.REVERSE && point == null) {
            throw new IllegalArgumentException("A reverse lookup needs a point");
        }
        if (kind != Kind.REVERSE && (text == null || text.isBlank())) {
            throw new IllegalArgumentException("A " + kind.label() + " lookup needs text");
        }
    }

    public static GeoOperation suggest(String text, GeoRegion region, @Nullable GeoPoint near, String locale) {
        return new GeoOperation(Kind.SUGGEST, text, near, region, locale);
    }

    public static GeoOperation geocode(String text, GeoRegion region, String locale) {
        return new GeoOperation(Kind.GEOCODE, text, null, region, locale);
    }

    public static GeoOperation reverse(GeoPoint point, GeoRegion region, String locale) {
        return new GeoOperation(Kind.REVERSE, null, point, region, locale);
    }

    @Override
    public String toString() {
        return "GeoOperation[kind=%s, locale=%s]".formatted(kind, locale);
    }
}
