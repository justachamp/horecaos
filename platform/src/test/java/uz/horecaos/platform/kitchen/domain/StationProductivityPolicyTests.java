package uz.horecaos.platform.kitchen.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The productivity document (ADR 0163 Decision 4) as the validator sees it. A document is built before it is
 * judged, so a figure the author left out ({@code {"GRILL": null}}) has to survive construction to be refused
 * as a sentence: a constructor that throws on it is a 500 for a request that was only wrong.
 */
class StationProductivityPolicyTests {

    @Test
    @DisplayName("a null figure for a role is a violation, not an exception from building the document")
    void aNullRoleFigureIsRefusedAsAViolation() {
        Map<String, Integer> roles = new HashMap<>();
        roles.put("GRILL", null);

        StationProductivityPolicy policy =
                new StationProductivityPolicy(StationProductivityPolicy.SCHEMA, roles, Map.of());

        assertThat(policy.violations())
                .singleElement()
                .asString()
                .contains("roles.GRILL")
                .contains("between 1 and 1000");
    }

    @Test
    @DisplayName("a null figure for a station is a violation, not an exception from building the document")
    void aNullStationFigureIsRefusedAsAViolation() {
        Map<String, Integer> stations = new HashMap<>();
        stations.put("GRILL-1", null);

        StationProductivityPolicy policy =
                new StationProductivityPolicy(StationProductivityPolicy.SCHEMA, Map.of(), stations);

        assertThat(policy.violations())
                .singleElement()
                .asString()
                .contains("stations.GRILL-1")
                .contains("between 1 and 1000");
    }

    @Test
    @DisplayName("a station with a null figure falls back to its role's, exactly as one with no entry does")
    void aNullStationFigureResolvesToTheRole() {
        Map<String, Integer> stations = new HashMap<>();
        stations.put("GRILL-1", null);

        StationProductivityPolicy policy =
                new StationProductivityPolicy(StationProductivityPolicy.SCHEMA, Map.of("GRILL", 40), stations);

        assertThat(policy.figureFor("GRILL-1", StationRole.GRILL)).contains(40);
    }

    @Test
    @DisplayName("the figures a document holds cannot be changed after it is built")
    void theFiguresAreImmutable() {
        Map<String, Integer> roles = new HashMap<>();
        roles.put("GRILL", 40);
        StationProductivityPolicy policy =
                new StationProductivityPolicy(StationProductivityPolicy.SCHEMA, roles, Map.of());

        roles.put("HOT", 30);

        assertThat(policy.roles()).containsOnlyKeys("GRILL");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> policy.roles().put("HOT", 30))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
