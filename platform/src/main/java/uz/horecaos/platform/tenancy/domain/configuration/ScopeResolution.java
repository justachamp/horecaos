package uz.horecaos.platform.tenancy.domain.configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Level;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Outcome;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Source;
import uz.horecaos.platform.tenancy.api.Resolved;

/**
 * The single definition of configuration precedence in the platform (ADR 0030).
 *
 * <p>Deliberately a pure function of the key, the requested scope, and the rows
 * already fetched. Keeping I/O out means precedence can be tested exhaustively
 * without a database, which matters because this rule is consumed by every
 * capability that has scoped behavior.
 */
public final class ScopeResolution {

    private ScopeResolution() {}

    /**
     * Resolves most-specific-first, stopping at the first level with an
     * explicit value.
     *
     * @param key    the typed key being resolved
     * @param scope  the scope the caller is acting at
     * @param values the stored rows found for that scope chain, keyed by level
     */
    public static <T> Resolved<T> resolve(
            ConfigurationKey<T> key, ResourceScope scope, Map<ScopeType, ScopedConfigurationRow> values) {

        List<Level> inspected = new ArrayList<>(4);

        for (ResourceScope level : scope.chain()) {
            ScopeType scopeType = level.type();
            ScopedConfigurationRow stored = values.get(scopeType);

            if (stored == null) {
                inspected.add(new Level(scopeType, Outcome.NOT_SET));
                continue;
            }
            if (stored.explicitNull()) {
                if (key.explicitNullTerminates()) {
                    inspected.add(new Level(scopeType, Outcome.EXPLICIT_NULL_TERMINATED));
                    return new Resolved<>(
                            null, new ResolutionTrace(key.code(), Source.SCOPED_VALUE, scopeType, inspected));
                }
                inspected.add(new Level(scopeType, Outcome.EXPLICIT_NULL_CONTINUED));
                continue;
            }

            inspected.add(new Level(scopeType, Outcome.VALUE));
            return new Resolved<>(
                    key.valueType().cast(stored.value()),
                    new ResolutionTrace(key.code(), Source.SCOPED_VALUE, scopeType, inspected));
        }

        return new Resolved<>(
                key.defaultValue(), new ResolutionTrace(key.code(), Source.CODE_DEFAULT, null, inspected));
    }
}
