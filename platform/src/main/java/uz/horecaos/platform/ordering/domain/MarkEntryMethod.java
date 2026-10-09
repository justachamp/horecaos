package uz.horecaos.platform.ordering.domain;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * How a code reached the console (ADR 0161). Client-reported and advisory: a scanner's keystroke burst is
 * {@link #SCANNER}, the browser's {@code BarcodeDetector} is {@link #CAMERA}, and anything pasted or typed
 * slowly is {@link #KEYED}, which is refused unless {@code fiscal.marking.allow_keyed_entry} is on -- the
 * human-readable text on a package does not include the cryptographic tail, so a keyed code is a guess.
 */
public enum MarkEntryMethod {
    SCANNER,
    CAMERA,
    KEYED;

    public static Optional<MarkEntryMethod> parse(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (MarkEntryMethod method : values()) {
            if (method.name().equals(value)) {
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }
}
