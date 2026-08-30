package uz.horecaos.platform.migration.application;

/**
 * How far a migration program has got.
 *
 * <p>Not in {@code migration.domain} with the rest of the vocabulary, and the
 * omission is deliberate: ADR 0024 names no program status, so unlike {@link
 * uz.horecaos.platform.migration.domain.ScopeState} this set is a decision of this
 * build rather than a pinned part of the ADR. It matches {@code
 * ck_program_status} in V0024 exactly, because a value the schema refuses would
 * strand a program mid-migration.
 *
 * <p>There is no {@code PAUSED}. Pausing is a scope-level fact, because a
 * program-wide pause that did not change the scopes would leave every ownership
 * answer unchanged while claiming the migration had stopped.
 */
public enum ProgramStatus {

    /** Scopes may be opened and planned. Nothing has moved and no run may start. */
    PLANNING,

    /** The program is executing: runs are permitted and scopes are transitioning. */
    ACTIVE,

    /** Every scope the program opened has retired. Terminal. */
    COMPLETED,

    /** Abandoned before completion. Terminal, and the scopes keep whatever they had. */
    ABANDONED;

    public boolean terminal() {
        return this == COMPLETED || this == ABANDONED;
    }

    /** Whether a scope may be opened or moved under a program in this status. */
    public boolean accepts() {
        return this == PLANNING || this == ACTIVE;
    }
}
