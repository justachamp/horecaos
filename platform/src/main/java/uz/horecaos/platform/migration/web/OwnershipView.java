package uz.horecaos.platform.migration.web;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.migration.api.CapabilityOwnership;
import uz.horecaos.platform.migration.api.MigrationCapability;
import uz.horecaos.platform.migration.domain.ReadMode;
import uz.horecaos.platform.migration.domain.ScopeState;
import uz.horecaos.platform.migration.domain.WriteMode;

/**
 * The gate's answer, rendered for an operator.
 *
 * <p>{@code targetMayWrite} and {@code legacyMayWrite} are published rather than
 * left for the client to derive from the write mode, because they are not the
 * same thing as the mode and the difference is where the mistakes are. A paused
 * {@code TARGET_ONLY} scope has a write mode saying the target owns the
 * capability and an answer saying nobody may write it, and a console that
 * recomputed the predicate from the mode would show the opposite of what the
 * platform is actually doing.
 *
 * @param scopeId null when no scope covers the request, which is what
 *                distinguishes a scope that exists and has not started from no
 *                scope at all
 */
public record OwnershipView(
        @Nullable UUID scopeId,
        MigrationCapability capability,
        ScopeState state,
        WriteMode writeMode,
        ReadMode readMode,
        boolean targetMayWrite,
        boolean legacyMayWrite) {

    static OwnershipView of(CapabilityOwnership ownership) {
        return new OwnershipView(
                ownership.scopeId(),
                ownership.capability(),
                ownership.state(),
                ownership.writeMode(),
                ownership.readMode(),
                ownership.targetMayWrite(),
                ownership.legacyMayWrite());
    }
}
