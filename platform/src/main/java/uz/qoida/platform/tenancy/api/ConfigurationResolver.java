package uz.qoida.platform.tenancy.api;

import uz.qoida.platform.iam.api.ResourceScope;

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

    default <T> T value(ConfigurationKey<T> key, ResourceScope scope) {
        return resolve(key, scope).value();
    }

    ResolutionTrace explain(ConfigurationKey<?> key, ResourceScope scope);
}
