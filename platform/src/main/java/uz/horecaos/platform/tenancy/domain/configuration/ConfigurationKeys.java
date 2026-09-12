package uz.horecaos.platform.tenancy.domain.configuration;

import java.math.BigDecimal;
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
 *
 * <p><strong>Four keys were removed here on 2026-09-10</strong> — {@code
 * ordering.approval_timeout_seconds}, {@code notifications.quiet_hours_start_hour},
 * {@code platform.default_locale}, and {@code integration.pos_sync_enabled} —
 * because each had a repository-wide search turn up zero consumers and a
 * better, already-live source of truth: order acceptance's own {@code
 * ordering.acceptance} policy document, marketing's per-tenant {@code
 * quiet_hours_start}/{@code quiet_hours_end} engagement-store columns, the
 * Telegram-specific {@code horecaos.notifications.telegram.group-locale}
 * property, and {@code integration.pos_sync_schedules.enabled} together with a
 * binding's own {@code CATALOG_READ} capability state, respectively. {@code
 * ConfigurationKeyStartupValidator} refuses to boot over an undeclared stored
 * key, so removing a declaration here required deleting any stored rows for it
 * in the same change — {@code V0194}. Never re-add a code under one of these
 * four names without checking whether the same duplication is what made it
 * dead the first time.
 */
public final class ConfigurationKeys {

    /**
     * Seconds a pricing quote stays acceptable at checkout.
     *
     * <p>900 (fifteen minutes), not the five minutes an earlier draft of this
     * key declared: {@code QuoteService.QUOTE_TTL} has always been fifteen
     * minutes, and until this key was wired to a consumer nobody noticed the
     * declared default disagreed with the code by 3x. A wired key's default is
     * the live value for every tenant that has not overridden it, so the two
     * must agree exactly. {@code pricing.api.PricingConfigurationKeys}
     * declares the identical key for the same cyclic-dependency reason
     * recorded on {@link #COMMERCIAL_ENFORCEMENT_CEILING}; {@code
     * PricingConfigurationKeyTests} keeps the two in step.
     *
     * <p>Coupled by design to {@link #INVENTORY_RESERVATION_TTL_SECONDS}: an
     * inventory reservation must never expire before the quote it backs, or
     * stock releases while the price is still acceptable — overselling.
     * {@code InventoryService.reserveForQuote} enforces that floor
     * structurally, against the specific quote's own stored expiry, not by
     * trusting the two keys to agree (see that method's own doc and {@code
     * ReservationExpiryTests}).
     */
    public static final ConfigurationKey<Integer> QUOTE_TTL_SECONDS = ConfigurationKey.of(
                    "pricing.quote_ttl_seconds", Integer.class)
            .defaultValue(900)
            .ownedBy("pricing")
            .describedAs("Seconds a pricing quote stays acceptable at checkout.")
            .build();

    /**
     * Minutes an untouched cart stays active before expiring.
     *
     * <p>240 (four hours), not the sixty minutes an earlier draft of this key
     * declared: {@code CartService.CART_TTL} has always been four hours,
     * deliberately far longer than the quote TTL because a cart survives an
     * interruption but a price does not (see that field's own doc). The same
     * "a wired key's default is a live value" correction as {@link
     * #QUOTE_TTL_SECONDS}.
     */
    public static final ConfigurationKey<Integer> CART_EXPIRY_MINUTES = ConfigurationKey.of(
                    "ordering.cart_expiry_minutes", Integer.class)
            .defaultValue(240)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes an untouched cart stays active before expiring.")
            .build();

    /**
     * Seconds an inventory reservation is held before expiry.
     *
     * <p>Settable, but never the last word: see {@link #QUOTE_TTL_SECONDS}'s
     * doc for why a hold may never expire before the quote it was taken for,
     * and {@code InventoryService.reserveForQuote} for where that is actually
     * enforced.
     */
    public static final ConfigurationKey<Integer> INVENTORY_RESERVATION_TTL_SECONDS = ConfigurationKey.of(
                    "inventory.reservation_ttl_seconds", Integer.class)
            .defaultValue(900)
            .ownedBy("inventory")
            .describedAs("Seconds an inventory reservation is held before expiry.")
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

