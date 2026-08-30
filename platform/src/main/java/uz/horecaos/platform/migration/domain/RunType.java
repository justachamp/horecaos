package uz.horecaos.platform.migration.domain;

/**
 * What a migration run was doing (ADR 0024).
 *
 * <p>The type is recorded rather than inferred from when the run happened,
 * because {@link #REMEDIATION} exists precisely to be distinguishable. A changed
 * transformation creates an explicit remediation run rather than quietly mixing
 * two mapping semantics in one entity family, and afterwards someone has to be
 * able to see which rows arrived under which version.
 */
public enum RunType {

    /** Bulk extraction of history, paged on a stable key and checkpointed. */
    BACKFILL,

    /** Incremental replication of committed legacy changes. */
    CATCH_UP,

    /** Reprocessing under a corrected mapping or transformation version. */
    REMEDIATION,

    /** Compares the two systems and records differences. Writes nothing. */
    RECONCILIATION;

    /** Whether this kind of run may write to the target at all. */
    public boolean writesTarget() {
        return this != RECONCILIATION;
    }
}
