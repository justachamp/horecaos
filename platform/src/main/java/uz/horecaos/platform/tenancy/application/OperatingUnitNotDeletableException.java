package uz.horecaos.platform.tenancy.application;

import org.jspecify.annotations.Nullable;

/**
 * A brand or location that cannot be deleted, with the reason in a form a
 * screen can branch on.
 *
 * <p>ADR 0031 clients never parse {@code detail} — it is written for a
 * developer, and in English — so the reason travels as its own problem
 * property and the console translates it. Otherwise every refusal would read
 * as the same "conflicts with something already recorded", and the one thing
 * an operator needs to know, what to remove first, would be lost.
 */
public final class OperatingUnitNotDeletableException extends RuntimeException {

    public enum Reason {
        /** It has been active, and keeps its history. */
        NOT_DRAFT,
        /** A brand that still owns locations; they are deleted first. */
        HAS_LOCATIONS,
        /** Staff still hold access scoped to it; revoking that is a decision about people. */
        HAS_ACCESS_GRANTS,
        /** Another record still refers to it, named by {@link #referencedBy()} where known. */
        STILL_REFERENCED
    }

    private final Reason reason;
    private final @Nullable String referencedBy;

    public OperatingUnitNotDeletableException(Reason reason, @Nullable String referencedBy, String message) {
        super(message);
        this.reason = reason;
        this.referencedBy = referencedBy;
    }

    public Reason reason() {
        return reason;
    }

    /** The table whose rows still refer to it, for {@link Reason#STILL_REFERENCED}; never a stored value. */
    public @Nullable String referencedBy() {
        return referencedBy;
    }
}
