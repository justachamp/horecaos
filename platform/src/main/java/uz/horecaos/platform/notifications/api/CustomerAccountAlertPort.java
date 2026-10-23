package uz.horecaos.platform.notifications.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Creates one customer-facing notification intent for a customer that is already known by account, with no order
 * behind it (ADR 0020, ADR 0181).
 *
 * <p>{@link CustomerAlertPort}'s sibling for the message that is about the customer and not about an order: a
 * loyalty balance about to lapse, a refund that is a person's and not an order's. {@code CustomerAlertPort}
 * cannot carry it, because it resolves the recipient from an order; this one is handed the account, which the
 * intent stores as {@code recipient_account_id} so that {@code NotificationEligibilityService} skips the order
 * lookup -- the path a campaign message already takes -- and goes straight to consent, preference, endpoint and
 * the active template.
 *
 * <p>The class is {@code TRANSACTIONAL_REQUIRED}, the precedent ADR 0159's rating prompt takes, until counsel
 * rules otherwise (ADR 0020, open input on legal basis); the choice is a constant in the caller's adapter, not a
 * parameter here, so a caller cannot reach for {@code MARKETING} and bypass its consent. A template nobody has
 * activated sends nothing: the intent is suppressed as {@code NO_ACTIVE_TEMPLATE}, which is what built dark means.
 *
 * <p>The variables are typed strings and nothing else: an amount, a date, a count. Never a name, a phone number or
 * a message body (ADR 0029); the template resolves the rest on the notification side from the account id.
 */
public interface CustomerAccountAlertPort {

    /**
     * Creates the intent, once.
     *
     * <p>The channel is TELEGRAM when the customer has an active 1:1 link and the tenant is entitled to Telegram
     * customer notifications, SMS otherwise: the same rule {@code CustomerAlertPort} applies.
     *
     * @return true when an intent was created, false when one with this {@code idempotencyKey} already exists --
     *     the expected shape of at-least-once delivery, and a success for the caller either way
     */
    boolean notifyAccount(AccountAlert alert);

    /**
     * Whether an intent for this template key would have something to send for this customer today.
     *
     * <p>True when the channel the customer would be reached by exists, a wording for the key is active for the
     * brand (or the platform's own stands in) and, where the SMS gateway moderates wordings, has been approved.
     * Those are the facts a tenant controls, and the ones that suppress an intent {@code NO_ACTIVE_TEMPLATE},
     * {@code NO_TEMPLATE_FOR_LOCALE}, {@code TEMPLATE_AWAITING_PROVIDER} or {@code CHANNEL_NOT_AVAILABLE} after it
     * is created. A caller whose message may be sent only once asks first: creating an intent that eligibility will
     * suppress still takes the day's idempotency key, and the caller counts the message as spent.
     *
     * <p>What the customer decides -- a preference, quiet hours, a missing contact -- is not asked here: those are
     * about this customer, and the intent records them as a suppression reason the tenant can read.
     */
    boolean canDeliver(UUID tenantId, UUID brandId, UUID customerAccountId, String templateKey);

    /**
     * @param brandId the brand the message is sent in the name of, which selects the template and the consent scope
     * @param customerAccountId the recipient; the intent's {@code recipient_account_id}
     * @param subjectType what the message is about, up to 32 characters
     * @param subjectId the id of that subject; it is not looked up as an order
     * @param idempotencyKey unique per (subject, template, occasion); a repeat is treated as success
     */
    record AccountAlert(
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            String templateKey,
            String subjectType,
            UUID subjectId,
            String idempotencyKey,
            Map<String, String> variables,
            Instant now,
            Duration expiry) {}
}
