package uz.horecaos.platform.migration.api;

import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.migration.domain.ScopeState;
import uz.horecaos.platform.migration.domain.WriteMode;

/**
 * The target is not the writer for this capability at this scope, so the write
 * did not happen (ADR 0024).
 *
 * <p>A distinct type rather than a generic failure, because "you are not the
 * writer yet" is not a bug, not a validation error, and not an outage, and the
 * three want completely different responses. A caller may legitimately catch
 * this one and route the work to the legacy owner; catching it by accident along
 * with a real fault would route a genuine failure into a migration path.
 *
 * <p>It carries the resolved scope, its state, and its write mode, because the
 * first question an operator asks about a fenced write is which scope fenced it
 * and what state that scope is in. A log line saying only that writes were
 * fenced sends them to the scope table to guess.
 */
public class TargetWritesFencedException extends RuntimeException {

    private final MigrationCapability capability;
    private final UUID scopeId;
    private final ScopeState state;
    private final WriteMode writeMode;

    public TargetWritesFencedException(
            MigrationCapability capability, UUID scopeId, ScopeState state, WriteMode writeMode) {
        super(describe(capability, scopeId, state, writeMode));
        this.capability = Objects.requireNonNull(capability, "A capability is required");
        this.scopeId = scopeId;
        this.state = Objects.requireNonNull(state, "A scope state is required");
        this.writeMode = Objects.requireNonNull(writeMode, "A write mode is required");
    }

    /** The gate's own answer, turned into the failure a caller sees. */
    public static TargetWritesFencedException fencedBy(CapabilityOwnership ownership) {
        return new TargetWritesFencedException(
                ownership.capability(), ownership.scopeId(), ownership.state(), ownership.writeMode());
    }

    private static String describe(
            MigrationCapability capability, UUID scopeId, ScopeState state, WriteMode writeMode) {
        String scope = scopeId == null ? "no migration scope" : "scope " + scopeId;
        return "The target may not write %s: %s is in %s with write mode %s (ADR 0024)"
                .formatted(capability, scope, state, writeMode);
    }

    public MigrationCapability capability() {
        return capability;
    }

    /** The scope that fenced the write, or null when none covered the request. */
    public UUID scopeId() {
        return scopeId;
    }

    public ScopeState state() {
        return state;
    }

    public WriteMode writeMode() {
        return writeMode;
    }
}
