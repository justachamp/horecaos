package uz.horecaos.platform.tenancy.api;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * A typed, code-owned configuration key (ADR 0030).
 *
 * <p>Keys are declared in code rather than in the database so an unknown key
 * fails at startup instead of silently resolving to a default at read time.
 *
 * @param <T> the resolved value type
 */
public record ConfigurationKey<T>(
        String code,
        Class<T> valueType,
        T defaultValue,
        Set<ScopeType> settableScopes,
        String owningModule,
        boolean tenantVisible,
        boolean explicitNullTerminates,
        String description) {

    public ConfigurationKey {
        Objects.requireNonNull(code, "Key code is required");
        Objects.requireNonNull(valueType, "Value type is required");
        Objects.requireNonNull(owningModule, "Owning module is required");
        settableScopes = Set.copyOf(Objects.requireNonNull(settableScopes, "Settable scopes are required"));
        if (settableScopes.isEmpty()) {
            throw new IllegalArgumentException("A key must be settable at at least one scope: " + code);
        }
        if (!code.matches("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+$")) {
            throw new IllegalArgumentException("Key code must be dotted lower case: " + code);
        }
        if (!SUPPORTED_TYPES.contains(valueType)) {
            throw new IllegalArgumentException(
                    "Unsupported configuration value type %s for %s".formatted(valueType.getName(), code));
        }
    }

    private static final Set<Class<?>> SUPPORTED_TYPES =
            Set.of(Boolean.class, Integer.class, Long.class, String.class, java.math.BigDecimal.class);

    public static <T> Builder<T> of(String code, Class<T> valueType) {
        return new Builder<>(code, valueType);
    }

    public boolean isSettableAt(ScopeType scopeType) {
        return settableScopes.contains(scopeType);
    }

    /** Builder keeping declarations readable; every field stays explicit. */
    public static final class Builder<T> {
        private final String code;
        private final Class<T> valueType;
        private T defaultValue;
        private Set<ScopeType> settableScopes = EnumSet.allOf(ScopeType.class);
        private String owningModule = "platform";
        private boolean tenantVisible;
        private boolean explicitNullTerminates;
        private String description = "";

        private Builder(String code, Class<T> valueType) {
            this.code = code;
            this.valueType = valueType;
        }

        public Builder<T> defaultValue(T value) {
            this.defaultValue = value;
            return this;
        }

        public Builder<T> settableAt(ScopeType... scopes) {
            this.settableScopes = Set.of(scopes);
            return this;
        }

        public Builder<T> ownedBy(String module) {
            this.owningModule = module;
            return this;
        }

        public Builder<T> tenantVisible() {
            this.tenantVisible = true;
            return this;
        }

        public Builder<T> explicitNullTerminates() {
            this.explicitNullTerminates = true;
            return this;
        }

        public Builder<T> describedAs(String text) {
            this.description = text;
            return this;
        }

        public ConfigurationKey<T> build() {
            return new ConfigurationKey<>(
                    code,
                    valueType,
                    defaultValue,
                    settableScopes,
                    owningModule,
                    tenantVisible,
                    explicitNullTerminates,
                    description);
        }
    }
}
