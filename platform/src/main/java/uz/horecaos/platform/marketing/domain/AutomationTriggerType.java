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
 * <p><strong>{@code LATE_ORDER_APOLOGY} is offered now, with the reconciliation ADR 0044
 * demanded of it.</strong> ADR 0044 left it "deliberately absent": lateness is an ADR 0013
 * recovery event with a compensation decision attached, and a second, unreconciled
 * compensation path from marketing is how one late delivery gets both a refund from support
 * and a promo code from a trigger. ADR 0112 is Accepted, and this kind is built to be that
 * reconciliation rather than a second path:
 *
 * <ul>
 *   <li><em>Words, never a benefit.</em> A rule names a template and nothing else: the table
 *       has no offer, promotion or accrual-rule column to state a discount in, so the apology
 *       cannot compensate. Compensation stays ADR 0013's, decided by a person with a reason.
 *   <li><em>An order support has already made good is not apologised to again.</em> The
 *       firing is cancelled, with that reason on its run row, when {@code
 *       payments.order_remedies} holds any remedy for the order, read inside the firing's own
 *       transaction after its guard key is claimed.
 *   <li><em>Support gets first refusal.</em> An order becomes a candidate only once it has
 *       been closed for {@code AutomationSweepService#APOLOGY_SETTLE_DELAY}, long enough for
 *       the person handling the complaint to record a remedy before an unattended message
 *       goes out.
 *   <li><em>Once per order, across rules.</em> {@link AutomationGuardKeys#order} makes a rule's
 *       own firing once per order; two armed rules whose thresholds both fit one late order
 *       are stopped from each apologising by the firing service (the second is cancelled, with
 *       that reason on its run row), by the partial unique index V0581 puts on {@code
 *       (tenant_id, subject_id)} for this kind, and by an idempotency key that names the order
 *       and not the rule. It runs under the same consent, frequency cap, contact policy and
 *       quiet hours as every other trigger.
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
    CASHBACK_CHANGE("minimumChangeMinor"),

    /**
     * A completed order that closed at least {@code lateByMinutes} after the moment it was
     * promised, and that no ADR 0013 remedy covers. Swept; see this enum's own doc for the
     * reconciliation it carries.
     */
    LATE_ORDER_APOLOGY("lateByMinutes");

    private final String configKey;

    AutomationTriggerType(String configKey) {
        this.configKey = configKey;
    }

    /** The one key this trigger's {@code trigger_config} bag must carry, with a non-negative value. */
    public String configKey() {
        return configKey;
    }
}
