package uz.horecaos.platform.fulfillment.domain;

/**
 * The lifecycle a zone version and a tariff version both follow (ADR 0037).
 *
 * <p>{@code DRAFT -> ACTIVE -> RETIRED}, or {@code DRAFT -> DISCARDED} for one
 * that never went live. One enum for both because the two are the same policy
 * object with different contents, and two copies of a four-value lifecycle
 * eventually disagree about what {@code RETIRED} permits.
 */
public enum VersionStatus {

    /** Authored and editable. Invisible to every quote. */
    DRAFT,

    /**
     * Live. Exactly one per lineage, enforced by a partial unique index rather
     * than by whichever service wrote the row.
     */
    ACTIVE,

    /**
     * Superseded or withdrawn, and kept for ever. Rollback retires versions
     * rather than deleting them, because an accepted quote pins one and a deleted
     * row turns that quote's evidence into a dangling id.
     */
    RETIRED,

    /** A draft abandoned before activation. */
    DISCARDED;

    public boolean isLive() {
        return this == ACTIVE;
    }
}
