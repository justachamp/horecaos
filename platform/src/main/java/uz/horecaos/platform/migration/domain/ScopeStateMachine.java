package uz.horecaos.platform.migration.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The authoritative scope state machine (ADR 0024).
 *
 * <p>Code, not configuration, for the same reason {@code OrderStateMachine} is:
 * a migration whose lawful moves can be edited is a migration where the answer
 * to "how did this scope get target-owned without reconciliation" is "someone
 * changed a row". There is no table to override and no constructor.
 *
 * <p>The ADR draws a happy path and two escapes. Three things it states in prose
 * rather than in the diagram are decided here.
 *
 * <p>The first is the return from a holding state. {@link ScopeState#PAUSED} and
 * {@link ScopeState#BLOCKED_RECONCILIATION} are reachable from every state that
 * is still moving, and leaving them means going back to where the scope came
 * from. That is a stored fact about one scope, not a property of the states, so
 * this table gives the holding states no outgoing edges at all and {@link
 * #permitsResume(ScopeState, ScopeState)} takes the return state from the
 * caller. Allowing {@code PAUSED -> anything} would have been the same table
 * with the safety removed: an operator could pause a canary and resume it
 * target-owned.
 *
 * <p>The second is where a rollback lands. The ADR describes entering {@link
 * ScopeState#ROLLING_BACK} and never says what a finished rollback looks like.
 * It ends at {@link ScopeState#CATCHING_UP}, because that is the only state
 * whose modes describe the world a rollback leaves behind: legacy owns the
 * writes again, the target copy still exists and is still being fed, and nothing
 * reads it. Landing in {@link ScopeState#SHADOW_READING} or {@link
 * ScopeState#CANARY} would re-assert a validation the rollback just disproved,
 * and landing further back would discard a target copy that rollback explicitly
 * does not destroy. A scope whose target data is judged unsalvageable is repaired
 * by a {@link RunType#REMEDIATION} run, which is a run and not a backward edge.
 *
 * <p>The third is that two states need a way to change their minds. {@link
 * ScopeState#CUTOVER_READY} can return to {@link ScopeState#CANARY}: its
 * approval is bound to evidence with a watermark, and the honest way to withdraw
 * it is to go back to the state that produces evidence rather than to sit ready
 * on a stale signature. {@link ScopeState#ROLLBACK_WINDOW} can return to {@link
 * ScopeState#TARGET_OWNED}, which is how a scope still inside its window reaches
 * rollback at all: the ADR admits {@link ScopeState#ROLLING_BACK} only from
 * {@link ScopeState#CANARY} and {@link ScopeState#TARGET_OWNED}, and a soaking
 * scope that hits a rollback criterion must reopen the cutover it is soaking
 * before it can reverse it.
 */
public final class ScopeStateMachine {

    private static final Set<ScopeState> HOLDING = EnumSet.of(ScopeState.PAUSED, ScopeState.BLOCKED_RECONCILIATION);

    private static final Map<ScopeState, Set<ScopeState>> ALLOWED = allowed();

    private ScopeStateMachine() {}

    private static Map<ScopeState, Set<ScopeState>> allowed() {
        Map<ScopeState, Set<ScopeState>> transitions = new EnumMap<>(ScopeState.class);

        transitions.put(ScopeState.DISCOVERY, EnumSet.of(ScopeState.MAPPING_APPROVED));
        transitions.put(ScopeState.MAPPING_APPROVED, EnumSet.of(ScopeState.BACKFILLING));
        transitions.put(ScopeState.BACKFILLING, EnumSet.of(ScopeState.CATCHING_UP));
        transitions.put(ScopeState.CATCHING_UP, EnumSet.of(ScopeState.SHADOW_READING));
        transitions.put(ScopeState.SHADOW_READING, EnumSet.of(ScopeState.CANARY));

        transitions.put(ScopeState.CANARY, EnumSet.of(ScopeState.CUTOVER_READY, ScopeState.ROLLING_BACK));

        // CUTOVER_READY -> TARGET_OWNED is the transfer of ownership itself. That
        // the reconciliation is signed and the target owner is a real recorded
        // owner is a runtime question and its gate lives in the application, the
        // way an order's policy-gated cancel does; the ADR's rule that no admin UI
        // may skip either is enforced there and cannot be enforced here. The edge
        // existing at all is a modelling question and belongs in this table.
        transitions.put(ScopeState.CUTOVER_READY, EnumSet.of(ScopeState.TARGET_OWNED, ScopeState.CANARY));

        transitions.put(ScopeState.TARGET_OWNED, EnumSet.of(ScopeState.ROLLBACK_WINDOW, ScopeState.ROLLING_BACK));

        transitions.put(ScopeState.ROLLBACK_WINDOW, EnumSet.of(ScopeState.LEGACY_READ_ONLY, ScopeState.TARGET_OWNED));

        transitions.put(ScopeState.LEGACY_READ_ONLY, EnumSet.of(ScopeState.RETIRED));
        transitions.put(ScopeState.ROLLING_BACK, EnumSet.of(ScopeState.CATCHING_UP));

        transitions.put(ScopeState.RETIRED, EnumSet.noneOf(ScopeState.class));
        for (ScopeState holding : HOLDING) {
            transitions.put(holding, EnumSet.noneOf(ScopeState.class));
        }

        // Suspension is available from everywhere the scope is still moving, and
        // from nowhere else. Suspending an already suspended scope would overwrite
        // the state it has to return to, and there is nothing to suspend about a
        // retired one.
        for (ScopeState state : ScopeState.values()) {
            if (!state.terminal() && !state.holding()) {
                // Every non-terminal, non-holding state was given an entry above, so
                // this lookup cannot miss; requireNonNull says so rather than
                // silently tolerating a state the table forgot to cover.
                Objects.requireNonNull(transitions.get(state)).addAll(HOLDING);
            }
        }

        Map<ScopeState, Set<ScopeState>> frozen = new EnumMap<>(ScopeState.class);
        transitions.forEach((from, to) -> frozen.put(from, Set.copyOf(to)));
        return Map.copyOf(frozen);
    }

    /**
     * The states a scope may move to next.
     *
     * <p>Empty means one of two different things, and {@link ScopeState#holding()}
     * separates them: a terminal scope has finished, and a held scope leaves
     * through {@link #permitsResume(ScopeState, ScopeState)} with a return state
     * this class does not know.
     */
    public static Set<ScopeState> nextFrom(ScopeState from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    public static boolean permits(ScopeState from, ScopeState to) {
        return nextFrom(from).contains(to);
    }

    /**
     * Fails rather than returning false, for the call sites where an illegal
     * transition is a programming error rather than an operator's request.
     */
    public static void require(ScopeState from, ScopeState to) {
        if (!permits(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }

    /**
     * Whether a held scope may resume into this state.
     *
     * <p>This checks the shape of the move and nothing else: that the scope is
     * actually held, and that it is returning to a state a scope can be suspended
     * from. Which state is correct is recorded on the scope when it was
     * suspended, and the application supplies it. A machine that guessed would be
     * guessing at whether a capability is legacy-owned or target-owned.
     */
    public static boolean permitsResume(ScopeState from, ScopeState to) {
        return from.holding() && !to.holding() && !to.terminal();
    }

    /** The failing counterpart of {@link #permitsResume(ScopeState, ScopeState)}. */
    public static void requireResume(ScopeState from, ScopeState to) {
        if (!permitsResume(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }

    /** Thrown when a caller asks for a move the canonical machine does not have. */
    public static final class IllegalTransitionException extends IllegalStateException {

        private final ScopeState from;
        private final ScopeState to;

        public IllegalTransitionException(ScopeState from, ScopeState to) {
            super("A migration scope cannot move from %s to %s (ADR 0024)".formatted(from, to));
            this.from = from;
            this.to = to;
        }

        public ScopeState from() {
            return from;
        }

        public ScopeState to() {
            return to;
        }
    }
}
