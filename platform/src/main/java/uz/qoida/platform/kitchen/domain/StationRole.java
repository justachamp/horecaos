package uz.qoida.platform.kitchen.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The closed set of production station roles (ADR 0041).
 *
 * <p>Code-owned for the reason ADR 0025 gives for capabilities: the brand routing
 * layer assigns a catalogue node to a role and each location resolves that role
 * to its own station, so a role that one branch spells {@code гриль} and another
 * spells {@code Grill} routes to nothing at one of them. The failure is silent —
 * the line lands on the fallback screen — and silent mis-routing during service is
 * the thing this enum exists to make impossible.
 *
 * <p>Adding a role is a release, and a deliberate one: every location that wants
 * the new role has to create a station carrying it before any rule can reach it.
 */
public enum StationRole {

    HOT,
    COLD,
    GRILL,
    BAR,
    BAKERY,
    PACKING,

    /**
     * The pass. Not a cooking station: expo is where a finished ticket is checked
     * and handed over, which is why ADR 0041 gives it its own screen and its own
     * capabilities rather than treating it as one more line.
     */
    EXPO;

    public static Optional<StationRole> find(String name) {
        return name == null ? Optional.empty()
                : Arrays.stream(values())
                        .filter(role -> role.name().equals(name.toUpperCase(Locale.ROOT)))
                        .findFirst();
    }

    public static StationRole require(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException(
                "Unknown station role \"%s\". The set is closed (ADR 0041).".formatted(name)));
    }
}
