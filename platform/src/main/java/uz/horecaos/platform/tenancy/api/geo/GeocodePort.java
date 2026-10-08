package uz.horecaos.platform.tenancy.api.geo;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * The provider-neutral geocoder (ADR 0145 decision 1, ADR 0015).
 *
 * <p>ADR 0015's two operations, {@code geocode} and {@code reverseGeocode}, plus the
 * {@code suggest} ADR 0145 adds. Each takes the region whose box constrains it and none
 * ever throws for a provider failure: the answer is {@link GeocodeOutcome.Unavailable}, and
 * what the caller does about it is the degradation order of ADR 0145 decision 6 (suggestions
 * fail and the field becomes text; the map fails and the address saves {@code NOT_GEOCODED};
 * an outage is a metric and an alert, never a failed checkout).
 *
 * <p><strong>The region box is applied on the way back by every implementation</strong> —
 * a result outside it is {@link GeocodeConfidence#LOW_CONFIDENCE} whatever the provider
 * claimed — and a contract test holds each adapter to it.
 *
 * <p>No parameter or result of this port names a vendor, and nothing here persists: the
 * response cache, the metering and the rate limit are the caller's.
 */
public interface GeocodePort {

    /**
     * Type-ahead completions for a half-typed address.
     *
     * @param near  where the person is looking, to rank nearer results first; absent means the
     *              region's centre
     * @param locale {@code ru}, {@code uz-Latn} or {@code en}: the language the list is shown in
     */
    GeocodeOutcome<List<GeoSuggestion>> suggest(String text, GeoRegion region, @Nullable GeoPoint near, String locale);

    /** Candidate places for an address, best first. An empty list is "nothing found", not a failure. */
    GeocodeOutcome<List<GeocodeResult>> geocode(String text, GeoRegion region, String locale);

    /** What is at a point, or empty when the provider knows nothing there. */
    GeocodeOutcome<Optional<GeocodeResult>> reverseGeocode(GeoPoint point, GeoRegion region, String locale);
}
