/**
 * The provider-neutral geocoding port and its vocabulary (ADR 0145, ADR 0015).
 *
 * <p>Declared beside {@link uz.horecaos.platform.tenancy.api.GeoPoint} because that is
 * where the platform's one definition of a point already lives, and needs its own named
 * interface for the reason {@code tenancy.api.onboarding} does: a {@code @NamedInterface}
 * on the parent package does not cover a sub-package, so without this file nothing outside
 * {@code tenancy} could implement {@link uz.horecaos.platform.tenancy.api.geo.GeocodePort}
 * and the adapter, which has to live in {@code integration}, could not compile.
 *
 * <p>Nothing here names a vendor. A map SDK type, a raw provider body and a provider's own
 * error vocabulary stay inside the adapter that owns them.
 */
@org.springframework.modulith.NamedInterface("geo")
package uz.horecaos.platform.tenancy.api.geo;
