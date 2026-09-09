package uz.horecaos.platform.pos.domain;

/** An operator's decision on one {@link SyncDifference} recommended {@code REVIEW} (ADR 0012). */
public enum ReviewOutcome {

    /** The provider's version becomes HorecaOS's, subject to {@link ApplyPlanner}. */
    APPROVED,

    /** The provider's version is recorded and never applied. */
    REJECTED,

    /** Neither decided nor ignored; stays in the queue for a later run of the same review. */
    DEFERRED
}
