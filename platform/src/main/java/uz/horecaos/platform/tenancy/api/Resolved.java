package uz.horecaos.platform.tenancy.api;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A resolved configuration value together with where it came from (ADR 0030).
 *
 * <p>Every resolution can explain itself; support tooling and control-plane
 * screens display the trace rather than guessing which level applied.
 *
 * @param value absent when resolution stopped at an explicit null (a key
 *              declaring {@code explicitNullTerminates()}) or when the key's
 *              own code default is itself absent
 */
public record Resolved<T>(@Nullable T value, ResolutionTrace trace) {

    public Resolved {
        Objects.requireNonNull(trace, "A resolution trace is required");
    }

    public Optional<T> asOptional() {
        return Optional.ofNullable(value);
    }

    public boolean cameFromDefault() {
        return trace.source() == ResolutionTrace.Source.CODE_DEFAULT;
    }
}
