package uz.horecaos.platform.migration.api;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import uz.horecaos.platform.migration.api.MigrationCapability;
import uz.horecaos.platform.migration.domain.ReadMode;
import uz.horecaos.platform.migration.domain.ScopeState;
import uz.horecaos.platform.migration.domain.WriteMode;

/**
 * Who owns writes for one capability at one resolved scope, right now (ADR
 * 0024).
 *
 * <p>This is the record form of the guarantee the whole migration exists to
 * keep: every capability has exactly one writer at every moment, provable from
 * the scope table. The state travels with the modes because a scope keeps its
 * stored modes while it is paused or blocked, so the modes alone cannot answer
 * the question a caller is actually asking.
 *
 * @param scopeId    the scope row this answer came from, or null when no scope
 *                   covers the request; see {@link #unmanaged}
 * @param capability the capability whose ownership this describes
 * @param state      the resolved scope's state, which can fence writes that its
 *                   stored write mode would otherwise permit
 * @param writeMode  which system may create facts for this capability
 * @param readMode   where reads are served from, and whether the target is being
 *                   compared or trusted
 */
public record CapabilityOwnership(
        UUID scopeId,
        MigrationCapability capability,
        ScopeState state,
        WriteMode writeMode,
        ReadMode readMode) {

    /**
     * The states that suspend a scope without rewriting the modes stored on it.
     *
     * <p>A pause and a reconciliation block are deliberately reversible: the
     * operator resumes and the scope carries on from where it stopped, which
     * only works if the stored modes survive. Consulting the write mode alone
     * would therefore let a paused {@code TARGET_ONLY} scope keep writing, and a
     * scope is paused precisely when somebody has decided it should not.
     *
     * <p>{@code ROLLING_BACK} is here for the same reason from the other
     * direction. ADR 0024 restores legacy routing only after one writer is
     * proven, so during the rollback the honest answer is that neither side may
     * write.
     */
    private static final Set<ScopeState> WRITES_SUSPENDED = EnumSet.of(
            ScopeState.PAUSED,
            ScopeState.BLOCKED_RECONCILIATION,
            ScopeState.ROLLING_BACK);

    public CapabilityOwnership {
        Objects.requireNonNull(capability, "A capability is required");
        Objects.requireNonNull(state, "A scope state is required");
        Objects.requireNonNull(writeMode, "A write mode is required");
        Objects.requireNonNull(readMode, "A read mode is required");
    }

    /**
     * The answer for a capability no scope covers: legacy owns it and the target
     * may not write.
     *
     * <p>A missing scope row means the migration has never reached this
     * capability for this tenant, which is the same ownership position as
     * {@code DISCOVERY} and is reported as that state. The null {@code scopeId}
     * is what tells an operator the difference between a scope that exists and
     * has not started and no scope at all.
     */
    public static CapabilityOwnership unmanaged(MigrationCapability capability) {
        return new CapabilityOwnership(null, capability, ScopeState.DISCOVERY,
                WriteMode.LEGACY_ONLY, ReadMode.LEGACY);
    }

    /**
     * Whether a target write here creates the authoritative fact.
     *
     * <p>True only for {@code TARGET_ONLY}, and only while the scope is not
     * suspended. {@code LEGACY_WITH_TARGET_SHADOW} writes the target too, but a
     * shadow is a copy being checked against the authority, not the authority
     * itself. Treating the two as the same thing is exactly the two-writer
     * failure ADR 0024 forbids: the shadow would start emitting effects, and the
     * comparison that was supposed to prove the target correct would instead be
     * comparing the target against itself.
     */
    public boolean targetMayWrite() {
        return writeMode == WriteMode.TARGET_ONLY && !WRITES_SUSPENDED.contains(state);
    }

    /**
     * Whether legacy still owns writes for this capability.
     *
     * <p>The complement of {@code TARGET_ONLY}, so the two predicates can never
     * both be true; a scope suspended after the target took ownership makes both
     * false, which is the safe direction to be wrong in and the state an
     * operator has to resolve before the capability writes again. Nothing in
     * this platform can fence a legacy writer, so this is
     * read by reporting, by import ports deciding whether a catch-up run still
     * has a source, and by anything that needs to say which system a fact should
     * have come from.
     */
    public boolean legacyMayWrite() {
        return writeMode != WriteMode.TARGET_ONLY;
    }
}
