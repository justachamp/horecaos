package uz.horecaos.platform.iam.application.staff;

import java.util.Arrays;
import java.util.Optional;

/**
 * The closed set of screen families the console remembers something for (ADR 0160, Decision 2).
 *
 * <p>Owned by code: the table's {@code surface} column is a shape check and not an enumeration, so a new
 * surface is a release (a constant here, a schema in {@link StaffPreferenceSchemas}) and never a
 * migration, and a surface the code does not know is refused at the route, not stored.
 */
public enum StaffPreferenceSurface {
    /** The shell: the person's default branch. */
    SHELL,
    /** The order board: its optional columns and the person's saved views. */
    ORDER_BOARD;

    /** The surface a path names, exactly as spelled; anything else (a lower-case spelling included) is none. */
    public static Optional<StaffPreferenceSurface> ofName(String name) {
        return Arrays.stream(values())
                .filter(surface -> surface.name().equals(name))
                .findFirst();
    }
}
