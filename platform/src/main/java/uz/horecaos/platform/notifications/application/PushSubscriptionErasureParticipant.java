package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcPushSubscriptionStore;

/**
 * Ends an erased customer's Web Push subscriptions (ADR 0029, ADR 0182).
 *
 * <p>A subscription is a capability that names a device, so an erased customer's must not outlive the
 * erasure: the ciphertext is cleared in the same statement that ends the row, and the endpoint stops
 * being found. Idempotent under retry, as {@link CustomerErasureParticipant} requires: a second call finds
 * nothing live.
 */
@Component
@ErasesCustomerData(
        name = "notifications-push",
        tables = {"notifications.push_subscriptions"})
public class PushSubscriptionErasureParticipant implements CustomerErasureParticipant {

    private final JdbcPushSubscriptionStore subscriptions;
    private final JdbcNotificationStore notifications;
    private final Clock clock;

    public PushSubscriptionErasureParticipant(
            JdbcPushSubscriptionStore subscriptions, JdbcNotificationStore notifications, Clock clock) {
        this.subscriptions = subscriptions;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        Instant now = clock.instant();
        var ended = subscriptions.endAllOf(tenantId, customerAccountId, JdbcPushSubscriptionStore.ENDED_ERASED, now);
        ended.forEach(subscriptionId -> notifications.retirePushEndpoint(tenantId, subscriptionId, now));
        return ErasureCounts.of("notifications.push_subscriptions", ended.size());
    }
}
