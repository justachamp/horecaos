package uz.horecaos.platform.migration.api;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.horecaos.platform.migration.domain.OwnershipModes;
import uz.horecaos.platform.migration.domain.ReadMode;
import uz.horecaos.platform.migration.domain.ScopeState;
import uz.horecaos.platform.migration.domain.WriteMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ownership answer itself: the record every other module's write is gated on
 * (ADR 0024).
 *
 * <p>Quantified over {@code ScopeState.values()} crossed with the modes each
 * state permits, because the failure this is kept against is a state added later
 * whose suspension nobody remembered to fence. There is one predicate — the
 * target may write only when it is the writer and only while nobody has stopped
 * the scope — and it is asserted against every combination the domain admits
 * rather than against the handful someone thought of.
 */
class MigrationCapabilityOwnershipTests {

    private static final UUID SCOPE = UUID.randomUUID();

    /**
     * The states that suspend a scope without rewriting its stored modes. Spelled
     * out here rather than read from the record, so the two have to agree.
     */
    private static final Set<ScopeState> SUSPENDED = EnumSet.of(
            ScopeState.PAUSED,
            ScopeState.BLOCKED_RECONCILIATION,
            ScopeState.ROLLING_BACK);

    /**
     * Guarantee 2, in record form: the answer is "the target may write" only for
     * the one write mode that means it, and only while the scope is running.
     */
    @Test
    @DisplayName("the target may write only where it owns the writes and nothing has stopped the scope")
    void targetMayWriteIsTrueForExactlyOneModeInExactlyTheRunningStates() {
        for (ScopeState state : ScopeState.values()) {
            for (OwnershipModes modes : state.permittedModes()) {
                CapabilityOwnership ownership = new CapabilityOwnership(SCOPE,
                        MigrationCapability.ORDERS, state, modes.writeMode(), modes.readMode());

                boolean expected = modes.writeMode() == WriteMode.TARGET_ONLY
                        && !SUSPENDED.contains(state);

                assertThat(ownership.targetMayWrite())
                        .as("%s with write mode %s", state, modes.writeMode())
                        .isEqualTo(expected);
            }
        }
    }

    /**
     * Guarantee 3. A shadow write is a copy being checked against the authority,
     * not the authority. Treating the two as the same thing is the two-writer
     * failure ADR 0024 exists to prevent: the shadow starts emitting effects, and
     * the comparison meant to prove the target correct compares it with itself.
     */
    @Test
    @DisplayName("LEGACY_WITH_TARGET_SHADOW never satisfies targetMayWrite, in any state")
    void aShadowWriteIsNotOwnership() {
        for (ScopeState state : ScopeState.values()) {
            for (OwnershipModes modes : state.permittedModes()) {
                if (modes.writeMode() != WriteMode.LEGACY_WITH_TARGET_SHADOW) {
                    continue;
                }
                CapabilityOwnership shadow = new CapabilityOwnership(SCOPE,
                        MigrationCapability.ORDERS, state, modes.writeMode(), modes.readMode());

                assertThat(shadow.targetMayWrite())
                        .as("a shadow in %s reading %s is still a copy", state, modes.readMode())
                        .isFalse();
                assertThat(shadow.legacyMayWrite())
                        .as("and legacy is still the authority behind it")
                        .isTrue();
            }
        }

        // The specific pair a canary runs on, named rather than only quantified
        // over, because it is the one that looks most like ownership from the
        // outside: a share of reads is already being served by the target.
        CapabilityOwnership canary = new CapabilityOwnership(SCOPE, MigrationCapability.ORDERS,
                ScopeState.CANARY, WriteMode.LEGACY_WITH_TARGET_SHADOW, ReadMode.CANARY_TARGET);
        assertThat(canary.targetMayWrite()).isFalse();
    }

    @Test
    @DisplayName("a capability no scope covers is legacy-owned, not unowned")
    void anUnmanagedCapabilityFailsClosed() {
        CapabilityOwnership unmanaged = CapabilityOwnership.unmanaged(MigrationCapability.ORDERS);

        assertThat(unmanaged.targetMayWrite()).isFalse();
        assertThat(unmanaged.legacyMayWrite()).isTrue();
        assertThat(unmanaged.state()).isEqualTo(ScopeState.DISCOVERY);
        assertThat(unmanaged.scopeId())
                .as("a null scope id is what tells an operator the difference between a scope "
                        + "that has not started and no scope at all")
                .isNull();
    }

    /**
     * Both predicates false is the safe direction to be wrong in: a scope
     * suspended after the target took ownership has no writer at all until an
     * operator resolves it.
     */
    @Test
    @DisplayName("the two writers are never both permitted")
    void thereIsNeverMoreThanOneWriter() {
        for (ScopeState state : ScopeState.values()) {
            for (OwnershipModes modes : state.permittedModes()) {
                CapabilityOwnership ownership = new CapabilityOwnership(SCOPE,
                        MigrationCapability.ORDERS, state, modes.writeMode(), modes.readMode());

                assertThat(ownership.targetMayWrite() && ownership.legacyMayWrite())
                        .as("%s with %s would be two authorities over one fact", state, modes)
                        .isFalse();
            }
        }

        CapabilityOwnership pausedAfterCutover = new CapabilityOwnership(SCOPE,
                MigrationCapability.ORDERS, ScopeState.PAUSED, WriteMode.TARGET_ONLY,
                ReadMode.TARGET);
        assertThat(pausedAfterCutover.targetMayWrite())
                .as("a scope is paused precisely when somebody has decided it should not write")
                .isFalse();
        assertThat(pausedAfterCutover.legacyMayWrite())
                .as("and legacy was fenced at cutover, so nobody writes until it is resumed")
                .isFalse();
    }

    /**
     * The exception the gate throws carries the row an operator has to look at.
     * A fenced write that named no scope would send them to the whole table.
     */
    @Test
    @DisplayName("the fencing exception names the scope, the state and the write mode")
    void theRefusalSaysWhichRowNeedsLookingAt() {
        CapabilityOwnership blocked = new CapabilityOwnership(SCOPE, MigrationCapability.ORDERS,
                ScopeState.BLOCKED_RECONCILIATION, WriteMode.TARGET_ONLY, ReadMode.TARGET);

        TargetWritesFencedException fenced = TargetWritesFencedException.fencedBy(blocked);

        assertThat(fenced.scopeId()).isEqualTo(SCOPE);
        assertThat(fenced.state()).isEqualTo(ScopeState.BLOCKED_RECONCILIATION);
        assertThat(fenced.writeMode()).isEqualTo(WriteMode.TARGET_ONLY);
        assertThat(fenced.getMessage())
                .contains("ORDERS", SCOPE.toString(), "BLOCKED_RECONCILIATION", "TARGET_ONLY");
    }
}
