package uz.horecaos.platform.tenancy.api.geo;

import java.util.Objects;

/**
 * Either an answer, which may be an empty one, or the fact that none could be had.
 *
 * <p>ADR 0007's four outcomes collapse here to the two a caller of a read can act on. A
 * geocode creates nothing, so there is no "uncertain" to reconcile: an unreadable answer is
 * simply unavailable, and the person is asked to try again.
 */
public sealed interface GeocodeOutcome<T> {

    record Answered<T>(T value) implements GeocodeOutcome<T> {
        public Answered {
            Objects.requireNonNull(value, "An answer is required");
        }

        @Override
        public String toString() {
            return "Answered[REDACTED]";
        }
    }

    record Unavailable<T>(GeoUnavailableReason reason) implements GeocodeOutcome<T> {
        public Unavailable {
            Objects.requireNonNull(reason, "A reason is required");
        }
    }

    static <T> GeocodeOutcome<T> answered(T value) {
        return new Answered<>(value);
    }

    static <T> GeocodeOutcome<T> unavailable(GeoUnavailableReason reason) {
        return new Unavailable<>(reason);
    }
}