    /**
     * ADR 0027: how long a {@code SECURITY}-class audit event is retained
     * before archival, in days.
     *
     * <p>The owner's answer of 2026-09-08 to that ADR's own open input
     * ("retention periods per audit class") is "configurable, defaulting to
     * ten years for both classes" — this key and {@link
     * #AUDIT_BUSINESS_RETENTION_DAYS} are that answer. 3,653 days is ten
     * Gregorian years counted from a non-leap 1 January, matching how a legal
     * retention period is normally quoted. {@code AuditPartitionArchiver}
     * reads this key — the longer of the two, taken with {@link
     * #AUDIT_BUSINESS_RETENTION_DAYS} — to compute the retention lock on a
     * closed partition's archive object before moving it to protected storage
     * and dropping the live partition; see ADR 0027's own Implementation
     * notes.
     */
    public static final ConfigurationKey<Integer> AUDIT_SECURITY_RETENTION_DAYS = ConfigurationKey.of(
                    "audit.security_retention_days", Integer.class)
            .defaultValue(3653)
            .ownedBy("audit")
            .settableAt(ScopeType.PLATFORM)
            .describedAs("Days a SECURITY-class audit event is retained before archival. "
                    + "Defaults to ten years. Read by AuditPartitionArchiver when it locks a "
                    + "closed partition's archive object.")
            .build();

    /**
     * ADR 0027: the same question for {@code BUSINESS}-class events.
     *
     * <p>{@code audit.api.AuditClass} names exactly these two classes, and the
     * owner's directive treats them identically: both default to ten years,
     * and both are platform-only, because a retention floor set by legal or
     * finance is not a per-tenant choice.
     */
    public static final ConfigurationKey<Integer> AUDIT_BUSINESS_RETENTION_DAYS = ConfigurationKey.of(
                    "audit.business_retention_days", Integer.class)
            .defaultValue(3653)
            .ownedBy("audit")
            .settableAt(ScopeType.PLATFORM)
            .describedAs("Days a BUSINESS-class audit event is retained before archival. "
                    + "Defaults to ten years. Read by AuditPartitionArchiver when it locks a "
                    + "closed partition's archive object.")
            .build();

    /**
     * ADR 0063: the phone-shape gate for Telegram share-contact sign-in.
     *
     * <p>Declared here so a stored row for it passes the startup validator, and
     * declared identically in {@code customers.api.CustomerConfigurationKeys}
     * where it is consumed, for the reason recorded on {@link
     * #COMMERCIAL_ENFORCEMENT_CEILING}: this registry is internal to tenancy,
     * and a reference the other way would make the modules cyclic.
     */
    public static final ConfigurationKey<String> CUSTOMERS_TELEGRAM_AUTH_PHONE_PATTERN = ConfigurationKey.of(
                    "customers.telegram_auth_phone_pattern", String.class)
            .defaultValue("^\\+?998\\d{9}$")
            .ownedBy("customers")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("Regular expression a Telegram-shared contact's phone number must match "
                    + "for ADR 0063 share-contact sign-in. Defaults to an Uzbek mobile in E.164.")
            .build();

    /**
     * ADR 0063: the dual-channel OTP delivery order, the owner's 2026-09-08
     * direction to "keep both SMS and Telegram Gateway" and let the control
     * plane — not a hardcoded Java conditional — decide which is tried first.
     *
     * <p>Declared here so a stored row for it passes the startup validator, and
     * declared identically in {@code customers.api.CustomerConfigurationKeys}
     * where it is consumed by {@code
     * integration.camel.sms.CamelVerificationCodeTransport}, for the reason
     * recorded on {@link #COMMERCIAL_ENFORCEMENT_CEILING}: this registry is
     * internal to tenancy, and a reference the other way would make the
     * modules cyclic.
     *
     * <p>The default, {@code TELEGRAM_GATEWAY,SMS}, restates ADR 0063's own
     * Decision as data: Gateway is cheaper but reaches only a number with a
     * Telegram account, so it goes first and SMS — which ADR 0063's own
     * Alternatives table says can "never fully" be dropped — is what catches
     * everyone else.
     */
    public static final ConfigurationKey<String> CUSTOMERS_OTP_DELIVERY_CHANNEL_ORDER = ConfigurationKey.of(
                    "customers.otp_delivery_channel_order", String.class)
            .defaultValue("TELEGRAM_GATEWAY,SMS")
            .ownedBy("customers")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("Comma-separated order (a permutation of TELEGRAM_GATEWAY,SMS, both required) "
                    + "in which ADR 0015 verification-code delivery tries its two channels. "
                    + "Defaults to Telegram Gateway first (cheaper) with SMS as the reachability "
                    + "fallback; whichever channel is not tried first is still tried when the "
                    + "first is unconfigured or does not accept the message.")
            .build();

