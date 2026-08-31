package uz.horecaos.platform.tenancy.api;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * Why a configuration value resolved the way it did (ADR 0030).
 *
 * @param keyCode          the key that was resolved
 * @param source           where the winning value came from
 * @param winningScope     the scope level that supplied it, absent for a code default
 * @param inspectedLevels  each level considered, most specific first, and what was found
 */
public record ResolutionTrace(
        String keyCode, Source source, @Nullable ScopeType winningScope, List<Level> inspectedLevels) {

    public enum Source {
        SCOPED_VALUE,
        CODE_DEFAULT
    }

    public enum Outcome {
        /** No row at this level; resolution continued. */
        NOT_SET,
        /** A value was found and used. */
        VALUE,
        /** An explicit null: the value is deliberately unset here. */
        EXPLICIT_NULL_CONTINUED,
        /** An explicit null on a key declaring that null terminates resolution. */
        EXPLICIT_NULL_TERMINATED
    }

    public record Level(ScopeType scopeType, Outcome outcome) {}

    public ResolutionTrace {
        Objects.requireNonNull(keyCode, "Key code is required");
        Objects.requireNonNull(source, "Source is required");
        inspectedLevels = List.copyOf(Objects.requireNonNull(inspectedLevels, "Inspected levels are required"));
    }

    public String describe() {
        StringBuilder text = new StringBuilder(keyCode).append(" -> ").append(source);
        if (winningScope != null) {
            text.append(" at ").append(winningScope);
        }
        text.append(" [");
        for (int index = 0; index < inspectedLevels.size(); index++) {
            Level level = inspectedLevels.get(index);
            if (index > 0) {
                text.append(", ");
            }
            text.append(level.scopeType()).append('=').append(level.outcome());
        }
        return text.append(']').toString();
    }
}
