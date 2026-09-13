package uz.horecaos.platform.ordering.api;

import java.math.BigDecimal;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The ordering module's ADR 0030 configuration keys.
 *
 * <p>{@link #CART_EXPIRY_MINUTES} was the first, wired 2026-09-10 for the
 * identical reason recorded on {@code pricing.api.PricingConfigurationKeys}:
 * the declared default (sixty minutes) disagreed with {@link
 * uz.horecaos.platform.ordering.application.CartService#CART_TTL} (four
 * hours) by 4x, harmlessly while the key was dead and not harmlessly the
 * moment it was wired without correcting the default.
 *
 * <p>Wave P46 (gap map row {@code 10.3b}, settings.md §10.3 cards 2–5) adds
 * the eleven that follow. Every one of them is registered so it can be
 * authored and inherited through {@code OperationsConfigurationController} —
 * none of them has a consumer yet. ADR 0002 (acceptance), ADR 0019 (order
 * lifecycle), ADR 0037 (delivery routing) and ADR 0018 (promotions) each own
 * a slice of what these values mean; wiring a reader for one is that ADR's
 * work, not this wave's. Registering the key first, with a default that
 * changes nothing until a reader exists, is what lets settings.md's five
 * cards stop rendering "not built" one field at a time instead of all at
 * once.
 * <p>And, since ADR 0109 (Settings 10.11): how long an abandoned cart —
 * one that expired without becoming an order — is kept before {@code
 * CartRetentionSweeper} deletes it outright. A different question from the
 * one above: {@link #CART_EXPIRY_MINUTES} decides when a cart stops being
 * usable at checkout, this decides how long its record survives afterwards
 * for the data-privacy self-service screen's own "retention periods" gap.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup
 * validator consults lives in {@code tenancy.domain.configuration}, which is
 * internal to the tenancy module; importing it here is not possible and
 * importing this from there would make the two modules cyclic. The registry
 * therefore carries an identical declaration, and {@code
 * OrderingConfigurationKeyTests} fails the build if the two ever drift apart.
 */
public final class OrderingConfigurationKeys {

    /** The code both declarations share. */
    public static final String CART_EXPIRY_MINUTES_CODE = "ordering.cart_expiry_minutes";

    /** The code both declarations share. */
    public static final String CART_RETENTION_DAYS_CODE = "ordering.cart_retention_days";

    /**
     * Minutes an untouched cart stays active before expiring.
     *
     * <p>240 (four hours): long enough for a customer to be interrupted and
     * come back, deliberately far longer than the fifteen-minute quote TTL —
     * the cart survives an interruption, the price does not.
     */
    public static final ConfigurationKey<Integer> CART_EXPIRY_MINUTES = ConfigurationKey.of(
                    CART_EXPIRY_MINUTES_CODE, Integer.class)
            .defaultValue(240)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes an untouched cart stays active before expiring.")
            .build();

    /** Card 2 (Тайминги и SLA): the local hour the business day is considered to start. */
    public static final String BUSINESS_DAY_START_HOUR_CODE = "ordering.business_day_start_hour";

    /**
     * The local hour (0–23) a trading day starts, for whichever report or
     * screen needs to group orders by "today" rather than by the calendar
     * date — settings.md §10.3 card 2, ADR 0043's own open question about a
     * business day crossing midnight. 6 (06:00): before most HoReCa venues
     * open, so a late-night order after midnight still counts against the
     * previous trading day rather than starting a new one an hour later.
     */
    public static final ConfigurationKey<Integer> BUSINESS_DAY_START_HOUR = ConfigurationKey.of(
                    BUSINESS_DAY_START_HOUR_CODE, Integer.class)
            .defaultValue(6)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("The local hour (0-23) a trading day is considered to start.")
            .build();

    /** Card 2: the expected time from acceptance to ready, in minutes. */
    public static final String AVERAGE_ORDER_MINUTES_CODE = "ordering.average_order_minutes";

    /**
     * Minutes an order is expected to take from acceptance to ready — the
     * figure a kitchen display or an ETA quote would read, once one reads
     * this key. 30: a common full-service estimate; a quick-service branch
     * overrides it narrower at LOCATION scope.
     */
    public static final ConfigurationKey<Integer> AVERAGE_ORDER_MINUTES = ConfigurationKey.of(
                    AVERAGE_ORDER_MINUTES_CODE, Integer.class)
            .defaultValue(30)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes an order is expected to take from acceptance to ready.")
            .build();

    /** Card 2: the outer bound before an order is unambiguously overdue. */
    public static final String MAXIMUM_ORDER_MINUTES_CODE = "ordering.maximum_order_minutes";

    /**
     * Minutes beyond which an order is unambiguously overdue, distinct from
     * {@link #LATE_ORDER_THRESHOLD_MINUTES}'s earlier warning line — settings.md
     * §10.3 card 2 lists both because a queue needs a "getting late" colour
     * and a "this is late" colour, not one threshold doing both jobs.
     */
    public static final ConfigurationKey<Integer> MAXIMUM_ORDER_MINUTES = ConfigurationKey.of(
                    MAXIMUM_ORDER_MINUTES_CODE, Integer.class)
            .defaultValue(60)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes beyond which an order is unambiguously overdue.")
            .build();

    /** Card 2: "Заказ опаздывает с" — the single most-used value on the order board. */
    public static final String LATE_ORDER_THRESHOLD_MINUTES_CODE = "ordering.late_order_threshold_minutes";

    /**
     * Minutes after acceptance at which an order is coloured late on the
     * order board. Settings.md §10.3 names this the single most-used value
     * on the whole screen. Lateness is a computed overlay on the order, never
     * a status of its own (IA Part 4) — this key only sets the threshold the
     * overlay compares against; nothing here creates a state.
     */
    public static final ConfigurationKey<Integer> LATE_ORDER_THRESHOLD_MINUTES = ConfigurationKey.of(
                    LATE_ORDER_THRESHOLD_MINUTES_CODE, Integer.class)
            .defaultValue(45)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes after acceptance at which an order is coloured late on the board.")
            .build();

    /** Card 2: "Минимальная сумма заказа" — pickup and dine-in; delivery is a zone concern. */
    public static final String MINIMUM_ORDER_AMOUNT_MINOR_CODE = "ordering.minimum_order_amount_minor";

    /**
     * The smallest order total accepted for pickup and dine-in, in minor
     * units of the tenant's own {@code defaultCurrency} (there is no paired
     * currency field here — {@link ConfigurationKey} carries no composite
     * money type, and every tenant this platform onboards today trades in
     * exactly one currency; see {@code Markets}). Settings.md §10.3 card 2
     * names an overlapping value, {@code fulfillment.service_zone_versions
     * .min_basket_minor}, and defers which one wins for delivery to a
     * decision this wave does not make: this key applies to pickup and
     * dine-in only, the zone value continues to own delivery. 0 (no
     * minimum) until a tenant sets one.
     */
    public static final ConfigurationKey<Long> MINIMUM_ORDER_AMOUNT_MINOR = ConfigurationKey.of(
                    MINIMUM_ORDER_AMOUNT_MINOR_CODE, Long.class)
            .defaultValue(0L)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("The smallest order total accepted for pickup and dine-in, in minor units. "
                    + "Delivery's own minimum comes from the service zone, not this key.")
            .build();

    /** Card 2: the VAT rate applied at checkout and on the fiscal receipt. */
    public static final String VAT_RATE_PERCENT_CODE = "ordering.vat_rate_percent";

    /**
     * The VAT rate, as a percentage (0–100). 12: Uzbekistan's standard rate.
     * A location whose legal entity carries a different registration
     * overrides it at LOCATION scope.
     */
    public static final ConfigurationKey<BigDecimal> VAT_RATE_PERCENT = ConfigurationKey.of(
                    VAT_RATE_PERCENT_CODE, BigDecimal.class)
            .defaultValue(new BigDecimal("12"))
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("The VAT rate applied at checkout, as a percentage.")
            .build();

    /** Card 2: "Интервал опроса маршрутизации" — ADR 0037's routing port. */
    public static final String ROUTING_POLL_INTERVAL_MINUTES_CODE = "ordering.routing_poll_interval_minutes";

    /**
     * Minutes between polls of the ADR 0037 routing port while a delivery
     * order waits for a courier assignment decision. 2: frequent enough that
     * a dispatcher does not perceive a stall, far enough apart that polling
     * itself is not the load.
     */
    public static final ConfigurationKey<Integer> ROUTING_POLL_INTERVAL_MINUTES = ConfigurationKey.of(
                    ROUTING_POLL_INTERVAL_MINUTES_CODE, Integer.class)
            .defaultValue(2)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes between polls of the routing port while a delivery order awaits assignment.")
            .build();

    /** Card 4: "Подбор филиала для предзаказа вне рабочего времени." */
    public static final String PREORDER_BRANCH_RESOLUTION_CODE = "ordering.preorder_branch_resolution";

    /**
     * How a pre-order placed outside every candidate branch's working hours
     * picks which branch will fulfil it: {@code BY_DISTANCE} (nearest to the
     * customer) or {@code BY_OPENING_TIME} (soonest to open). {@code
     * BY_DISTANCE} is the default — a customer waiting for a pre-order cares
     * more about who is closest than about who opens first.
     */
    public static final ConfigurationKey<String> PREORDER_BRANCH_RESOLUTION = ConfigurationKey.of(
                    PREORDER_BRANCH_RESOLUTION_CODE, String.class)
            .defaultValue("BY_DISTANCE")
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("How a pre-order outside working hours picks its fulfilling branch: "
                    + "BY_DISTANCE or BY_OPENING_TIME.")
            .build();

    /** Card 5: "Оператор может вводить промокод." */
    public static final String OPERATOR_PROMO_CODE_ALLOWED_CODE = "ordering.operator_promo_code_allowed";

    /**
     * Whether a call-centre operator hand-entering an order (settings.md
     * §10.3 card 5, ADR 0036's {@code CALL_CENTRE} channel) may apply an
     * ADR 0018 promo code. Off by default: a discount a customer did not ask
     * for, applied by whoever answers the phone, is an abuse surface a
     * tenant opts into rather than one it is defaulted into.
     */
    public static final ConfigurationKey<Boolean> OPERATOR_PROMO_CODE_ALLOWED = ConfigurationKey.of(
                    OPERATOR_PROMO_CODE_ALLOWED_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Whether an operator hand-entering an order may apply a promo code.")
            .build();

    /**
     * Card 1's own Delever-comparison gap: "auto-accept restricted to a set
     * of channels."
     */
    public static final String AUTO_ACCEPT_ELIGIBLE_CHANNELS_CODE = "ordering.auto_accept_eligible_channels";

    /**
     * Which sales channels {@code ordering.acceptance}'s {@code AUTO_CONFIRM}
     * mode applies to: the literal {@code "ALL"}, or a comma-separated list
     * of {@code tenant.sales_channels.code} values (ADR 0036) — "auto-accept
     * the bot, hand-check the aggregator." Defaults to {@code "ALL"} so
     * wiring this key changes nothing for a tenant that has not narrowed it:
     * {@code AUTO_CONFIRM} today has no channel filter at all.
     *
     * <p>Registered, not yet read: {@code OrderAcceptancePolicyService}
     * still auto-confirms every channel when the mode says to. A reader
     * belongs to ADR 0002/0030's own follow-up, not this wave.
     */
    public static final ConfigurationKey<String> AUTO_ACCEPT_ELIGIBLE_CHANNELS = ConfigurationKey.of(
                    AUTO_ACCEPT_ELIGIBLE_CHANNELS_CODE, String.class)
            .defaultValue("ALL")
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("\"ALL\", or a comma-separated list of sales channel codes eligible for "
                    + "auto-accept. Not yet enforced.")
            .build();

    /** Card 1's other Delever-comparison gap: the anti-fraud gate. */
    public static final String AUTO_ACCEPT_MIN_PRIOR_ORDERS_CODE = "ordering.auto_accept_min_prior_orders";

    /**
     * How many prior successful orders a customer needs before {@code
     * AUTO_CONFIRM} applies to them at all — an anti-fraud gate, not a
     * convenience, per settings.md §10.3's own Delever comparison. 0 (no
     * gate) preserves today's behaviour; registered for the same
     * not-yet-read reason as {@link #AUTO_ACCEPT_ELIGIBLE_CHANNELS}.
     */
    public static final ConfigurationKey<Integer> AUTO_ACCEPT_MIN_PRIOR_ORDERS = ConfigurationKey.of(
                    AUTO_ACCEPT_MIN_PRIOR_ORDERS_CODE, Integer.class)
            .defaultValue(0)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Prior successful orders a customer needs before auto-accept applies to "
                    + "them. 0 means no gate. Not yet enforced.")
            .build();

    /**
     * Days an abandoned cart is kept before {@code CartRetentionSweeper}
     * deletes it (ADR 0092). Ninety, matching that class's own {@code
     * @Value} default exactly — the same "a wired key's default is the live
     * value" discipline {@link #CART_EXPIRY_MINUTES} follows. Settable at the
     * platform and per tenant, and only ever lengthened: {@code
     * CartRetentionSweeper} sweeps on the longer of the platform default and
     * the largest tenant-configured value, the same rule {@code
     * TrackRetentionSweeper.effectiveRetentionDays} already uses, because a
     * shorter stored value must never delete another tenant's cart early.
     */
    public static final ConfigurationKey<Integer> CART_RETENTION_DAYS = ConfigurationKey.of(
                    CART_RETENTION_DAYS_CODE, Integer.class)
            .defaultValue(90)
            .ownedBy("ordering")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Days an abandoned cart (one that expired without becoming an order) "
                    + "is kept before it is deleted outright.")
            .build();

    private OrderingConfigurationKeys() {}
}