    /**
     * ADR 0082: whether a tenant's operations app shows its administrators the
     * HorecaOS support visits to their account (ADR 0081), and lets them end
     * one. Off until turned on: the page is new, and the first tenants to see
     * it are chosen rather than everyone at once.
     */
    public static final ConfigurationKey<Boolean> FEATURE_SUPPORT_VISITS = ConfigurationKey.of(
                    "feature.support_visits", Boolean.class)
            .defaultValue(false)
            .ownedBy("iam")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Shows a tenant's administrators the HorecaOS support visits to their "
                    + "account in the operations app, and lets them end one.")
            .build();

    /**
     * Wave P46 (gap map row {@code 10.3b}), settings.md §10.3 cards 2–5:
     * order policy's eleven registry entries. Declared identically in {@code
     * ordering.api.OrderingConfigurationKeys}, which is where every one of
     * them is actually consumed once a reader exists — see that class's own
     * doc for why none of them is consumed yet, and {@code
     * OrderingConfigurationKeyTests} for the drift check.
     */
    public static final ConfigurationKey<Integer> ORDERING_BUSINESS_DAY_START_HOUR = ConfigurationKey.of(
                    "ordering.business_day_start_hour", Integer.class)
            .defaultValue(6)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("The local hour (0-23) a trading day is considered to start.")
            .build();

    public static final ConfigurationKey<Integer> ORDERING_AVERAGE_ORDER_MINUTES = ConfigurationKey.of(
                    "ordering.average_order_minutes", Integer.class)
            .defaultValue(30)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes an order is expected to take from acceptance to ready.")
            .build();

    public static final ConfigurationKey<Integer> ORDERING_MAXIMUM_ORDER_MINUTES = ConfigurationKey.of(
                    "ordering.maximum_order_minutes", Integer.class)
            .defaultValue(60)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes beyond which an order is unambiguously overdue.")
            .build();

    public static final ConfigurationKey<Integer> ORDERING_LATE_ORDER_THRESHOLD_MINUTES = ConfigurationKey.of(
                    "ordering.late_order_threshold_minutes", Integer.class)
            .defaultValue(45)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes after acceptance at which an order is coloured late on the board.")
            .build();

    public static final ConfigurationKey<Long> ORDERING_MINIMUM_ORDER_AMOUNT_MINOR = ConfigurationKey.of(
                    "ordering.minimum_order_amount_minor", Long.class)
            .defaultValue(0L)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("The smallest order total accepted for pickup and dine-in, in minor units. "
                    + "Delivery's own minimum comes from the service zone, not this key.")
            .build();

    public static final ConfigurationKey<BigDecimal> ORDERING_VAT_RATE_PERCENT = ConfigurationKey.of(
                    "ordering.vat_rate_percent", BigDecimal.class)
            .defaultValue(new BigDecimal("12"))
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("The VAT rate applied at checkout, as a percentage.")
            .build();

    public static final ConfigurationKey<Integer> ORDERING_ROUTING_POLL_INTERVAL_MINUTES = ConfigurationKey.of(
                    "ordering.routing_poll_interval_minutes", Integer.class)
            .defaultValue(2)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes between polls of the routing port while a delivery order awaits assignment.")
            .build();

    public static final ConfigurationKey<String> ORDERING_PREORDER_BRANCH_RESOLUTION = ConfigurationKey.of(
                    "ordering.preorder_branch_resolution", String.class)
            .defaultValue("BY_DISTANCE")
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("How a pre-order outside working hours picks its fulfilling branch: "
                    + "BY_DISTANCE or BY_OPENING_TIME.")
            .build();

    public static final ConfigurationKey<Boolean> ORDERING_OPERATOR_PROMO_CODE_ALLOWED = ConfigurationKey.of(
                    "ordering.operator_promo_code_allowed", Boolean.class)
            .defaultValue(false)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Whether an operator hand-entering an order may apply a promo code.")
            .build();

    public static final ConfigurationKey<String> ORDERING_AUTO_ACCEPT_ELIGIBLE_CHANNELS = ConfigurationKey.of(
                    "ordering.auto_accept_eligible_channels", String.class)
            .defaultValue("ALL")
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("\"ALL\", or a comma-separated list of sales channel codes eligible for "
                    + "auto-accept. Not yet enforced.")
            .build();

    public static final ConfigurationKey<Integer> ORDERING_AUTO_ACCEPT_MIN_PRIOR_ORDERS = ConfigurationKey.of(
                    "ordering.auto_accept_min_prior_orders", Integer.class)
            .defaultValue(0)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Prior successful orders a customer needs before auto-accept applies to "
                    + "them. 0 means no gate. Not yet enforced.")
            .build();

    /**
     * Wave P46 (gap map row {@code 4.4d}): catalog base settings' first
     * switch. Declared identically in {@code
     * inventory.api.InventoryConfigurationKeys}, because {@code
     * InventoryService} reads it to explain, rather than silently repeat,
     * why {@code QUANTITY} tracking is refused — see that declaration's own
     * doc.
     */
    public static final ConfigurationKey<Boolean> CATALOG_USE_STOCK_LOGIC = ConfigurationKey.of(
                    "catalog.use_stock_logic", Boolean.class)
            .defaultValue(false)
            .ownedBy("inventory")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Turns counted-stock tracking on for the whole tenant. Not yet enforced: "
                    + "QUANTITY tracking mode is still refused either way.")
            .build();

    /**
     * Wave P46 (gap map row {@code 4.4d}): catalog base settings' second
     * switch, "QR and kiosk sell at hall prices." {@code
     * tenancy.api.SalesChannel#pricingChannelId()} already gives every
     * channel a one-hop price-plane override, authored by hand per channel
     * through {@code SalesChannelService} — this key does not change that
     * resolution; nothing outside this registry reads it yet, so unlike
     * {@link #CATALOG_USE_STOCK_LOGIC} it is declared only here. It exists so
     * the switch can be authored and shown through the operations surface
     * ahead of whichever later change makes a QR or kiosk channel with no
     * price plane of its own default to the tenant's hall channel instead of
     * requiring every one to be pointed there manually.
     */
    public static final ConfigurationKey<Boolean> CATALOG_QR_KIOSK_PRICE_PLANE = ConfigurationKey.of(
                    "catalog.qr_kiosk_price_plane", Boolean.class)
            .defaultValue(false)
            .ownedBy("catalog")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("QR and kiosk channels take the tenant's hall (dine-in) price plane "
                    + "automatically. Not yet enforced: a channel with no price plane of its own "
                    + "still needs one set by hand through Sales channels.")
            .build();

    private static final Map<String, ConfigurationKey<?>> BY_CODE = index(List.of(
            CART_EXPIRY_MINUTES,
            QUOTE_TTL_SECONDS,
            INVENTORY_RESERVATION_TTL_SECONDS,
            COMMERCIAL_ENFORCEMENT_CEILING,
            TELEMETRY_COLLECTION_GATE,
            TELEMETRY_TRACK_RETENTION_DAYS,
            AUDIT_SECURITY_RETENTION_DAYS,
            AUDIT_BUSINESS_RETENTION_DAYS,
            CUSTOMERS_TELEGRAM_AUTH_PHONE_PATTERN,
            CUSTOMERS_OTP_DELIVERY_CHANNEL_ORDER,
            FEATURE_SUPPORT_VISITS,
            ORDERING_BUSINESS_DAY_START_HOUR,
            ORDERING_AVERAGE_ORDER_MINUTES,
            ORDERING_MAXIMUM_ORDER_MINUTES,
            ORDERING_LATE_ORDER_THRESHOLD_MINUTES,
            ORDERING_MINIMUM_ORDER_AMOUNT_MINOR,
            ORDERING_VAT_RATE_PERCENT,
            ORDERING_ROUTING_POLL_INTERVAL_MINUTES,
            ORDERING_PREORDER_BRANCH_RESOLUTION,
            ORDERING_OPERATOR_PROMO_CODE_ALLOWED,
            ORDERING_AUTO_ACCEPT_ELIGIBLE_CHANNELS,
            ORDERING_AUTO_ACCEPT_MIN_PRIOR_ORDERS,
            CATALOG_USE_STOCK_LOGIC,
            CATALOG_QR_KIOSK_PRICE_PLANE));

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

    /**
     * ADR 0082: the feature flags — every boolean key in the {@code feature.}
     * namespace, settable at the platform and per tenant, off by default.
     */
    @SuppressWarnings("unchecked")
    public static List<ConfigurationKey<Boolean>> featureFlags() {
        return BY_CODE.values().stream()
                .filter(key -> key.code().startsWith(FEATURE_PREFIX) && key.valueType() == Boolean.class)
                .map(key -> (ConfigurationKey<Boolean>) key)
                .sorted(java.util.Comparator.comparing(ConfigurationKey::code))
                .toList();
    }

    /** The namespace a feature flag's code starts with. */
    public static final String FEATURE_PREFIX = "feature.";

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
