package uz.horecaos.platform.migration.domain;

/**
 * Who owns business writes for one capability in one scope (ADR 0024).
 *
 * <p>Exactly one system writes a capability at any moment, and this enum is
 * where that claim is checkable rather than merely asserted: {@link
 * #targetMayWrite()} and {@link #legacyMayWrite()} are exclusive for every
 * constant, so there is no value of this type that describes two authorities.
 *
 * <p>There is deliberately no {@code DUAL_WRITE}. ADR 0024 rejected dual write
 * from request handlers outright — two writers diverge on the first partial
 * failure, and afterwards nothing can decide which side is right. The migration
 * still puts data into the target before cutover, but through a replicator that
 * transforms already-committed legacy changes into idempotent target commands,
 * which is {@link #LEGACY_WITH_TARGET_SHADOW}: one authority, one follower.
 */
public enum WriteMode {

    /** Legacy is the only writer, and nothing is maintaining a target copy yet. */
    LEGACY_ONLY,

    /**
     * Legacy is still the authority, and the migration's own importer is keeping
     * a target copy in step behind it. Request handlers in the target still may
     * not write; the follower is fed from committed legacy changes, never from
     * the same request.
     */
    LEGACY_WITH_TARGET_SHADOW,

    /** The target is the authority. Legacy is fenced at the capability boundary. */
    TARGET_ONLY;

    /** Whether a request handler in the target may commit a business write. */
    public boolean targetMayWrite() {
        return this == TARGET_ONLY;
    }

    /** Whether legacy is still the authority for this capability. */
    public boolean legacyMayWrite() {
        return this != TARGET_ONLY;
    }

    /**
     * Whether the migration's importer may write to the target.
     *
     * <p>True for exactly one mode, and the narrowness is the point: the importer
     * feeds a <em>follower</em>. {@link #LEGACY_WITH_TARGET_SHADOW} is the only
     * mode in which a follower exists — under {@link #LEGACY_ONLY} nothing is
     * being maintained for it to write into, and under {@link #TARGET_ONLY} the
     * target is the authority and there is no longer anything following.
     *
     * <p>Reading this as "anything except LEGACY_ONLY" is the mistake worth
     * naming, because it is the plausible one. It admits a catch-up run against a
     * capability that has already cut over — "one last sweep before we freeze
     * legacy" — which replays legacy-sourced rows over target-owned facts and
     * recreates the two-authority state the whole module exists to prevent. It is
     * reachable by an ordinary operator action, not by a race.
     *
     * <p>Separate from {@link #targetMayWrite()} on purpose. Import writes carry
     * the ADR 0024 suppression of external effects; a request handler writing
     * under the same permission would be the dual write this enum forbids.
     */
    public boolean importMayWrite() {
        return this == LEGACY_WITH_TARGET_SHADOW;
    }
}
