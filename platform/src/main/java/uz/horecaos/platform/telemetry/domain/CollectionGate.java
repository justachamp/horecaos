package uz.horecaos.platform.telemetry.domain;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * When telemetry is collected inside an open duty session (ADR 0045).
 *
 * <p>Both are implemented and the value resolves through ADR 0030, which is what
 * makes a narrower answer from legal a configuration change rather than a
 * redesign. The default is {@link #ON_DUTY} for an operational reason a privacy
 * review should hear rather than be told: the dispatcher board exists to assign
 * work, assigning work means seeing who is free, and a courier carrying no order
 * is exactly the courier who is free. Gating collection on assignment does not
 * narrow the feature, it removes it.
 */
public enum CollectionGate {

    /** The whole session. A dispatcher sees idle couriers and can assign them. */
    ON_DUTY,

    /**
     * Only while carrying at least one active assignment. The narrower gate a
     * privacy review would prefer, and it costs the board its idle pins.
     */
    ON_ASSIGNMENT;

    /** Whether an observation is collected given how many assignments are live. */
    public boolean collects(int activeAssignmentCount) {
        return this == ON_DUTY || activeAssignmentCount > 0;
    }

    public static Optional<CollectionGate> find(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
