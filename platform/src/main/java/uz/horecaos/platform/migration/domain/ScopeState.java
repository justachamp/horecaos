package uz.horecaos.platform.migration.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Where one capability, in one tenant/brand/location scope, stands in its move
 * from legacy to target (ADR 0024).
 *
 * <p>The scope is the unit of ownership, not the table and not the program. ADR
 * 0024 rejected migrating table by table precisely because it produces
 * half-migrated journeys with two writers and no coherent rollback unit, so this
 * enum describes a whole capability changing hands and nothing smaller.
 *
 * <p>Each state carries the ownership modes it permits, because a state and its
 * modes are one fact recorded twice. A scope that claims to be in {@link
 * #DISCOVERY} while the target owns its writes is not a scope in discovery; it
 * is a scope whose control-plane row has drifted from what production is
 * actually doing, and by the time anyone notices, nobody can say which of the
 * two systems accepted the last order.
 *
 * <p>{@link #RETIRED} is the only terminal state. Every other state has an
 * outgoing move, and the two holding states have {@link ScopeStateMachine}'s
 * resume instead of an edge in the table. A scope that could be stranded would
 * need database surgery to finish its migration, and manual database updates are
 * exactly what the ADR refuses to accept as a cutover mechanism.
 */
public enum ScopeState {

    /**
     * The source is being inventoried: tables, volumes, change rate, retention,
     * and how reliably the source carries a tenant key.
     */
    DISCOVERY(false, false),

    /**
     * Field mapping, defaults, transformations, stable-ID policy, and quarantine
     * reasons are approved and recorded. Still nothing has moved.
     */
    MAPPING_APPROVED(false, false),

    /** Bulk extraction is running, checkpointing only after each target commit. */
    BACKFILLING(false, false),

    /**
     * The bulk load is done and the replicator is following committed legacy
     * changes. The gap between the two systems is now bounded rather than
     * growing.
     */
    CATCHING_UP(false, false),

    /**
     * Both systems answer every read and the differences are recorded. The
     * caller still gets the legacy answer.
     */
    SHADOW_READING(false, false),

    /** A bounded share of reads is answered by the target and watched. */
    CANARY(false, false),

    /**
     * Reconciliation passed, approvals are recorded against the evidence, and
     * the scope is waiting for its window. Routing has not changed yet.
     */
    CUTOVER_READY(false, false),

    /** The target owns the writes. Legacy is fenced at the capability boundary. */
    TARGET_OWNED(false, false),

    /**
     * The soak period after cutover, during which legacy is kept warm and
     * restorable and the business metrics are watched.
     */
    ROLLBACK_WINDOW(false, false),

    /**
     * The window has closed and legacy is frozen for audit and export. The name
     * describes what happened to the legacy system, not where the platform reads
     * from; reads have been served by the target since cutover.
     */
    LEGACY_READ_ONLY(false, false),

    /**
     * Access, integrations, credentials, and scheduled jobs are revoked and the
     * signoffs are recorded. Terminal: reviving a retired scope is a new
     * program, not a transition.
     */
    RETIRED(true, false),

    /** Deliberately suspended. The scope keeps the routing it had. */
    PAUSED(false, true),

    /**
     * Suspended by a reconciliation difference that blocks progress. Distinct
     * from {@link #PAUSED} because a person chose one and evidence forced the
     * other, and an operator resuming a scope needs to know which.
     */
    BLOCKED_RECONCILIATION(false, true),

    /**
     * Target ownership is being handed back to legacy after a money, state,
     * authorization, or provider failure. Reachable only from {@link #CANARY} and
     * {@link #TARGET_OWNED}, and it is an ownership and traffic operation with
     * reconciliation, never an undo of the data movement.
     */
    ROLLING_BACK(false, false);

    private static final Map<ScopeState, Set<OwnershipModes>> PERMITTED = permitted();

    private final boolean terminal;
    private final boolean holding;

    ScopeState(boolean terminal, boolean holding) {
        this.terminal = terminal;
        this.holding = holding;
    }

    /** No transition leaves a terminal state. */
    public boolean terminal() {
        return terminal;
    }

    /**
     * Whether this state suspends a scope rather than advancing it.
     *
     * <p>A holding state is entered from wherever the scope was and left back to
     * the same place, so it has no outgoing edges of its own. Callers use this to
     * tell an empty {@link ScopeStateMachine#nextFrom(ScopeState)} that means
     * "finished" from one that means "ask the scope where it came from".
     */
    public boolean holding() {
        return holding;
    }

    /** The ownership modes a scope in this state may be in. */
    public Set<OwnershipModes> permittedModes() {
        return PERMITTED.getOrDefault(this, Set.of());
    }

    public boolean permits(OwnershipModes modes) {
        return permittedModes().contains(modes);
    }

    private static Map<ScopeState, Set<OwnershipModes>> permitted() {
        OwnershipModes untouched = new OwnershipModes(WriteMode.LEGACY_ONLY, ReadMode.LEGACY);
        OwnershipModes following = new OwnershipModes(WriteMode.LEGACY_WITH_TARGET_SHADOW, ReadMode.LEGACY);
        OwnershipModes comparing = new OwnershipModes(WriteMode.LEGACY_WITH_TARGET_SHADOW, ReadMode.SHADOW_COMPARE);
        OwnershipModes canarying = new OwnershipModes(WriteMode.LEGACY_WITH_TARGET_SHADOW, ReadMode.CANARY_TARGET);
        OwnershipModes owned = new OwnershipModes(WriteMode.TARGET_ONLY, ReadMode.TARGET);

        Map<ScopeState, Set<OwnershipModes>> modes = new EnumMap<>(ScopeState.class);

        // Nothing is being written to the target, so nothing may be read from it.
        modes.put(DISCOVERY, Set.of(untouched));
        modes.put(MAPPING_APPROVED, Set.of(untouched));

        // The follower is being filled and then kept in step. Reads stay on legacy
        // throughout: an incomplete backfill answers a question wrongly rather than
        // slowly, and there is no way for the caller to tell the difference.
        modes.put(BACKFILLING, Set.of(following));
        modes.put(CATCHING_UP, Set.of(following));

        modes.put(SHADOW_READING, Set.of(comparing));
        modes.put(CANARY, Set.of(canarying));

        // Being ready to cut over is a decision about evidence, not a routing
        // change, so the scope keeps the canary it was already running. Reads move
        // once, at cutover, where there is a window and someone watching.
        modes.put(CUTOVER_READY, Set.of(canarying));

        modes.put(TARGET_OWNED, Set.of(owned));
        modes.put(ROLLBACK_WINDOW, Set.of(owned));
        modes.put(LEGACY_READ_ONLY, Set.of(owned));
        modes.put(RETIRED, Set.of(owned));

        // Rollback is the one state whose purpose is to move the modes, and it
        // cannot move both axes at once. Pinning it to a single pair would make the
        // operation unrepresentable while it is half done, which in practice means
        // it gets done with an UPDATE statement instead.
        modes.put(ROLLING_BACK, Set.of(owned, canarying, following));

        // A suspended scope keeps whatever routing it had. Forcing a mode here
        // would mean that pausing a target-owned scope quietly handed the
        // capability back to legacy, which is the single failure this module
        // exists to make impossible. The state machine cannot narrow this set,
        // because the honest answer is recorded on the scope, not derivable
        // from the state.
        Set<OwnershipModes> anyRunning = Set.of(untouched, following, comparing, canarying, owned);
        modes.put(PAUSED, anyRunning);
        modes.put(BLOCKED_RECONCILIATION, anyRunning);

        return Map.copyOf(modes);
    }
}
