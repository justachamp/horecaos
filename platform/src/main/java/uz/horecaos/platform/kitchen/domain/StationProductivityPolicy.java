package uz.horecaos.platform.kitchen.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The {@code kitchen.station_productivity} policy document (ADR 0163 Decision 4, ADR 0030): whole portions
 * one cook produces in an hour, per station role and per station code.
 *
 * <p>{@code { "schema": 1, "roles": { "GRILL": 40, "HOT": 30 }, "stations": { "GRILL-1": 35 } }}. A station
 * resolves to its {@code stations[code]}, else its {@code roles[role]}, else nothing. <strong>The built-in
 * default is the empty document</strong>: the platform supplies no productivity figure, and a tenant that has
 * not published one has no cook count for any station. A figure is a pace and never a person: nothing here is
 * keyed to a named employee.
 *
 * <p>This is not {@code kitchen.station_capacity.portions_per_hour}, the plant ceiling "the grill does 40
 * plates an hour": a grill that tops out at 40 may be worked by one cook or by two.
 */
public record StationProductivityPolicy(int schema, Map<String, Integer> roles, Map<String, Integer> stations) {

    public static final int SCHEMA = 1;
    public static final int MIN_PORTIONS_PER_COOK_HOUR = 1;
    public static final int MAX_PORTIONS_PER_COOK_HOUR = 1000;

    /** Matches {@code ck_station_code} (V0030). */
    private static final Pattern STATION_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,31}$");

    /** The document in force when nothing is published: no figure for any station. */
    public static final StationProductivityPolicy EMPTY = new StationProductivityPolicy(SCHEMA, Map.of(), Map.of());

    public StationProductivityPolicy {
        roles = snapshot(roles);
        stations = snapshot(stations);
    }

    /**
     * An unmodifiable, sorted copy that <em>keeps a null figure</em> ({@code {"GRILL": null}} is a document an
     * author can send): {@code Map.copyOf} throws on one, and a constructor that throws turns a request the
     * validator exists to refuse into a 500 before {@link #violations} is ever asked.
     */
    private static Map<String, Integer> snapshot(Map<String, Integer> figures) {
        return Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNullElse(figures, Map.of())));
    }

    /** The figure for a station: its own code's, else its role's, else none. */
    public Optional<Integer> figureFor(String stationCode, StationRole role) {
        Integer own = stations.get(stationCode);
        if (own != null) {
            return Optional.of(own);
        }
        return Optional.ofNullable(roles.get(role.name()));
    }

    /** Why this document may not be published, as sentences; empty when it may. */
    public List<String> violations() {
        List<String> found = new ArrayList<>();
        if (schema != SCHEMA) {
            found.add("schema must be %d".formatted(SCHEMA));
        }
        roles.forEach((name, figure) -> {
            Optional<StationRole> role =
                    StationRole.find(name).filter(candidate -> candidate.name().equals(name));
            if (role.isEmpty()) {
                found.add("%s is not a station role".formatted(name));
            } else if (role.get() == StationRole.EXPO) {
                found.add("EXPO is the pass, not a cooking station: it is never planned (ADR 0041)");
            }
            checkFigure("roles." + name, figure, found);
        });
        stations.forEach((code, figure) -> {
            if (!STATION_CODE.matcher(code).matches()) {
                found.add("%s is not a station code".formatted(code));
            }
            checkFigure("stations." + code, figure, found);
        });
        return List.copyOf(found);
    }

    private static void checkFigure(String where, Integer figure, List<String> found) {
        if (figure == null || figure < MIN_PORTIONS_PER_COOK_HOUR || figure > MAX_PORTIONS_PER_COOK_HOUR) {
            found.add("%s must be between %d and %d portions per cook-hour"
                    .formatted(where, MIN_PORTIONS_PER_COOK_HOUR, MAX_PORTIONS_PER_COOK_HOUR));
        }
    }
}
