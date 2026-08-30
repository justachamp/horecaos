package uz.horecaos.platform.migration.domain;

import java.util.Objects;

/**
 * The modes a scope in this state is allowed to be in.
 *
 * <p>Write mode and read mode are not independent, so this pair is a type rather
 * than two fields that happen to travel together. Of the twelve combinations the
 * two enums can spell, five describe a system that works and seven describe one
 * that has already lost something. The constructor rejects the seven, which is
 * the difference between an invariant and a convention: a caller cannot hold an
 * incoherent pair long enough to persist it.
 *
 * <p>The three rules below are the whole of it. Reading a target that nothing is
 * filling returns confident nonsense. Reading legacy after the target took over
 * the writes returns the state of the world as it was before the customer's last
 * order. And treating the target as the authority while legacy still writes
 * makes the target's answer a stale copy that looks authoritative.
 */
public record OwnershipModes(WriteMode writeMode, ReadMode readMode) {

    public OwnershipModes {
        Objects.requireNonNull(writeMode, "Write mode is required");
        Objects.requireNonNull(readMode, "Read mode is required");
        if (readMode.touchesTarget() && writeMode == WriteMode.LEGACY_ONLY) {
            throw new IllegalArgumentException(
                    "Read mode %s reads a target that %s leaves unfilled".formatted(readMode, writeMode));
        }
        if (writeMode == WriteMode.TARGET_ONLY && readMode != ReadMode.TARGET) {
            throw new IllegalArgumentException(
                    "Read mode %s serves stale data once the target owns writes".formatted(readMode));
        }
        if (readMode == ReadMode.TARGET && writeMode != WriteMode.TARGET_ONLY) {
            throw new IllegalArgumentException(
                    "Read mode TARGET treats a follower as the authority while %s writes".formatted(writeMode));
        }
    }
}
