package uz.horecaos.platform.tenancy.domain.configuration;

import java.util.Objects;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * One stored configuration row as the resolver sees it (ADR 0030).
 *
 * <p>{@code explicitNull} is deliberately distinct from "no row". No row means
 * "not set here, keep looking"; an explicit null means "deliberately unset
 * here", which is visible in the resolution trace and can terminate resolution
 * for keys that declare it.
 */
public record ScopedValue(ScopeType scopeType, Object value, boolean explicitNull) {

    public ScopedValue {
        Objects.requireNonNull(scopeType, "Scope type is required");
        if (explicitNull && value != null) {
            throw new IllegalArgumentException("An explicit null cannot carry a value");
        }
    }

    public static ScopedValue of(ScopeType scopeType, Object value) {
        return new ScopedValue(scopeType, Objects.requireNonNull(value, "Value is required"), false);
    }

    public static ScopedValue explicitNull(ScopeType scopeType) {
        return new ScopedValue(scopeType, null, true);
    }
}
