package uz.horecaos.platform.tenancy.domain.configuration;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ResolutionTrace.Provenance;

/**
 * One stored configuration row as the resolver sees it (ADR 0030).
 *
 * <p>{@code explicitNull} is deliberately distinct from "no row". No row means
 * "not set here, keep looking"; an explicit null means "deliberately unset
 * here", which is visible in the resolution trace and can terminate resolution
 * for keys that declare it.
 *
 * <p>Named to avoid colliding with {@link java.lang.ScopedValue}: the JDK type
 * is used elsewhere in this codebase for thread-confined context, and having
 * two unrelated {@code ScopedValue}s in scope at once is exactly the confusion
 * that name would invite.
 */
public record ScopedConfigurationRow(
        ScopeType scopeType,
        @Nullable Object value,
        boolean explicitNull,
        @Nullable Provenance provenance) {

    public ScopedConfigurationRow {
        Objects.requireNonNull(scopeType, "Scope type is required");
        if (explicitNull && value != null) {
            throw new IllegalArgumentException("An explicit null cannot carry a value");
        }
    }

    /** A row with no provenance — what a resolution unit test builds. */
    public ScopedConfigurationRow(ScopeType scopeType, @Nullable Object value, boolean explicitNull) {
        this(scopeType, value, explicitNull, null);
    }

    /** The same row, saying who last changed it and when; it rides into the resolution trace. */
    public ScopedConfigurationRow withProvenance(@Nullable Provenance provenance) {
        return new ScopedConfigurationRow(scopeType, value, explicitNull, provenance);
    }

    public static ScopedConfigurationRow of(ScopeType scopeType, Object value) {
        return new ScopedConfigurationRow(scopeType, Objects.requireNonNull(value, "Value is required"), false);
    }

    public static ScopedConfigurationRow explicitNull(ScopeType scopeType) {
        return new ScopedConfigurationRow(scopeType, null, true);
    }
}
