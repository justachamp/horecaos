package uz.horecaos.platform.tenancy.api;

import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Resolves scoped configuration values (ADR 0030).
 *
 * <p>This is the only mechanism for platform, tenant, brand, and location
 * precedence. A module must not implement its own scoped-configuration table:
 * nine implementations of one rule will disagree about precedence, about what
 * an explicit null means, and about caching, and those disagreements surface
 * only when a value changes and history becomes unexplainable.
 */
public interface ConfigurationResolver {

    <T> Resolved<T> resolve(ConfigurationKey<T> key, ResourceScope scope);

    /**
     * Convenience accessor for {@link #resolve}'s value, which is genuinely
     * absent under the same conditions {@link Resolved#value()} documents — an
     * explicit null that terminates resolution, or a key with no code default
     * left unset at every scope. Callers already code defensively against this
     * (see {@code EnforcementCeiling} and telemetry's {@code CollectionGate}
     * resolution); this signature now says so.
     */
    default <T> @Nullable T value(ConfigurationKey<T> key, ResourceScope scope) {
        return resolve(key, scope).value();
    }

    ResolutionTrace explain(ConfigurationKey<?> key, ResourceScope scope);
}
