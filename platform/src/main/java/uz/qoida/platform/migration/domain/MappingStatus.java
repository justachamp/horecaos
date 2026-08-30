package uz.qoida.platform.migration.domain;

/**
 * What a legacy-to-target entity mapping is worth (ADR 0024).
 *
 * <p>Mappings are never deleted, which is why {@link #SUPERSEDED} exists. The
 * mapping is the only record of where a legacy identifier ended up, and it is
 * what a rollback, a support question, and every reconciliation of external
 * references are answered from. Deleting one to correct it would remove the
 * evidence that the correction happened.
 */
public enum MappingStatus {

    /** The legacy entity has a target counterpart under the current transformation. */
    MAPPED,

    /**
     * The entity could not be mapped and is held with a reason code and sanitized
     * evidence. Rows without provable tenant ownership land here rather than in a
     * convenient default tenant.
     */
    QUARANTINED,

    /** Replaced by a later mapping, usually by a remediation run. Kept for the trail. */
    SUPERSEDED;

    /** Whether this mapping is the one to resolve a legacy identifier through. */
    public boolean usable() {
        return this == MAPPED;
    }
}
