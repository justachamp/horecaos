package uz.qoida.platform.tenancy.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import uz.qoida.platform.tenancy.api.GeoPoint;

/**
 * Where a branch physically is, and how to reach it.
 *
 * <p>Held in clear, and the contrast with {@code customer.addresses} is a decision
 * rather than an inconsistency. A customer's address is where one identifiable
 * person sleeps, so ADR 0029 seals it inside an encrypted document. A restaurant's
 * address is published by the merchant on purpose: it is printed on the receipt,
 * shown in the storefront, and handed to every courier who collects from it.
 * Encrypting it would put a decrypt on the hot path of every dispatch, for
 * information the merchant is actively advertising.
 *
 * <p>Every field is optional except the coordinate source, because a branch is
 * created before anyone has stood outside it. What is not optional is that the
 * record stays coherent: a point and a source that disagree would let a branch
 * claim to be geocoded while being unroutable.
 *
 * @param landmark ориентир. Not decoration — a large share of addresses in this
 *                 market are given this way, and a courier who cannot find the
 *                 service entrance of a branch inside a mall spends ten minutes
 *                 of somebody's promise looking for it
 */
public record LocationPlace(
        String addressLine,
        String district,
        String city,
        String landmark,
        String contactPhone,
        GeoPoint coordinates,
        CoordinateSource coordinateSource) {

    /**
     * E.164 and nothing else. A number stored as {@code 71 200 00 00} cannot be
     * dialled by a client, compared for equality, or handed to an SMS provider,
     * and every one of those failures surfaces somewhere far from here.
     */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    public LocationPlace {
        Objects.requireNonNull(coordinateSource, "A location must say why it has or lacks a point");

        // Mirrors ck_locations_coordinate_source_agrees. Stated as an equivalence
        // so neither direction can drift: a source without a point would tell
        // dispatch it has a routable branch when it does not, and a point without
        // a source would hide an unattributed coordinate from a provenance audit.
        if (coordinateSource.awaitingCoordinates() != (coordinates == null)) {
            throw new IllegalArgumentException(
                    "Coordinate source " + coordinateSource + " disagrees with the point");
        }

        addressLine = blankToNull(addressLine);
        district = blankToNull(district);
        city = blankToNull(city);
        landmark = blankToNull(landmark);
        contactPhone = blankToNull(contactPhone);

        if (contactPhone != null && !E164.matcher(contactPhone).matches()) {
            throw new IllegalArgumentException(
                    "Branch telephone must be E.164, for example +998712000000");
        }
    }

    /**
     * Blank collapses to absent rather than being stored.
     *
     * <p>An empty string satisfies "the field is set" while addressing nothing, so
     * without this a branch would report as fully configured and still put a blank
     * line on its receipts — a silent gap rather than a visible one.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** A branch that exists on paper and nowhere else yet. */
    public static LocationPlace unknown() {
        return new LocationPlace(null, null, null, null, null, null,
                CoordinateSource.NOT_GEOCODED);
    }

    public Optional<GeoPoint> point() {
        return Optional.ofNullable(coordinates);
    }

    /** Whether this branch can originate an ADR 0037 delivery zone. */
    public boolean isLocatable() {
        return coordinates != null;
    }
}
