package uz.horecaos.platform.courier.domain;

import org.jspecify.annotations.Nullable;

/**
 * What one check of a pickup or a handover came to (ADR 0042, ADR 0178).
 *
 * <p>The courier client's contract reports these as {@code checks: [{kind, outcome}]}. {@code reason}
 * is the {@link GateRefusal} that failed the check, kept for the record and never sent as a figure:
 * where the courier stood, how far from the door and how good the fix was are not part of it.
 *
 * @param reason the refusal behind a {@link Outcome#FAILED}; null otherwise
 */
public record StepCheck(
        Kind kind, Outcome outcome, @Nullable GateRefusal reason) {

    /** What was checked. {@link #PHOTO} is in the vocabulary because the client's contract names it; no policy asks for one yet. */
    public enum Kind {
        GEO,
        PHOTO,
        PAYMENT
    }

    public enum Outcome {

        /** Evaluated and held. */
        PASSED,

        /** Evaluated and did not hold, or could not be evaluated. */
        FAILED,

        /** The location does not ask for this check. */
        SKIPPED
    }

    public StepCheck {
        if ((outcome == Outcome.FAILED) != (reason != null)) {
            throw new IllegalArgumentException(
                    "A failed check names its refusal, and a check that did not fail names none");
        }
    }

    public static StepCheck passed(Kind kind) {
        return new StepCheck(kind, Outcome.PASSED, null);
    }

    public static StepCheck skipped(Kind kind) {
        return new StepCheck(kind, Outcome.SKIPPED, null);
    }

    public static StepCheck failed(Kind kind, GateRefusal reason) {
        return new StepCheck(kind, Outcome.FAILED, reason);
    }

    public boolean failed() {
        return outcome == Outcome.FAILED;
    }
}
