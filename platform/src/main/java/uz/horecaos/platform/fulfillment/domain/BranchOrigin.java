package uz.horecaos.platform.fulfillment.domain;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * Where a branch actually is, once it is established that it is anywhere at all
 * (ADR 0037).
 *
 * <p>This type exists to make "the branch has no usable point" impossible to
 * carry around unnoticed. Every distance in this module and every circle drawn in
 * the zone editor starts from a branch, and the two ways a branch can fail to
 * have one are not symmetric:
 *
 * <ul>
 *   <li>V0023's {@code coordinate_source = 'NOT_GEOCODED'} means the coordinate
 *       columns are null. That is loud: any arithmetic on it fails immediately.</li>
 *   <li>(0, 0) is silent. It is a real point in the Gulf of Guinea, four hundred
 *       kilometres off Ghana, and PostGIS containment and haversine both accept it
 *       without a word. Three of the real legacy branches sit there. A branch at
 *       the null island is 6,000 km from every Uzbek address, so it resolves
 *       {@code BEYOND_MAX_DISTANCE} for every customer while reporting as fully
 *       configured — and the operator sent to look at it is shown a polygon that
 *       is not the problem.</li>
 * </ul>
 *
 * <p>Both are refused here, at construction, with a message that names which one
 * it was. V0025 adds the matching database constraint, so the null island cannot
 * arrive through the legacy import either.
 *
 * @param source V0023's {@code coordinate_source}, carried as the string the
 *               column holds. Tenancy's own {@code CoordinateSource} enum is
 *               internal to that module and deliberately not exposed, and
 *               widening its named interface so this record can hold a typed
 *               value would give every module a vote on how tenancy models a
 *               branch. The one value that decides anything here is
 *               {@code NOT_GEOCODED}, named once, below.
 */
public record BranchOrigin(UUID locationId, GeoPoint point, String source) {

    /** V0023: the coordinate columns are null exactly when the source says this. */
    private static final String NOT_GEOCODED = "NOT_GEOCODED";

    /**
     * @throws UnlocatedBranchException when the branch cannot originate a zone or
     *                                  a measurement
     */
    public static BranchOrigin of(
            UUID locationId, @Nullable Double latitude, @Nullable Double longitude, String coordinateSource) {

        if (latitude == null || longitude == null || NOT_GEOCODED.equals(coordinateSource)) {
            throw new UnlocatedBranchException(
                    locationId,
                    // Parenthesised so .formatted() applies to the whole message and
                    // not just the last concatenated fragment: unparenthesised, the
                    // %s above was never substituted and every refusal printed the
                    // literal text "(%s)" instead of the branch's coordinate_source.
                    ("This branch has no coordinate (%s), so no delivery zone can be drawn around "
                                    + "it and no distance can be measured from it. Place its pin before "
                                    + "configuring delivery.")
                            .formatted(coordinateSource));
        }
        if (latitude == 0.0 && longitude == 0.0) {
            throw new UnlocatedBranchException(
                    locationId,
                    "This branch is recorded at (0, 0), which is a point in the Gulf of Guinea "
                            + "rather than a missing value. Every distance and containment test "
                            + "accepts it, so it would serve nothing while reporting as located. "
                            + "Place its pin.");
        }
        return new BranchOrigin(locationId, new GeoPoint(latitude, longitude), coordinateSource);
    }

    /**
     * Thrown rather than returned.
     *
     * <p>An unlocated branch is a configuration fault and not a customer-visible
     * business answer, and the two must not look alike: returning "no zones" here
     * would tell an operator to redraw a polygon when what is wrong is that the
     * branch has never been placed on a map. The resolver catches it and turns it
     * into {@code LOCATION_NOT_LOCATED}, which says so.
     */
    public static final class UnlocatedBranchException extends RuntimeException {

        private final UUID locationId;

        public UnlocatedBranchException(UUID locationId, String message) {
            super(message);
            this.locationId = locationId;
        }

        public UUID locationId() {
            return locationId;
        }
    }
}
