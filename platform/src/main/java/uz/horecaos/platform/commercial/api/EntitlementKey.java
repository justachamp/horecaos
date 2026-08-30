package uz.horecaos.platform.commercial.api;

import java.util.Objects;
import java.util.Set;

/**
 * A typed, code-owned entitlement definition (ADR 0021).
 *
 * <p>Declared in code for the same reason ADR 0030 declares configuration keys
 * in code: an unknown or mistyped key must fail when a plan version is
 * activated, not resolve silently to nothing on a request path months later.
 * The catalogue in {@link EntitlementKeys} is the compatibility surface, and
 * renaming a key is a migration rather than an edit.
 *
 * <p>Every key carries a safe default — the value and mode that apply when a
 * tenant has no subscription at all, which is the state every tenant is in
 * before onboarding finishes and the state a pilot tenant stays in. The default
 * mode is {@link EnforcementMode#METER_ONLY} and nothing here can raise it.
 *
 * @param <T> the value type, {@code Long} for a counted limit and
 *            {@code Boolean} for a feature
 */
public record EntitlementKey<T>(
        String code,
        Class<T> valueType,
        T safeDefault,
        EnforcementMode defaultMode,
        ResetPeriod resetPeriod,
        String unit,
        Set<String> allowedDimensions,
        String owningModule,
        String description) {

    private static final Set<Class<?>> SUPPORTED_TYPES = Set.of(Boolean.class, Long.class);

    public EntitlementKey {
        Objects.requireNonNull(code, "An entitlement key code is required");
        Objects.requireNonNull(valueType, "A value type is required");
        Objects.requireNonNull(resetPeriod, "A reset period is required");
        Objects.requireNonNull(owningModule, "An owning module is required");
        allowedDimensions = Set.copyOf(
                Objects.requireNonNull(allowedDimensions, "An allowed dimension set is required"));

        // Underscores are allowed in the first segment as well as the later
        // ones, unlike ADR 0030's configuration codes. ADR 0021's own catalogue
        // contains control_plane.users.max_count, and a pattern that rejected
        // the ADR's example would have been a pattern chosen without reading it.
        if (!code.matches("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")) {
            throw new IllegalArgumentException("Key code must be dotted lower case: " + code);
        }
        if (!SUPPORTED_TYPES.contains(valueType)) {
            throw new IllegalArgumentException(
                    "Unsupported entitlement value type %s for %s".formatted(valueType.getName(), code));
        }
        // A safe default that could refuse would make an unsubscribed tenant a
        // broken tenant, which is exactly the failure the meter-only rollout
        // exists to avoid.
        if (defaultMode == null || defaultMode.canRefuse()) {
            throw new IllegalArgumentException(
                    "A catalogue default must never be able to refuse: " + code);
        }
        if (valueType == Boolean.class && resetPeriod != ResetPeriod.NONE) {
            throw new IllegalArgumentException(
                    "A feature entitlement has nothing to reset: " + code);
        }
    }

    public boolean isCounted() {
        return valueType == Long.class;
    }

    public boolean isFeature() {
        return valueType == Boolean.class;
    }

    /** Whether a metering dimension may be recorded against this key (ADR 0029). */
    public boolean permitsDimension(String dimension) {
        return allowedDimensions.contains(dimension);
    }

    public static Builder<Long> counted(String code, String unit) {
        return new Builder<>(code, Long.class, unit);
    }

    public static Builder<Boolean> feature(String code) {
        return new Builder<>(code, Boolean.class, "feature");
    }

    /** Keeps declarations readable while every field stays explicit. */
    public static final class Builder<T> {
        private final String code;
        private final Class<T> valueType;
        private final String unit;
        private T safeDefault;
        private ResetPeriod resetPeriod = ResetPeriod.NONE;
        private Set<String> allowedDimensions = Set.of();
        private String owningModule = "platform";
        private String description = "";

        private Builder(String code, Class<T> valueType, String unit) {
            this.code = code;
            this.valueType = valueType;
            this.unit = unit;
        }

        public Builder<T> safeDefault(T value) {
            this.safeDefault = value;
            return this;
        }

        public Builder<T> resetting(ResetPeriod period) {
            this.resetPeriod = period;
            return this;
        }

        public Builder<T> withDimensions(String... dimensions) {
            this.allowedDimensions = Set.of(dimensions);
            return this;
        }

        public Builder<T> ownedBy(String module) {
            this.owningModule = module;
            return this;
        }

        public Builder<T> describedAs(String text) {
            this.description = text;
            return this;
        }

        public EntitlementKey<T> build() {
            return new EntitlementKey<>(code, valueType, safeDefault, EnforcementMode.METER_ONLY,
                    resetPeriod, unit, allowedDimensions, owningModule, description);
        }
    }
}
