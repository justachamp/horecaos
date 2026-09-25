package uz.horecaos.platform.marketing.domain;

/**
 * The closed set of automation triggers this slice offers (gap-map row 6.5,
 * ADR 0044's own Triggers table).
 *
 * <p>Three of ADR 0044's four named kinds, offered exactly as that record
 * states them: {@link #BIRTHDAY} ("daily sweep of {@code birth_month_day},
 * once per customer per year"), {@link #INACTIVITY} ("daily sweep of {@code
 * days_since_last_order} crossing a configured band, per-customer cooldown"),
 * and {@link #CART_ABANDONMENT} for ADR 0044's {@code CART_ABANDONED}
 * ("an ADR 0019 cart with no order after a configured delay, cancelled if the
 * cart converts first"). {@code POST_ORDER_REVIEW} is not offered because
 * {@code marketing.reviews} does not exist yet.
 *
 * <p><strong>Two trigger kinds this row's own instructions named are
 * deliberately not offered</strong>, and neither is an oversight:
 *
 * <ul>
 *   <li>{@code CASHBACK_CHANGE} — no accrual or debit event exists in the
 *       {@code loyalty} module today ({@code LoyaltyAccrualService} writes a
 *       ledger entry and publishes nothing to any other module), so a trigger
 *       of this kind would be a rule with no producer. Building the producer
 *       — a new {@code loyalty.api} port or an event {@code loyalty} does not
 *       yet emit — is real design work with its own consistency questions
 *       (accrual is deferred past an earn delay; does "changed" mean the
 *       entry or the point the lot actually becomes spendable?) that deserves
 *       its own decision rather than a rushed answer inside this migration.
 *   <li>{@code LATE_ORDER_APOLOGY} — ADR 0044's own Triggers section states
 *       it is "deliberately absent": lateness is an ADR 0013 recovery event
 *       with a compensation decision attached, and a second, unreconciled
 *       compensation path from marketing is the exact failure that section
 *       names. ADR 0112 ("Campaigns are versioned, offers reference the
 *       catalogue, and the contact policy decides") is where a late-order
 *       apology is designed as a reconciled scenario — and it is {@code
 *       Proposed}, not {@code Accepted}. Building this trigger here would be
 *       re-deciding an Accepted ADR's explicit exclusion rather than filling
 *       a gap it left open.
 * </ul>
 */
public enum AutomationTriggerType {

    /** Daily sweep of {@code birth_month_day}; guarded once per customer per calendar year. */
    BIRTHDAY("birthdayWindowDays"),

    /** Daily sweep of {@code days_since_last_order} crossing {@code inactivityDays}. */
    INACTIVITY("inactivityDays"),

    /** An abandoned cart with no order after {@code abandonmentDelayHours}. */
    CART_ABANDONMENT("abandonmentDelayHours");

    private final String configKey;

    AutomationTriggerType(String configKey) {
        this.configKey = configKey;
    }

    /** The one key this trigger's {@code trigger_config} bag must carry, with a non-negative value. */
    public String configKey() {
        return configKey;
    }
}
