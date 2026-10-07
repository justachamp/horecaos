package uz.horecaos.platform.tenancy.api.geo;

import java.time.Instant;
import java.util.Objects;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * One answer to "where is this" or "what is here" (ADR 0015's {@code GeocodeAddress} /
 * {@code ReverseGeocodePoint} result): the point, the normalized components, the provider's
 * own reference, a confidence, a precision and the time it was calculated.
 *
 * <p><strong>A suggestion a person confirms, never a stored fact.</strong> ADR 0145 decision
 * 5: what the platform persists is the pin somebody accepted, as {@code CUSTOMER_PIN} or
 * {@code OPERATOR_PIN}. Nothing here has a path into {@code customer.addresses} as a
 * {@code GEOCODER} point.
 *
 * @param providerReference the provider's own handle for the object, opaque to us
 * @param provider          the adapter that answered, so a bad calibration can be traced to it
 */
public record GeocodeResult(
        GeoPoint point,
        AddressComponents components,
        String providerReference,
        GeocodeConfidence confidence,
        GeocodePrecision precision,
        Instant resolvedAt,
        String provider) {

    public GeocodeResult {
        Objects.requireNonNull(point, "A point is required");
        Objects.requireNonNull(components, "Components are required");
        Objects.requireNonNull(providerReference, "A provider reference is required");
        Objects.requireNonNull(confidence, "A confidence is required");
        Objects.requireNonNull(precision, "A precision is required");
        Objects.requireNonNull(resolvedAt, "A resolution time is required");
        Objects.requireNonNull(provider, "A provider is required");
    }

    /**
     * The region's box applied on the way back (ADR 0145 decision 7).
     *
     * <p>Sending the box as the provider's bias is not enough: a bias is a preference, and
     * the whole reason a box exists is a provider that preferred something else. A result
     * outside it is {@link GeocodeConfidence#LOW_CONFIDENCE} whatever the provider said,
     * and one already at that level stays there.
     */
    public GeocodeResult checkedAgainst(GeoBoundingBox box) {
        if (box.contains(point) || confidence == GeocodeConfidence.LOW_CONFIDENCE) {
            return this;
        }
        return new GeocodeResult(
                point,
                components,
                providerReference,
                GeocodeConfidence.LOW_CONFIDENCE,
                precision,
                resolvedAt,
                provider);
    }

    @Override
    public String toString() {
        return "GeocodeResult[provider=%s, confidence=%s, precision=%s]".formatted(provider, confidence, precision);
    }
}
