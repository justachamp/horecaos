package uz.horecaos.platform.notifications.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;

/**
 * Whether a customer-facing intent should route to TELEGRAM instead of its
 * configured default (ADR 0058 stage 2, "Telegram is additive for customers,
 * never the assumed default").
 *
 * <p>Called once, at intent creation — by {@link OrderNotificationTrigger}
 * directly, and by {@link CustomerAlertFanoutService} for every module
 * outside {@code notifications} — never at eligibility. The channel a message
 * was created for is what {@code NotificationEligibilityService} resolves a
 * recipient for and what the delivery worker sends on; deciding it once, up
 * front, is what makes a link that dies before delivery an honest {@code
 * NO_RECIPIENT_ENDPOINT} suppression (the binding this intent was routed to
 * is gone by the time eligibility looks for it) rather than a silent,
 * unaccountable fallback to SMS for a message that already claims to be a
 * Telegram send.
 *
 * <p>Deliberately fails open. {@code OrderNotificationTrigger} creates its
 * intent inside the same transaction as the order confirmation it reports —
 * "a notification problem must not fail, or reverse, a confirmation the
 * restaurant has already made" is that class's own governing rule, and a
 * channel-routing read is not exempt from it just because this build added
 * it. Any failure here is swallowed and answered with {@code fallback}: the
 * message still sends, on the channel it would have used before this
 * feature existed.
 */
@Component
public class CustomerTelegramChannelRouter {

    private static final Logger log = LoggerFactory.getLogger(CustomerTelegramChannelRouter.class);

    private final JdbcNotificationStore notifications;
    private final EntitlementService entitlements;

    public CustomerTelegramChannelRouter(JdbcNotificationStore notifications, EntitlementService entitlements) {
        this.notifications = notifications;
        this.entitlements = entitlements;
    }

    /**
     * @param customerAccountId null for a guest order, which has no link to
     *                          route to and always answers {@code fallback}
     * @param notificationClass decides whether a preference can veto the
     *                          switch: {@code TRANSACTIONAL_REQUIRED} "sends
     *                          whenever linked" (ADR 0058) and never consults
     *                          one, matching {@link NotificationClass#respectsPreference()}
     * @param fallback the channel used when Telegram does not apply — SMS
     *                 for every caller today, passed rather than hard-coded
     *                 so a caller's own configured default stays the answer
     */
    public NotificationChannel resolve(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID customerAccountId,
            NotificationClass notificationClass,
            NotificationChannel fallback) {
        if (customerAccountId == null) {
            return fallback;
        }
        try {
            if (!entitlements.featureEnabled(tenantId, EntitlementKeys.TELEGRAM_CUSTOMER_NOTIFICATIONS_ENABLED)) {
                return fallback;
            }
            if (notifications
                    .activeCustomerTelegramEndpointId(tenantId, customerAccountId)
                    .isEmpty()) {
                return fallback;
            }
            if (notificationClass.respectsPreference()) {
                boolean disabled = notifications
                        .effectivePreference(
                                tenantId,
                                customerAccountId,
                                brandId,
                                notificationClass.name(),
                                NotificationChannel.TELEGRAM.name())
                        .map(preference -> !preference.enabled())
                        .orElse(false);
                if (disabled) {
                    return fallback;
                }
            }
            return NotificationChannel.TELEGRAM;
        } catch (RuntimeException failure) {
            log.warn(
                    "Telegram channel routing failed for a customer in tenant {}; falling back to {}: {}",
                    tenantId,
                    fallback,
                    failure.toString());
            return fallback;
        }
    }
}
