package uz.horecaos.platform.tenancy.api.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/** The shared geo vocabulary (ADR 0145): what a value may be, and what it will not say about itself. */
class GeoVocabularyTests {

    private static final GeoBoundingBox BOX =
            new GeoBoundingBox(new GeoPoint(41.15, 69.04), new GeoPoint(41.47, 69.46));

    private static GeocodeResult result(GeoPoint point, GeocodeConfidence confidence) {
        return new GeocodeResult(
                point,
                new AddressComponents(
                        "UZ", "Ташкент", null, "Fixture ko'chasi", "12", "Узбекистан, Ташкент, Fixture ko'chasi, 12"),
                "ref-1",
                confidence,
                GeocodePrecision.HOUSE,
                Instant.parse("2026-10-07T08:00:00Z"),
                "TEST");
    }

    @Test
    @DisplayName("a box is oriented on construction, so an inverted or empty one cannot exist")
    void aBoxMustBeOriented() {
        assertThatThrownBy(() -> new GeoBoundingBox(new GeoPoint(41.47, 69.04), new GeoPoint(41.15, 69.46)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoBoundingBox(new GeoPoint(41.15, 69.46), new GeoPoint(41.47, 69.04)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoBoundingBox(new GeoPoint(41.15, 69.04), new GeoPoint(41.15, 69.46)))
                .as("no area at all")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a box contains its edges and corners, and nothing past them")
    void containmentIsInclusive() {
        assertThat(BOX.contains(new GeoPoint(41.3, 69.2))).isTrue();
        assertThat(BOX.contains(new GeoPoint(41.15, 69.04)))
                .as("south-west corner")
                .isTrue();
        assertThat(BOX.contains(new GeoPoint(41.47, 69.46)))
                .as("north-east corner")
                .isTrue();
        assertThat(BOX.contains(new GeoPoint(41.14999, 69.2))).isFalse();
        assertThat(BOX.contains(new GeoPoint(41.3, 69.46001))).isFalse();
        // A latitude/longitude transposition is a perfectly valid point somewhere else entirely.
        assertThat(BOX.contains(new GeoPoint(69.2, 41.3))).isFalse();
    }

    @Test
    @DisplayName("a result outside the box is LOW_CONFIDENCE whatever it claimed; one inside is untouched")
    void checkedAgainstDowngradesOnlyWhatIsOutside() {
        GeocodeResult inside = result(new GeoPoint(41.3, 69.2), GeocodeConfidence.HIGH);
        GeocodeResult outside = result(new GeoPoint(42.87, 74.61), GeocodeConfidence.HIGH);

        assertThat(inside.checkedAgainst(BOX)).isSameAs(inside);
        GeocodeResult flagged = outside.checkedAgainst(BOX);
        assertThat(flagged.confidence()).isEqualTo(GeocodeConfidence.LOW_CONFIDENCE);
        assertThat(flagged.point()).isEqualTo(outside.point());
        assertThat(flagged.precision()).as("only the confidence moves").isEqualTo(GeocodePrecision.HOUSE);
        assertThat(flagged.providerReference()).isEqualTo("ref-1");
    }

    @Test
    @DisplayName("a result already LOW_CONFIDENCE stays itself, and the check never raises a confidence")
    void checkedAgainstNeverRaises() {
        GeocodeResult low = result(new GeoPoint(41.3, 69.2), GeocodeConfidence.LOW_CONFIDENCE);
        GeocodeResult medium = result(new GeoPoint(41.3, 69.2), GeocodeConfidence.MEDIUM);

        assertThat(low.checkedAgainst(BOX)).isSameAs(low);
        assertThat(medium.checkedAgainst(BOX).confidence()).isEqualTo(GeocodeConfidence.MEDIUM);
    }

    @Test
    @DisplayName("nothing a record prints carries the address, so a stray log line cannot")
    void toStringPrintsNoAddress() {
        GeocodeResult result = result(new GeoPoint(41.3, 69.2), GeocodeConfidence.HIGH);

        assertThat(result.toString())
                .doesNotContain("Fixture")
                .doesNotContain("41.3")
                .doesNotContain("Ташкент");
        assertThat(result.components().toString()).doesNotContain("Fixture").doesNotContain("Ташкент");
        assertThat(new GeoSuggestion("Fixture ko'chasi", "Ташкент", "Fixture ko'chasi, Ташкент", "ref", null)
                        .toString())
                .doesNotContain("Fixture")
                .doesNotContain("Ташкент");
        assertThat(GeocodeOutcome.answered(java.util.List.of(result)).toString())
                .doesNotContain("Fixture");
    }

    @Test
    @DisplayName("an unconfigured client config offers nothing and names no key")
    void notConfiguredOffersNothing() {
        MapClientConfig none = MapClientConfig.notConfigured();

        assertThat(none.configured()).isFalse();
        assertThat(none.provider()).isEqualTo("NONE");
        assertThat(none.browserKey()).isNull();
        assertThat(none.features()).isEmpty();
    }
}
