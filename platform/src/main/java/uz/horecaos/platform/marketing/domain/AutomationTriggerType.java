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
 * <p><strong>{@code CASHBACK_CHANGE} is now offered too</strong> (V0421, batch
 * 12): {@code loyalty.api.LoyaltyBalanceChanged} is a Modulith application
 * event {@code LoyaltyAccrualService#accrue} and {@code
 * PointsRedemptionService#reserve} both publish — ids and amounts only, no
 * PII, as every event payload in this codebase must be — and {@code
 * uz.horecaos.platform.marketing.application.LoyaltyBalanceChangeAutomationTrigger}
 * consumes it the same way {@code OrderCompletionAccrualTrigger} consumes
 * {@code OrderCompleted}: a {@code @TransactionalEventListener} in loyalty's
 * own commit, not a sweep, because there is no polling candidate query for
 * "whose balance changed" the way there is for a birthday or a day count —
 * the fact is inherently point-in-time. Its guard reuses {@link
 * uz.horecaos.platform.marketing.domain.AutomationGuardKeys#cooldownBucket}
 * unchanged, so a customer whose balance moves several times inside one
 * cooldown window is told about it once, the same "guard/cooldown/quiet-hours
 * semantics the existing triggers have" restated for an event-driven trigger
 * rather than a swept one. {@code minimumChangeMinor} is this kind's
 * threshold: a change smaller than it is not a candidate at all, the same way
 * a customer outside {@code birthdayWindowDays} is not one.
 *
 * <p><strong>One trigger kind this row's own instructions named is still
 * deliberately not offered</strong>, and it is not an oversight:
 *
 * <ul>
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
    CART_ABANDONMENT("abandonmentDelayHours"),

    /**
     * A {@code loyalty.api.LoyaltyBalanceChanged} whose {@code |deltaMinor|} is
     * at least {@code minimumChangeMinor}. Event-driven, not swept — see this
     * enum's own doc.
     */
    CASHBACK_CHANGE("minimumChangeMinor");

    private final String configKey;

    AutomationTriggerType(String configKey) {
        this.configKey = configKey;
    }

    /** The one key this trigger's {@code trigger_config} bag must carry, with a non-negative value. */
    public String configKey() {
        return configKey;
    }
}
