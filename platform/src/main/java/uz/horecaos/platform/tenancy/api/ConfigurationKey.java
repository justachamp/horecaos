package uz.horecaos.platform.tenancy.api;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * A typed, code-owned configuration key (ADR 0030).
 *
 * <p>Keys are declared in code rather than in the database so an unknown key
 * fails at startup instead of silently resolving to a default at read time.
 *
 * @param <T> the resolved value type
 * @param defaultValue absent when the key is declared with {@code
 *                      explicitNullTerminates()} and no default — an explicit
 *                      null at a scope then resolves to null rather than to a
 *                      value nobody chose
 * @param entitledBy   the code of the ADR 0021 plan feature that must be in force for this
 *                      key to be set at all (settings.md §1.2's fifth state, «Locked by
 *                      plan»), or absent for a key no plan gates. A string and not a
 *                      {@code commercial} type: {@code tenancy} must not depend on {@code
 *                      commercial}, and the operations surface asks the {@code iam}
 *                      {@code EntitlementGate} about the code. {@code
 *                      ConfigurationKeyEntitlementTests} holds every code named here to a
 *                      feature key {@code EntitlementKeys} declares
 * @param provisional present while the key's default is a retention period nobody with the authority has confirmed
 *                    (ADR 0029, «Provisional retention»); see {@link ProvisionalRetention}
 */
public record ConfigurationKey<T>(
        String code,
        Class<T> valueType,
        @Nullable T defaultValue,
        Set<ScopeType> settableScopes,
        String owningModule,
        boolean tenantVisible,
        boolean explicitNullTerminates,
        String description,
        @Nullable String entitledBy,
        @Nullable ProvisionalRetention provisional) {

    /** A key no plan feature gates: how every declaration was written before {@code entitledBy} existed. */
    public ConfigurationKey(
            String code,
            Class<T> valueType,
            @Nullable T defaultValue,
            Set<ScopeType> settableScopes,
            String owningModule,
            boolean tenantVisible,
            boolean explicitNullTerminates,
            String description) {
        this(
                code,
                valueType,
                defaultValue,
                settableScopes,
                owningModule,
                tenantVisible,
                explicitNullTerminates,
                description,
                null,
                null);
    }

    /**
     * A retention period that is a placeholder (ADR 0029, «Provisional retention»): the key's <em>default</em> is a guess
     * until someone with the authority confirms it, and confirming it is writing the value at {@code PLATFORM} scope, which
     * ADR 0030 audits. While the key still resolves from its code default it is provisional, and the startup check reports
     * it (and refuses a production start when it governs personal data).
     *
     * @param governsPersonalData whether the rows this period keeps hold personal data. A provisional period over counts
     *                            and no person (the customer funnel) is reported; one over a person's data is refused
     * @param awaitingConfirmationFrom who must confirm it, named so the report says whom to ask
     */
    public record ProvisionalRetention(boolean governsPersonalData, String awaitingConfirmationFrom) {

        public ProvisionalRetention {
            Objects.requireNonNull(awaitingConfirmationFrom, "Say who must confirm a provisional period");
            if (awaitingConfirmationFrom.isBlank()) {
                throw new IllegalArgumentException("Say who must confirm a provisional period");
            }
        }
    }

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
        if (entitledBy != null && !entitledBy.matches("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")) {
            throw new IllegalArgumentException(
                    "entitledBy must be an entitlement key code (dotted lower case): " + entitledBy);
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
        private @Nullable T defaultValue;
        private Set<ScopeType> settableScopes = EnumSet.allOf(ScopeType.class);
        private String owningModule = "platform";
        private boolean tenantVisible;
        private boolean explicitNullTerminates;
        private String description = "";
        private @Nullable String entitledBy;
        private @Nullable ProvisionalRetention provisional;

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

        /**
         * Gates this key behind an ADR 0021 plan feature: a tenant whose plan does not include {@code
         * entitlementKeyCode} sees the key locked (settings.md §1.2) and may not set it.
         */
        public Builder<T> entitledBy(String entitlementKeyCode) {
            this.entitledBy = entitlementKeyCode;
            return this;
        }

        /** Marks the key's default as a provisional retention period (ADR 0029); see {@link ProvisionalRetention}. */
        public Builder<T> provisionalRetention(boolean governsPersonalData, String awaitingConfirmationFrom) {
            this.provisional = new ProvisionalRetention(governsPersonalData, awaitingConfirmationFrom);
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
                    description,
                    entitledBy,
                    provisional);
        }
    }
}
