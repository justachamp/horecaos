package uz.horecaos.platform.tenancy.domain.configuration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The code-owned configuration key registry (ADR 0030).
 *
 * <p>Registering a key in code rather than in the database is what makes an
 * unknown or mistyped key fail at startup instead of silently resolving to a
 * default at read time. Adding a key is a release; composing values per tenant
 * is not.
 */
public final class ConfigurationKeys {

    public static final ConfigurationKey<Integer> ORDER_APPROVAL_TIMEOUT_SECONDS = ConfigurationKey.of(
                    "ordering.approval_timeout_seconds", Integer.class)
            .defaultValue(600)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Seconds a restaurant-approval order waits before the timeout action applies.")
            .build();

    public static final ConfigurationKey<Integer> CART_EXPIRY_MINUTES = ConfigurationKey.of(
                    "ordering.cart_expiry_minutes", Integer.class)
            .defaultValue(60)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes an untouched cart stays active before expiring.")
            .build();

    public static final ConfigurationKey<Integer> QUOTE_TTL_SECONDS = ConfigurationKey.of(
                    "pricing.quote_ttl_seconds", Integer.class)
            .defaultValue(300)
            .ownedBy("pricing")
            .describedAs("Seconds a pricing quote stays acceptable at checkout.")
            .build();

    public static final ConfigurationKey<Integer> INVENTORY_RESERVATION_TTL_SECONDS = ConfigurationKey.of(
                    "inventory.reservation_ttl_seconds", Integer.class)
            .defaultValue(900)
            .ownedBy("inventory")
            .describedAs("Seconds an inventory reservation is held before expiry.")
            .build();

    public static final ConfigurationKey<String> DEFAULT_LOCALE = ConfigurationKey.of(
                    "platform.default_locale", String.class)
            .defaultValue("uz")
            .tenantVisible()
            .describedAs("Locale used when a request expresses no supported preference.")
            .build();

    public static final ConfigurationKey<Boolean> POS_SYNC_ENABLED = ConfigurationKey.of(
                    "integration.pos_sync_enabled", Boolean.class)
            .defaultValue(Boolean.FALSE)
            .ownedBy("integration")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION)
            .describedAs("Whether scheduled POS catalogue synchronisation runs for this scope.")
            .build();

    public static final ConfigurationKey<Integer> NOTIFICATION_QUIET_HOURS_START = ConfigurationKey.of(
                    "notifications.quiet_hours_start_hour", Integer.class)
            .ownedBy("notifications")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .explicitNullTerminates()
            .describedAs("Local hour quiet hours begin; an explicit null disables quiet hours here.")
            .build();

    /**
     * ADR 0021: the strongest enforcement mode entitlement checks may apply.
     *
     * <p>Declared here so a stored row for it passes the startup validator, and
     * declared identically in {@code commercial.api.CommercialConfigurationKeys}
     * where it is used — this registry is internal to tenancy and the commercial
     * module cannot read it, while a reference the other way would make the two
     * modules cyclic. A test in the commercial module keeps the pair equal.
     *
     * <p>The default is {@code METER_ONLY}: nothing the commercial module knows
     * about refuses anything until somebody raises this for a named tenant.
     */
    public static final ConfigurationKey<String> COMMERCIAL_ENFORCEMENT_CEILING = ConfigurationKey.of(
                    "commercial.enforcement_ceiling", String.class)
            .defaultValue("METER_ONLY")
            .ownedBy("commercial")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("The strongest enforcement mode entitlement checks may apply for this tenant. "
                    + "METER_ONLY measures and refuses nothing.")
            .build();

    /**
     * ADR 0045: when courier telemetry is collected inside an open duty session.
     *
     * <p>Declared here so a stored row for it passes the startup validator, and
     * declared identically in {@code telemetry.api.TelemetryConfigurationKeys}
     * where it is used, for the reason recorded on
     * {@link #COMMERCIAL_ENFORCEMENT_CEILING}: this registry is internal to
     * tenancy, and a reference the other way would make the modules cyclic.
     *
     * <p>{@code ON_DUTY} is the default because the dispatcher board's job is to
     * assign work, assigning work means seeing who is free, and the couriers who
     * are free are exactly the ones {@code ON_ASSIGNMENT} would hide.
     */
    public static final ConfigurationKey<String> TELEMETRY_COLLECTION_GATE = ConfigurationKey.of(
                    "telemetry.courier_collection_gate", String.class)
            .defaultValue("ON_DUTY")
            .ownedBy("telemetry")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION)
            .describedAs("When courier telemetry is collected inside an open duty session. "
                    + "ON_DUTY collects for the whole session so a dispatcher can see idle "
                    + "couriers; ON_ASSIGNMENT collects only while carrying an order.")
            .build();

    /**
     * ADR 0045: days a courier's track survives at coordinate precision.
     *
     * <p>Thirty is derived rather than picked — it clears the floor of settlement
     * period plus statement dispute window with room for a calendar longer than
     * the pilot's — and a production start refuses a stored value below that
     * floor at any scope.
     */
    public static final ConfigurationKey<Integer> TELEMETRY_TRACK_RETENTION_DAYS = ConfigurationKey.of(
                    "telemetry.track_retention_days", Integer.class)
            .defaultValue(30)
            .ownedBy("telemetry")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Days a courier's track is kept at coordinate precision before its "
                    + "daily partition is dropped. Must be at least the ADR 0042 settlement "
                    + "period plus the statement dispute window; a production start refuses "
                    + "a value below that floor.")
            .build();

    private static final Map<String, ConfigurationKey<?>> BY_CODE = index(List.of(
            ORDER_APPROVAL_TIMEOUT_SECONDS,
            CART_EXPIRY_MINUTES,
            QUOTE_TTL_SECONDS,
            INVENTORY_RESERVATION_TTL_SECONDS,
            DEFAULT_LOCALE,
            POS_SYNC_ENABLED,
            NOTIFICATION_QUIET_HOURS_START,
            COMMERCIAL_ENFORCEMENT_CEILING,
            TELEMETRY_COLLECTION_GATE,
            TELEMETRY_TRACK_RETENTION_DAYS));

    private ConfigurationKeys() {}

    private static Map<String, ConfigurationKey<?>> index(List<ConfigurationKey<?>> keys) {
        Map<String, ConfigurationKey<?>> byCode = new LinkedHashMap<>();
        for (ConfigurationKey<?> key : keys) {
            if (byCode.put(key.code(), key) != null) {
                throw new IllegalStateException("Duplicate configuration key: " + key.code());
            }
        }
        return Map.copyOf(byCode);
    }

    public static Collection<ConfigurationKey<?>> all() {
        return BY_CODE.values();
    }

    public static Optional<ConfigurationKey<?>> find(String code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }

    public static ConfigurationKey<?> require(String code) {
        return find(code)
                .orElseThrow(() -> new UnknownConfigurationKeyException(
                        "Unknown configuration key \"%s\". Declare it in ConfigurationKeys (ADR 0030)."
                                .formatted(code)));
    }

    /** Thrown when a stored or requested key has no code-owned declaration. */
    public static final class UnknownConfigurationKeyException extends IllegalStateException {
        public UnknownConfigurationKeyException(String message) {
            super(message);
        }
    }
}
