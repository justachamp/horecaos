package uz.horecaos.platform.ordering.domain;

import org.jspecify.annotations.Nullable;

/**
 * {@code fiscal.marking.structure_check} (ADR 0161): how much of a marking code's shape the server insists on.
 *
 * <p>{@link #GS1} (the default) checks the assumed GS1 element-string shape; {@link #NONE} checks only the
 * character set and length that {@link MarkingCodes#normalise} always checks. A value that is neither is
 * read as {@link #GS1}: the stricter reading is the safe one for a mistyped setting, and the configuration
 * layer refuses to store such a value in the first place.
 */
public enum MarkingStructureCheck {
    GS1,
    NONE;

    public static MarkingStructureCheck of(@Nullable String configured) {
        return "NONE".equals(configured) ? NONE : GS1;
    }
}
