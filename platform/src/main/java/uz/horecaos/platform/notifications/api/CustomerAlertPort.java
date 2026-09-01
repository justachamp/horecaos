package uz.horecaos.platform.notifications.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Creates one {@code TRANSACTIONAL_REQUIRED} notification intent for an
 * order's own customer, for a module outside {@code notifications} that has
 * no other way to reach {@code JdbcNotificationStore#createIntent} —
 * {@link OperationsAlertPort}'s customer-facing counterpart, and the seam
 * {@code payments.notifications.FiscalCustomerReceiptTrigger} uses for the
 * OFD-link message ADR 0058 calls "a legal artifact, not a courtesy".
 *
 * <p>{@code OrderNotificationTrigger} does not call this: it lives inside
 * {@code notifications.application} already and writes the row itself,
 * choosing SMS or TELEGRAM the same way this port's implementation does — see
 * {@code CustomerChannelRouter}, which both share.
 *
 * @see OperationsAlertPort the operations-audience twin this mirrors
 */
public interface CustomerAlertPort {

    /**
     * Creates the intent, once, routed to TELEGRAM when the order's customer
     * has an active 1:1 link, the {@code
     * telegram.customer_notifications.enabled} entitlement allows it, and (for
     * a class that respects one) their preference does not refuse it — SMS
     * otherwise, the same three-condition rule {@code OrderNotificationTrigger}
     * applies to confirmation and rejection.
     *
     * <p>Quietly a no-op for a guest order (no customer account to notify) —
     * the ordinary, expected case in ADR 0020's own model, not a caller error.
     *
     * @param orderId the notification's {@code subject_id} as well as the
     *                order {@code NotificationEligibilityService} resolves
     *                the recipient and the amount/currency variables from —
     *                {@code evaluate} calls {@code OrderDirectory.summary}
     *                against {@code subject_id} unconditionally, for every
     *                class and every subject type, so a caller cannot name a
     *                subject id other than the order's own and expect
     *                delivery to work. A fiscal document's own id belongs in
     *                {@code idempotencyKey} instead, the way {@code
     *                FiscalCustomerReceiptTrigger} uses it.
     * @param idempotencyKey unique per (subject, template); a repeat is the
     *                       expected shape of at-least-once delivery and is
     *                       treated as success, not retried
     */
    void notifyCustomer(
            UUID tenantId,
            UUID orderId,
            String templateKey,
            String subjectType,
            @Nullable UUID triggerEventId,
            String idempotencyKey,
            Map<String, String> variables,
            Instant now,
            Duration expiry);
}
