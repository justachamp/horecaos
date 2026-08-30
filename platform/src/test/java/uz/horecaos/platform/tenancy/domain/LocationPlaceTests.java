package uz.horecaos.platform.tenancy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

import uz.horecaos.platform.tenancy.api.GeoPoint;

class LocationPlaceTests {

    private static final GeoPoint TASHKENT = new GeoPoint(41.311081, 69.240562);

    @Test
    void aNewBranchExistsOnPaperAndNowhereElse() {
        LocationPlace place = LocationPlace.unknown();

        assertThat(place.coordinateSource()).isEqualTo(CoordinateSource.NOT_GEOCODED);
        assertThat(place.isLocatable()).isFalse();
        assertThat(place.point()).isEmpty();
    }

    /**
     * The equivalence, in both directions. A source without a point tells dispatch
     * it has a routable branch when it has not; a point without a source hides an
     * unattributed coordinate from a provenance audit.
     */
    @Test
    void refusesASourceThatDisagreesWithThePoint() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LocationPlace(null, null, null, null, null, null, CoordinateSource.GEOCODER));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new LocationPlace(null, null, null, null, null, TASHKENT,
                        CoordinateSource.NOT_GEOCODED));
    }

    @Test
    void refusesACoordinateOutsideTheEarth() {
        assertThatIllegalArgumentException().isThrownBy(() -> new GeoPoint(91, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new GeoPoint(0, 181));
    }

    /**
     * Every comparison against NaN is false, so a NaN latitude passes a plain range
     * check in both directions and then compares false against every threshold
     * downstream — a branch that is silently outside every delivery zone, with no
     * error anywhere. The database will not catch it either: double precision
     * stores NaN.
     */
    @Test
    void refusesACoordinateThatIsNotAFiniteNumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> new GeoPoint(Double.NaN, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new GeoPoint(0, Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new GeoPoint(Double.POSITIVE_INFINITY, 0));
    }

    @Test
    void requiresATelephoneToBeDiallable() {
        assertThatIllegalArgumentException().isThrownBy(() -> place("71 200 00 00"));
        assertThatIllegalArgumentException().isThrownBy(() -> place("998712000000"));
        assertThatIllegalArgumentException().isThrownBy(() -> place("+998-71-200-00-00"));

        assertThat(place("+998712000000").contactPhone()).isEqualTo("+998712000000");
    }

    /**
     * Blank collapses to absent. Otherwise a branch reports as fully configured and
     * still prints an empty line on its receipts — a silent gap rather than one an
     * onboarding checklist can see.
     */
    @Test
    void treatsBlankFieldsAsAbsentRatherThanStoringThem() {
        LocationPlace place = new LocationPlace("   ", "", null, "  \t ", null, null,
                CoordinateSource.NOT_GEOCODED);

        assertThat(place.addressLine()).isNull();
        assertThat(place.district()).isNull();
        assertThat(place.landmark()).isNull();
    }

    @Test
    void stripsSurroundingWhitespaceFromWhatItKeeps() {
        LocationPlace place = new LocationPlace("  Amir Temur ko'chasi 12  ", null,
                "  Toshkent ", " Metro Bodomzor yonida ", null, null,
                CoordinateSource.NOT_GEOCODED);

        assertThat(place.addressLine()).isEqualTo("Amir Temur ko'chasi 12");
        assertThat(place.city()).isEqualTo("Toshkent");
        assertThat(place.landmark()).isEqualTo("Metro Bodomzor yonida");
    }

    @Test
    void aPinnedBranchCanOriginateADeliveryZone() {
        LocationPlace place = new LocationPlace("Amir Temur ko'chasi 12", "Yunusobod",
                "Toshkent", "Metro Bodomzor yonida", "+998712000000", TASHKENT,
                CoordinateSource.MERCHANT_PIN);

        assertThat(place.isLocatable()).isTrue();
        assertThat(place.point()).contains(TASHKENT);
        assertThat(place.coordinateSource().awaitingCoordinates()).isFalse();
    }

    private static LocationPlace place(String phone) {
        return new LocationPlace(null, null, null, null, phone, null,
                CoordinateSource.NOT_GEOCODED);
    }
}
