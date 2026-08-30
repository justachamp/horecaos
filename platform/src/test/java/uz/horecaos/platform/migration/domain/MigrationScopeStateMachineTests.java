package uz.horecaos.platform.migration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scope state machine as the pure function it is (ADR 0024).
 *
 * <p>Everything here is asserted over {@code ScopeState.values()} rather than
 * over a list written out by hand. A migration state added later — a second
 * terminal state, a state with no way out, a state whose modes nobody decided —
 * is a scope that needs database surgery to finish its migration, and the point
 * of quantifying over the enum is that such a state fails here rather than in a
 * cutover window.
 */
class MigrationScopeStateMachineTests {

    /**
     * The five coherent mode pairs, spelled out independently of {@link
     * ScopeState} so the two cannot agree with each other by construction.
     */
    private static final OwnershipModes UNTOUCHED = new OwnershipModes(WriteMode.LEGACY_ONLY, ReadMode.LEGACY);

    private static final OwnershipModes FOLLOWING =
            new OwnershipModes(WriteMode.LEGACY_WITH_TARGET_SHADOW, ReadMode.LEGACY);
    private static final OwnershipModes COMPARING =
            new OwnershipModes(WriteMode.LEGACY_WITH_TARGET_SHADOW, ReadMode.SHADOW_COMPARE);
    private static final OwnershipModes CANARYING =
            new OwnershipModes(WriteMode.LEGACY_WITH_TARGET_SHADOW, ReadMode.CANARY_TARGET);
    private static final OwnershipModes OWNED = new OwnershipModes(WriteMode.TARGET_ONLY, ReadMode.TARGET);

    // ------------------------------------------------------- the terminal set

    /**
     * Guarantee 10. Quantified over the enum, so a state added later that forgets
     * to declare itself non-terminal fails this test instead of stranding a scope.
     */
    @Test
    @DisplayName("RETIRED is terminal and nothing else is")
    void retiredIsTheOnlyTerminalState() {
        Set<ScopeState> terminal = EnumSet.noneOf(ScopeState.class);
        for (ScopeState state : ScopeState.values()) {
            if (state.terminal()) {
                terminal.add(state);
            }
        }

        assertThat(terminal)
                .as("a second terminal state is a second way for a migration to end, "
                        + "and only one of them has signoffs behind it")
                .containsExactly(ScopeState.RETIRED);
        assertThat(ScopeStateMachine.nextFrom(ScopeState.RETIRED))
                .as("reviving a retired scope is a new program, not a transition")
                .isEmpty();
    }

    /**
     * The corollary, and the one that catches a state added without an edge: a
     * scope that could be stranded would need an UPDATE statement to finish its
     * migration, which is the cutover mechanism ADR 0024 refuses.
     */
    @Test
    @DisplayName("every non-terminal state has a way out that is not just being suspended")
    void noStateStrandsAScope() {
        List<ScopeState> stranded = new ArrayList<>();
        for (ScopeState state : ScopeState.values()) {
            if (state.terminal()) {
                continue;
            }
            if (state.holding()) {
                // A holding state leaves through resume with a state the scope
                // recorded, which is why it has no edges of its own.
                assertThat(ScopeStateMachine.nextFrom(state))
                        .as("%s must not be resumable to a state of the table's choosing", state)
                        .isEmpty();
                assertThat(ScopeStateMachine.permitsResume(state, ScopeState.CATCHING_UP))
                        .as("%s leaves through resume", state)
                        .isTrue();
                continue;
            }
            boolean advances = ScopeStateMachine.nextFrom(state).stream().anyMatch(next -> !next.holding());
            if (!advances) {
                stranded.add(state);
            }
        }

        assertThat(stranded)
                .as("a state whose only moves are into a holding state is a dead end "
                        + "reachable only by database surgery")
                .isEmpty();
    }

    @Test
    @DisplayName("every state names the ownership modes a scope in it may carry")
    void everyStatePermitsAtLeastOneCoherentModePair() {
        for (ScopeState state : ScopeState.values()) {
            assertThat(state.permittedModes())
                    .as(
                            "a scope entering %s with no permitted modes cannot be represented, "
                                    + "and MigrationOwnershipService would answer it as drifted",
                            state)
                    .isNotEmpty();
        }
    }

