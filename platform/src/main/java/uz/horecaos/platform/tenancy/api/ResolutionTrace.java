package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
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

    /**
     * One rung of the ladder: what was found at a level and, when something was stored
     * there, the facts about that row.
     *
     * @param provenance who last changed what is stored at this level and when; absent
     *                   for {@link Outcome#NOT_SET} (there is no row to describe) and for
     *                   a trace built from rows that carry none
     */
    public record Level(
            ScopeType scopeType, Outcome outcome, @Nullable Provenance provenance) {

        public Level(ScopeType scopeType, Outcome outcome) {
            this(scopeType, outcome, null);
        }
    }

    /**
     * Who changed a stored level and when, so the console's «why is this branch different»
     * popover can answer for every rung and not only the winning one (settings.md §1.2).
     *
     * <p>One shape for the two things a trace is asked about. For a configuration value
     * (a {@code tenant.configuration_values} row) it is the row's {@code version}, {@code
     * set_by} and {@code updated_at}. For a versioned policy document (a {@code
     * tenant.policies} row) it is the policy's {@code version}, {@code approved_by} and
     * {@code valid_from}: the principal who approved that version, and when it took effect.
     *
     * <p><strong>Ids, never names (ADR 0029).</strong> {@code principal} is the identity
     * provider's subject exactly as stored. A trace is a domain object that travels through
     * caches and logs; the person's name is a PERSONAL field and is looked up by the HTTP
     * adapter that answers a signed-in tenant admin, through {@code StaffDirectory}, at the
     * moment it answers and nowhere else. Nothing here holds a free-text reason either, for
     * the same reason: an operator can type anything into one.
     *
     * @param version   the row's version (a value's optimistic-concurrency counter, a
     *                  policy's published version number)
     * @param principal the subject who set the value or approved the policy version, absent
     *                  when the row records none
     * @param since     when this row took effect: a value's last update, a policy's {@code
     *                  valid_from}
     */
    public record Provenance(
            long version,
            @Nullable String principal,
            @Nullable Instant since) {}

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