    // ------------------------------------------------- the holding states (7)

    /**
     * Guarantee 7, the half that lives in the machine. Giving {@code PAUSED}
     * outgoing edges would be the same table with the safety removed.
     */
    @Test
    @DisplayName("a held scope cannot be transitioned anywhere; it is resumed")
    void pausedHasNoOutgoingEdges() {
        assertThat(ScopeStateMachine.nextFrom(ScopeState.PAUSED)).isEmpty();
        assertThat(ScopeStateMachine.nextFrom(ScopeState.BLOCKED_RECONCILIATION))
                .isEmpty();

        // The move an operator would reach for: pause a canary, resume it owned.
        assertThat(ScopeStateMachine.permits(ScopeState.PAUSED, ScopeState.TARGET_OWNED))
                .isFalse();
        assertThat(catchThrowable(() -> ScopeStateMachine.require(ScopeState.PAUSED, ScopeState.TARGET_OWNED)))
                .isInstanceOf(ScopeStateMachine.IllegalTransitionException.class);
    }

    /**
     * Resume checks the shape of the move and nothing else. Which state is
     * correct is a stored fact about one scope, and {@code MigrationScopeService}
     * reads it from the checkpoint the suspension wrote.
     */
    @Test
    @DisplayName("resume returns a held scope to a running state, never to a terminal or held one")
    void resumeOnlyReturnsToARunningState() {
        for (ScopeState to : ScopeState.values()) {
            boolean expected = !to.holding() && !to.terminal();
            assertThat(ScopeStateMachine.permitsResume(ScopeState.PAUSED, to))
                    .as("resuming a paused scope into %s", to)
                    .isEqualTo(expected);
            assertThat(ScopeStateMachine.permitsResume(ScopeState.BLOCKED_RECONCILIATION, to))
                    .as("resuming a blocked scope into %s", to)
                    .isEqualTo(expected);
        }

        // And a scope that is not held is not resumable at all, whatever it is
        // asked to resume into.
        for (ScopeState from : ScopeState.values()) {
            if (from.holding()) {
                continue;
            }
            assertThat(ScopeStateMachine.permitsResume(from, ScopeState.CANARY))
                    .as("%s is not a held state", from)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("suspension is reachable from everywhere a scope is still moving, and nowhere else")
    void suspensionIsAvailableExactlyWhileTheScopeIsMoving() {
        for (ScopeState state : ScopeState.values()) {
            boolean moving = !state.terminal() && !state.holding();
            assertThat(ScopeStateMachine.permits(state, ScopeState.PAUSED))
                    .as("pausing from %s", state)
                    .isEqualTo(moving);
            assertThat(ScopeStateMachine.permits(state, ScopeState.BLOCKED_RECONCILIATION))
                    .as("blocking from %s", state)
                    .isEqualTo(moving);
        }
    }

    // -------------------------------------------------- the ordinary path (3)

    /**
     * A rollback lands where the world it leaves behind actually is: legacy owns
     * the writes again and the target copy is still being fed. Landing in
     * SHADOW_READING or CANARY would re-assert the validation the rollback just
     * disproved.
     */
    @Test
    @DisplayName("a rollback ends in CATCHING_UP and is reachable only from CANARY and TARGET_OWNED")
    void rollbackHasOneEntryPairAndOneLanding() {
        // Suspension is available from here as it is from everywhere still moving,
        // so the landing is the one edge that advances the scope.
        assertThat(ScopeStateMachine.nextFrom(ScopeState.ROLLING_BACK).stream()
                        .filter(next -> !next.holding())
                        .toList())
                .containsExactly(ScopeState.CATCHING_UP);

        Set<ScopeState> entries = EnumSet.noneOf(ScopeState.class);
        for (ScopeState state : ScopeState.values()) {
            if (ScopeStateMachine.permits(state, ScopeState.ROLLING_BACK)) {
                entries.add(state);
            }
        }
        assertThat(entries).containsExactlyInAnyOrder(ScopeState.CANARY, ScopeState.TARGET_OWNED);
    }

    @Test
    @DisplayName("TARGET_OWNED is reachable only from CUTOVER_READY and from a closing rollback window")
    void targetOwnershipHasTwoWaysIn() {
        Set<ScopeState> entries = EnumSet.noneOf(ScopeState.class);
        for (ScopeState state : ScopeState.values()) {
            if (ScopeStateMachine.permits(state, ScopeState.TARGET_OWNED)) {
                entries.add(state);
            }
        }

        // ROLLBACK_WINDOW -> TARGET_OWNED is how a soaking scope reopens its
        // cutover so it can then be reversed; it does not take ownership, which
        // the scope already had.
        assertThat(entries).containsExactlyInAnyOrder(ScopeState.CUTOVER_READY, ScopeState.ROLLBACK_WINDOW);
    }

    // ------------------------------------------------------ the mode pairs (3)

    /**
     * Guarantee 3, at the level where the two write modes are first distinguished:
     * the coherent set contains exactly one pair whose writer is the target, and
     * shadow is not it.
     */
    @Test
    @DisplayName("five of the twelve mode combinations describe a system that works")
    void onlyTheCoherentModePairsCanBeConstructed() {
        Set<OwnershipModes> constructible = new java.util.LinkedHashSet<>();
        for (WriteMode writeMode : WriteMode.values()) {
            for (ReadMode readMode : ReadMode.values()) {
                try {
                    constructible.add(new OwnershipModes(writeMode, readMode));
                } catch (IllegalArgumentException incoherent) {
                    // One of the seven the constructor refuses.
                }
            }
        }

        assertThat(constructible).containsExactlyInAnyOrder(UNTOUCHED, FOLLOWING, COMPARING, CANARYING, OWNED);
        assertThat(constructible.stream()
                        .filter(modes -> modes.writeMode().targetMayWrite())
                        .toList())
                .as("exactly one coherent pair makes the target the writer, and a shadow is not it")
                .containsExactly(OWNED);
    }

    @Test
    @DisplayName("no state permits a mode pair the pair type itself would refuse")
    void everyPermittedPairIsOneOfTheFive() {
        for (ScopeState state : ScopeState.values()) {
            assertThat(state.permittedModes())
                    .as("%s permits only coherent pairs", state)
                    .isSubsetOf(UNTOUCHED, FOLLOWING, COMPARING, CANARYING, OWNED);
        }
    }

    /**
     * A shadow write is a copy being checked against the authority. Any state
     * that treated it as ownership would let the shadow start emitting effects,
     * and the comparison meant to prove the target correct would be comparing the
     * target against itself.
     */
    @Test
    @DisplayName("only the states that have taken ownership permit TARGET_ONLY")
    void targetOnlyIsPermittedOnlyWhereOwnershipHasTransferred() {
        Set<ScopeState> owning = EnumSet.noneOf(ScopeState.class);
        for (ScopeState state : ScopeState.values()) {
            if (state.permittedModes().stream().anyMatch(modes -> modes.writeMode() == WriteMode.TARGET_ONLY)) {
                owning.add(state);
            }
        }

        assertThat(owning)
                .containsExactlyInAnyOrder(
                        ScopeState.TARGET_OWNED,
                        ScopeState.ROLLBACK_WINDOW,
                        ScopeState.LEGACY_READ_ONLY,
                        ScopeState.RETIRED,
                        // A rollback cannot move both axes at once, so it carries the
                        // pair it is leaving as well as the one it is heading for.
                        ScopeState.ROLLING_BACK,
                        // The holding states keep whatever routing the scope had; pausing
                        // a target-owned scope must not hand the capability back.
                        ScopeState.PAUSED,
                        ScopeState.BLOCKED_RECONCILIATION);

        assertThat(ScopeState.CANARY.permittedModes())
                .as("a canary reads a share of traffic from the target; legacy still writes it")
                .containsExactly(CANARYING);
        assertThat(ScopeState.CUTOVER_READY.permittedModes())
                .as("being ready to cut over is a decision about evidence, not a routing change")
                .containsExactly(CANARYING);
    }
}
